# Ilha: publicar na Play Store

## Arquivos desta pasta

| Arquivo | Onde vai no Play Console |
|---|---|
| `Ilha-release.aab` (assinado com a chave de upload) | Testar e lançar → Versão → Enviar pacote |
| `icone-512.png` | Página da loja → Ícone do app (512×512) |
| `destaque-1024x500.png` | Página da loja → Recurso gráfico (1024×500) |
| `print-1-app.png`, `print-2-notificacao.png`, `print-3-icone.png` (1080×2160) | Página da loja → Capturas de tela do telefone |
| `politica-de-privacidade.html` | Publique num link público (ex.: GitHub Pages, Google Sites) e cole o link em Conteúdo do app → Política de privacidade |

## Textos da página da loja

**Nome do app** (até 30 caracteres):
Ilha · Ilha Dinâmica

**Descrição curta** (até 80 caracteres):
Uma ilha dinâmica ao redor da câmera, com notificações e música.

**Descrição completa:**

A Ilha transforma o furo da câmera do seu celular numa ilha dinâmica, no estilo do HyperOS e do iPhone.

• Notificações na ilha: quando chega uma mensagem, a ilha abre um cartão com o texto abaixo da câmera e depois encolhe para um ícone ao lado dela. Toque para abrir, segure para ler tudo.
• Música na ilha: enquanto toca, a capa do álbum e barrinhas animadas ficam ao redor da câmera. Toque para ver os controles: anterior, tocar/pausar e próxima.
• Sempre ativa: a ilha fica sempre visível ao redor da câmera e some sozinha em vídeos e jogos na horizontal.
• Leve e sem anúncios: sem compras, sem internet, sem coleta de dados.

A Ilha usa o serviço de Acessibilidade apenas para desenhar a ilha por cima das outras telas e receber seus toques. Ela não lê o conteúdo da tela nem coleta dados.

Feita e testada no Samsung Galaxy S24 FE. Funciona em celulares com Android 11 ou mais novo e câmera frontal com furo na tela.

**Categoria:** Personalização

## Formulários do Play Console

**Segurança dos dados:** o app não coleta nem compartilha dados. Responda "Não" para coleta e compartilhamento.

**Declaração da API de Acessibilidade** (Conteúdo do app → Acessibilidade):
- O app NÃO é uma ferramenta de acessibilidade (`isAccessibilityTool=false`).
- Uso: "Desenhar uma sobreposição (a ilha dinâmica) ao redor da câmera frontal, por cima de outros apps, e receber toques nela. O serviço não lê o conteúdo da tela nem coleta dados."
- O app mostra um aviso com aceite antes de levar o usuário para ativar o serviço (botão "2. Acessibilidade" → "Aceitar e continuar").
- O Google pede um **vídeo curto** mostrando: abrir o app, tocar no passo 2, o aviso, aceitar, ativar o serviço e a ilha funcionando.

**Classificação de conteúdo:** questionário IARC; o app não tem conteúdo sensível.

**Público-alvo:** maiores de 13 anos (não é para crianças).

## Conta de desenvolvedor pessoal nova

A Google exige um **teste fechado com pelo menos 12 testadores por 14 dias seguidos** antes de liberar o app para todos.

## A cada atualização

1. Aumente `versionCode` em `app/build.gradle.kts` (1 → 2 → 3…).
2. Gere de novo o `.aab` com a mesma chave de upload.
