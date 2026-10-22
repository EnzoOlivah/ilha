package com.ilha.app

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class IslandService : AccessibilityService() {

    // ---- Ajustes finos (mexa aqui se a pílula não ficar perfeita no seu S24 FE) ----
    private object Tune {
        const val EXTRA_HEIGHT_DP = 10      // folga em volta do furo da câmera
        const val COLLAPSED_WIDTH_FACTOR = 3.2f // largura da pílula fechada = altura × fator
        const val COMPACT_WIDTH_FACTOR = 3.4f   // pílula com ícone; maior que isso cobre o relógio e os ícones da barra
        const val OFFSET_X_DP = 0           // empurra para a direita (+) ou esquerda (−)
        const val OFFSET_Y_DP = 0           // empurra para baixo (+) ou para cima (−)
        const val CARD_SHOW_MS = 3000L      // cartão com o texto quando a notificação chega
        const val COMPACT_SHOW_MS = 5000L   // depois, só o ícone ao lado da câmera
        const val EXPANDED_SHOW_MS = 8000L  // tempo depois de segurar para expandir
        const val MUSIC_CARD_MS = 6000L     // cartão com os controles de música
        const val PAUSED_KEEP_MS = 60000L   // a música continua na ilha por 1 min depois de pausar
    }

    /** O que a ilha está mostrando agora. BASE = fechada, ou a música se estiver tocando. */
    private enum class Mode { BASE, NOTIF_CARD, NOTIF_COMPACT, MUSIC_CARD }

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var root: FrameLayout? = null
    private lateinit var bg: GradientDrawable
    private lateinit var card: LinearLayout     // notificação: ícone + título + texto, abaixo da câmera
    private lateinit var icon: ImageView
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var musicCard: LinearLayout // música: capa + nome + controles, abaixo da câmera
    private lateinit var musicArt: ImageView
    private lateinit var musicTitle: TextView
    private lateinit var musicArtist: TextView
    private lateinit var playPause: ImageView
    private lateinit var compactIcon: ImageView // à esquerda da câmera: ícone da notificação ou capa do álbum
    private lateinit var bars: Bars             // à direita da câmera, enquanto a música toca
    private lateinit var lp: WindowManager.LayoutParams

    private var collapsedW = 0
    private var collapsedH = 0
    private var centerX = 0     // centro do furo da câmera, em pixels da borda esquerda
    private var topY = 0
    private var screenW = 0

    private var mode = Mode.BASE
    private var current: IslandEvent? = null
    private var animator: ValueAnimator? = null

    private val toCompactRunnable = Runnable { toCompact() }
    private val baseRunnable = Runnable { goBase() }

    // ---- Música ----
    private var sessions: MediaSessionManager? = null
    private var controllers: List<MediaController> = emptyList()
    private var music: MediaController? = null
    private var musicPlaying = false
    private var pausedAt = 0L
    private var baseIsMusic = false

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        watchControllers(list.orEmpty())
    }
    private val musicCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refreshMusic()
        override fun onMetadataChanged(metadata: MediaMetadata?) = refreshMusic()
        override fun onSessionDestroyed() = refreshMusic()
    }
    private val pauseTimeout = Runnable { refreshMusic() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WindowManager::class.java)
        computeGeometry()
        createView()
        IslandBus.listener = { ev -> handler.post { show(ev) } }
        startMusicWatch()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        IslandBus.listener = null
        handler.removeCallbacksAndMessages(null)
        sessions?.removeOnActiveSessionsChangedListener(sessionsListener)
        controllers.forEach { it.unregisterCallback(musicCallback) }
        bars.playing = false
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val v = root ?: return
        // Em paisagem (vídeos, jogos) a ilha some
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            v.visibility = View.GONE
            return
        }
        v.visibility = View.VISIBLE
        computeGeometry()
        card.layoutParams.width = cardWidth()
        card.setPadding(dp(16), collapsedH, dp(20), dp(14))
        musicCard.layoutParams.width = cardWidth()
        musicCard.setPadding(dp(16), collapsedH, dp(16), dp(10))
        lp.y = topY
        goBase()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun cardWidth(): Int = screenW - dp(24)

    private fun compactWidth(): Int = (collapsedH * Tune.COMPACT_WIDTH_FACTOR).roundToInt()

    private fun iconSize(): Int = dp(20)

    /** Espaço entre a borda da pílula e o ícone/capa (ou as barrinhas). */
    private fun side(): Int = (collapsedH - iconSize()) / 2 + dp(4)

    /** Lê onde fica o furo da câmera e calcula o tamanho da pílula fechada. */
    private fun computeGeometry() {
        val metrics = wm.currentWindowMetrics
        screenW = metrics.bounds.width()
        val cutout = metrics.windowInsets.displayCutout
        val hole = cutout?.boundingRects?.minByOrNull { it.top }

        if (hole != null && hole.top < dp(80)) {
            val diameter = min(hole.width(), hole.height())
            collapsedH = diameter + dp(Tune.EXTRA_HEIGHT_DP)
            topY = hole.centerY() - collapsedH / 2
            centerX = hole.centerX()
        } else {
            // Sem furo detectado: valores padrão
            collapsedH = dp(34)
            topY = dp(10)
            centerX = screenW / 2
        }
        collapsedW = (collapsedH * Tune.COLLAPSED_WIDTH_FACTOR).roundToInt()
        centerX += dp(Tune.OFFSET_X_DP)
        topY = max(0, topY + dp(Tune.OFFSET_Y_DP))
    }

    /** Cantos arredondados para capas e ícones. */
    private fun rounded(radius: Int) = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius.toFloat())
        }
    }

    private fun text(sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        setTextColor(color)
        textSize = sizeSp
        paint.isFakeBoldText = bold
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun mediaButton(res: Int, action: () -> Unit) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setOnClickListener {
            action()
            schedule(baseRunnable, Tune.MUSIC_CARD_MS) // mexeu nos controles: o cartão fica mais um pouco
        }
    }

    private fun createView() {
        bg = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = collapsedH / 2f
        }

        // ---- Cartão da notificação ----
        icon = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        title = text(14f, Color.WHITE, bold = true)
        body = text(12f, Color.rgb(190, 190, 190)).apply { maxLines = 2 }

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(body)
        }

        // O cartão começa abaixo da linha da câmera (padding de cima = altura da pílula),
        // assim o furo nunca tapa o texto.
        card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), collapsedH, dp(20), dp(14))
            alpha = 0f
            addView(icon, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        // ---- Cartão da música ----
        musicArt = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = rounded(dp(8))
            clipToOutline = true
        }
        musicTitle = text(14f, Color.WHITE, bold = true)
        musicArtist = text(12f, Color.rgb(190, 190, 190))
        val musicTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(musicTitle)
            addView(musicArtist)
        }
        val musicTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(musicArt, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) })
            addView(musicTexts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        playPause = mediaButton(android.R.drawable.ic_media_play) {
            val c = music ?: return@mediaButton
            if (musicPlaying) c.transportControls.pause() else c.transportControls.play()
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val size = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(14); marginEnd = dp(14) }
            addView(mediaButton(android.R.drawable.ic_media_previous) { music?.transportControls?.skipToPrevious() }, size)
            addView(playPause, LinearLayout.LayoutParams(size))
            addView(mediaButton(android.R.drawable.ic_media_next) { music?.transportControls?.skipToNext() }, LinearLayout.LayoutParams(size))
        }
        musicCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), collapsedH, dp(16), dp(10))
            alpha = 0f
            visibility = View.INVISIBLE // invisível também para toques, senão os botões roubam o toque da pílula
            addView(musicTop)
            addView(controls, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
        }

        // ---- Pílula compacta: ícone/capa à esquerda da câmera, barrinhas à direita ----
        val iconSize = iconSize()
        val side = side()
        compactIcon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = rounded(dp(5))
            clipToOutline = true
            alpha = 0f
        }
        bars = Bars(this).apply { alpha = 0f }

        val view = FrameLayout(this).apply {
            background = bg
            clipToOutline = true
            // Largura fixa: o texto não se rearruma durante a animação, só vai sendo revelado
            addView(card, FrameLayout.LayoutParams(
                cardWidth(), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ))
            addView(musicCard, FrameLayout.LayoutParams(
                cardWidth(), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ))
            addView(compactIcon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.TOP or Gravity.START).apply {
                leftMargin = side
                topMargin = (collapsedH - iconSize) / 2
            })
            addView(bars, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.TOP or Gravity.END).apply {
                rightMargin = side
                topMargin = (collapsedH - iconSize) / 2
            })
            setOnClickListener { onTap() }
            setOnLongClickListener { onLongPress(); true }
        }

        lp = WindowManager.LayoutParams(
            collapsedW,
            collapsedH,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT // x é calculado a partir do furo (ver animateTo)
            x = centerX - collapsedW / 2
            y = topY
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        wm.addView(view, lp)
        root = view
    }

    // ---------------- Estados ----------------
    // BASE (fechada ou música) → cartão (texto, ao chegar) → compacta (ícone) → BASE

    private fun show(ev: IslandEvent) {
        if (root?.visibility != View.VISIBLE) return
        current = ev
        icon.setImageDrawable(ev.icon)
        // Um Drawable não pode estar em duas views ao mesmo tempo: a compacta usa uma cópia
        compactIcon.setImageDrawable(ev.icon?.constantState?.newDrawable(resources) ?: ev.icon)
        title.text = ev.title
        body.text = ev.text
        showCard(2)
        schedule(toCompactRunnable, Tune.CARD_SHOW_MS)
    }

    private fun expand() {
        if (current == null) return
        showCard(4)
        schedule(toCompactRunnable, Tune.EXPANDED_SHOW_MS)
    }

    private fun toCompact() {
        if (current == null) return goBase()
        mode = Mode.NOTIF_COMPACT
        animateTo(compactWidth(), collapsedH, card = 0f, icon = 1f)
        schedule(baseRunnable, Tune.COMPACT_SHOW_MS)
    }

    /** Volta ao estado de repouso: a música, se estiver tocando, ou a pílula fechada. */
    private fun goBase() {
        mode = Mode.BASE
        current = null
        handler.removeCallbacks(toCompactRunnable)
        handler.removeCallbacks(baseRunnable)
        baseIsMusic = musicShown()
        if (baseIsMusic) {
            compactIcon.setImageDrawable(musicArtwork())
            animateTo(compactWidth(), collapsedH, icon = 1f, bars = 1f)
        } else {
            animateTo(collapsedW, collapsedH)
        }
    }

    /** Mostra o cartão com até [lines] linhas de texto; a altura acompanha o conteúdo. */
    private fun showCard(lines: Int) {
        mode = Mode.NOTIF_CARD
        body.maxLines = lines
        animateTo(cardWidth(), measure(card), card = 1f)
    }

    private fun showMusicCard() {
        mode = Mode.MUSIC_CARD
        animateTo(cardWidth(), measure(musicCard), music = 1f)
        schedule(baseRunnable, Tune.MUSIC_CARD_MS)
    }

    private fun measure(v: View): Int {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(cardWidth(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return v.measuredHeight
    }

    private fun onTap() {
        when (mode) {
            Mode.NOTIF_CARD, Mode.NOTIF_COMPACT -> {
                current?.intent?.let { send(it) }
                goBase()
            }
            Mode.MUSIC_CARD -> {
                // Toque fora dos botões abre o app de música
                music?.sessionActivity?.let { send(it) }
                goBase()
            }
            Mode.BASE -> if (baseIsMusic) showMusicCard()
        }
    }

    private fun onLongPress() {
        when (mode) {
            Mode.NOTIF_CARD, Mode.NOTIF_COMPACT -> expand()
            Mode.BASE -> if (baseIsMusic) showMusicCard()
            Mode.MUSIC_CARD -> {}
        }
    }

    private fun send(pi: PendingIntent) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 16 trocou o nome da opção que deixa abrir o app a partir da ilha
                val mode = if (Build.VERSION.SDK_INT >= 36) {
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                } else {
                    @Suppress("DEPRECATION")
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
                val opts = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode)
                pi.send(this, 0, null, null, null, null, opts.toBundle())
            } else {
                pi.send()
            }
        }
    }

    /** Agenda o próximo passo, cancelando o que estava agendado. */
    private fun schedule(next: Runnable, ms: Long) {
        handler.removeCallbacks(toCompactRunnable)
        handler.removeCallbacks(baseRunnable)
        handler.postDelayed(next, ms)
    }

    // ---------------- Música ----------------

    /** Acompanha os players ativos. Funciona porque o NotifListener tem acesso às notificações. */
    private fun startMusicWatch() {
        val msm = getSystemService(MediaSessionManager::class.java) ?: return
        val cn = ComponentName(this, NotifListener::class.java)
        runCatching {
            msm.addOnActiveSessionsChangedListener(sessionsListener, cn, handler)
            sessions = msm
            watchControllers(msm.getActiveSessions(cn))
        }
    }

    private fun watchControllers(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(musicCallback) }
        controllers = list
        controllers.forEach { it.registerCallback(musicCallback, handler) }
        refreshMusic()
    }

    /** Escolhe o player que está tocando (ou o primeiro) e atualiza a ilha. */
    private fun refreshMusic() {
        val playingNow = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val wasPlaying = musicPlaying
        music = playingNow ?: controllers.firstOrNull { it == music } ?: controllers.firstOrNull()
        musicPlaying = playingNow != null
        if (wasPlaying && !musicPlaying) {
            pausedAt = SystemClock.elapsedRealtime()
            handler.removeCallbacks(pauseTimeout)
            handler.postDelayed(pauseTimeout, Tune.PAUSED_KEEP_MS + 100)
        }

        val meta = music?.metadata
        musicTitle.text = meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        musicArtist.text = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
        musicArt.setImageDrawable(musicArtwork())
        playPause.setImageResource(
            if (musicPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        bars.playing = musicPlaying

        when {
            // Começou ou acabou a música: a pílula muda de forma
            mode == Mode.BASE && musicShown() != baseIsMusic -> goBase()
            // Trocou de faixa: só atualiza a capa
            mode == Mode.BASE && baseIsMusic -> compactIcon.setImageDrawable(musicArtwork())
        }
    }

    /** A música fica na ilha enquanto toca e por um tempo depois de pausar. */
    private fun musicShown(): Boolean {
        val c = music ?: return false
        if (c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()) return false
        return musicPlaying || SystemClock.elapsedRealtime() - pausedAt < Tune.PAUSED_KEEP_MS
    }

    /** Capa do álbum; se o app não mandar, o ícone do app. Sempre um Drawable novo. */
    private fun musicArtwork(): Drawable? {
        val c = music ?: return null
        val meta = c.metadata
        val bmp: Bitmap? = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bmp != null) return BitmapDrawable(resources, bmp)
        return runCatching { packageManager.getApplicationIcon(c.packageName) }.getOrNull()
    }

    // ---------------- Animação ----------------

    /**
     * Anima o tamanho da pílula (sempre centralizada no furo da câmera)
     * e a opacidade de cada parte (0 = escondida, 1 = visível).
     */
    private fun animateTo(
        w: Int, h: Int,
        card: Float = 0f, music: Float = 0f, icon: Float = 0f, bars: Float = 0f,
    ) {
        val v = root ?: return
        animator?.cancel()
        val views = listOf(this.card, musicCard, compactIcon, this.bars)
        val targets = floatArrayOf(card, music, icon, bars)
        val starts = FloatArray(views.size) { views[it].alpha }
        // O cartão de música só recebe toques quando está aparecendo
        if (music > 0f) musicCard.visibility = View.VISIBLE
        val sw = lp.width
        val sh = lp.height
        val sx = lp.x
        val x = centerX - w / 2
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380
            interpolator = OvershootInterpolator(0.9f)
            addUpdateListener {
                val f = it.animatedValue as Float
                val fa = f.coerceIn(0f, 1f)
                lp.x = (sx + (x - sx) * f).roundToInt()
                lp.width = (sw + (w - sw) * f).roundToInt()
                lp.height = (sh + (h - sh) * f).roundToInt()
                bg.cornerRadius = min(lp.height / 2f, dp(30).toFloat())
                for (i in views.indices) views[i].alpha = starts[i] + (targets[i] - starts[i]) * fa
                if (fa >= 1f && music == 0f) musicCard.visibility = View.INVISIBLE
                runCatching { wm.updateViewLayout(v, lp) }
            }
            start()
        }
    }

    /** Barrinhas que sobem e descem enquanto a música toca; paradas quando pausa. */
    private class Bars(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val speed = floatArrayOf(1f, 2f, 1f, 2f)
        private val phase = floatArrayOf(0f, 0.9f, 1.8f, 2.6f)
        private var t = 0f
        private val anim = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
        }

        var playing = false
            set(value) {
                field = value
                if (value) { if (!anim.isStarted) anim.start() } else { anim.cancel(); invalidate() }
            }

        override fun onDraw(canvas: Canvas) {
            val n = speed.size
            val bw = width / (n * 2f - 1)
            for (i in 0 until n) {
                val f = if (playing) 0.3f + 0.7f * abs(sin(t * speed[i] + phase[i])) else 0.3f
                val bh = height * f
                val x = i * bw * 2
                val top = (height - bh) / 2
                canvas.drawRoundRect(x, top, x + bw, top + bh, bw / 2, bw / 2, paint)
            }
        }

        override fun onDetachedFromWindow() {
            anim.cancel()
            super.onDetachedFromWindow()
        }
    }
}
