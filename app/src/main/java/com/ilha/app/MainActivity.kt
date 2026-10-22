package com.ilha.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        col.addView(TextView(this).apply {
            text = "Ilha"
            textSize = 30f
            setTextColor(Color.WHITE)
            paint.isFakeBoldText = true
            gravity = Gravity.START
        })

        status = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(0, pad, 0, pad)
        }
        col.addView(status)

        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
            col.addView(this)
        }

        // A Play Store exige um aviso claro, com aceite, antes de pedir cada permissão sensível
        button("1. Acesso às notificações") {
            disclosure(
                "Acesso às notificações",
                "A Ilha lê o título, o texto e o ícone das notificações que chegam, e as informações " +
                    "da música que está tocando, só para mostrá-los na ilha ao redor da câmera.\n\n" +
                    "Nada é guardado, enviado para a internet ou compartilhado: tudo fica no seu celular."
            ) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        button("2. Acessibilidade → Apps instalados → Ilha") {
            disclosure(
                "Uso da Acessibilidade",
                "A Ilha usa o serviço de Acessibilidade do Android só para desenhar a ilha por cima " +
                    "das outras telas, ao redor da câmera, e para receber seus toques nela.\n\n" +
                    "Ela não lê o conteúdo da tela, não registra o que você digita e não coleta nem " +
                    "compartilha nenhum dado.\n\nPara continuar, ative \"Ilha\" na próxima tela."
            ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        button("3. Bateria sem restrição") {
            // Abre a lista de otimização de bateria; aí é só escolher a Ilha e "Não otimizar"
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        button("Se o passo 1 ou 2 estiver bloqueado: Info do app") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
        button("Testar a ilha") {
            val ok = IslandBus.post(
                IslandEvent(
                    "A ilha está funcionando",
                    "Toque para fechar. Segure para expandir e ver o texto completo da notificação.",
                    applicationInfo.loadIcon(packageManager),
                    null
                )
            )
            if (!ok) Toast.makeText(this, "Ative a Acessibilidade primeiro (passo 2)", Toast.LENGTH_LONG).show()
        }

        // Android 16 desenha o app atrás das barras: o conteúdo respeita a área livre
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            isFillViewport = true
            // Mesmo degradê do ícone; num fundo claro a ilha preta aparece bem
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(0x3B, 0x82, 0xF6), Color.rgb(0x8B, 0x5C, 0xF6))
            )
            addView(col)
        })
    }

    /** Aviso com aceite antes de abrir a tela de permissão. */
    private fun disclosure(title: String, message: String, onAccept: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Aceitar e continuar") { _, _ -> onAccept() }
            .setNegativeButton("Agora não", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        val notif = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        val access = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("$packageName/") == true
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

        fun mark(b: Boolean) = if (b) "✅" else "❌"
        status.text = "${mark(notif)} Acesso às notificações\n" +
            "${mark(access)} Acessibilidade\n" +
            "${mark(battery)} Bateria sem restrição"
    }
}
