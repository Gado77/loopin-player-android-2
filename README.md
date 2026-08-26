# Loopin Player 2.0 — Fundação e Playback Local

Projeto Android isolado do Loopin Admin e do Player de produção. Esta etapa inicializa o aparelho localmente, mesmo sem rede, e fornece contratos pequenos para as próximas fases.

## Módulos

- `app`: Application, Activity, boot e kiosk.
- `core:model`: identidade, mídia, playlist e contratos Kotlin puros, sem dependência Android.
- `core:foundation`: identidade, configuração essencial, logs, estado, rede e pontos de extensão.
- `core:playback`: máquina de estados, loop, recuperação, Media3 e renderização leve de imagens.

O projeto inclui somente uma playlist local de validação. Não inclui download, sincronização, cache remoto, conteúdo dinâmico, OTA ou comunicação de comandos.

## Playback local — fase 2

`LoopingPlaybackEngine` não depende da Activity nem do ExoPlayer diretamente. Ele controla uma máquina de estados explícita (`IDLE`, `PREPARING`, `PLAYING`, `PAUSED`, `COMPLETED`, `ERROR`, `RECOVERING`) por meio do contrato `CurrentItemPlayer`.

`Media3ItemPlayer` é o adaptador Android. Ele mantém somente o recurso do item atual: uma instância ExoPlayer para vídeo ou um bitmap RGB_565 amostrado para imagem. Ao concluir, falhar, trocar de item ou sair do lifecycle, decoder, superfície, callbacks e bitmap são liberados. Imagens usam um executor temporário de uma thread que expira após cinco segundos, sem polling.

Em erro, o item é liberado e tentado mais uma vez. Persistindo a falha, ele é ignorado e a playlist continua. Se todos os itens falharem no mesmo ciclo, o motor entra em `ERROR` e mantém a tela segura de identificação.

A playlist empacotada valida offline a sequência vídeo → imagem (3 segundos) → vídeo → loop. Os artefatos são H.264 Baseline e PNG em 640×360 e não fazem acesso à rede.

Os contratos `PlaylistRepository`, `LocalMediaResolver` e `PlaylistSyncSource`, junto com referências local/remota no modelo, deixam preparada a evolução `REMOTE → LOCAL CACHE → PLAYBACK`. Apenas o repositório local possui implementação nesta fase.

## Inicialização

1. `LoopinApplication` cria o container em memória.
2. `EssentialConfigStore` lê ou cria a identidade interna e o código amigável com `SharedPreferences.commit()`.
3. O tratamento global de exceções é instalado.
4. `NetworkStateObserver` publica conectividade por callback; falhas e ausência de rede não interrompem a inicialização.
5. `MainActivity` entra em modo imersivo e exibe somente o código amigável persistente.

## Identidade

A identidade interna é um UUID e nunca é exibida nem substituída pelo código amigável. Instalações existentes migram o antigo `installation_id` sem trocar esse UUID. Em uma instalação nova, o UUID é derivado do `ANDROID_ID` quando ele é válido; isso permite recuperá-lo após reinstalação nos aparelhos em que o Android mantém esse valor. Firmwares com `ANDROID_ID` ausente ou reconhecidamente inválido recebem um UUID aleatório persistido.

O código amigável contém exatamente seis dígitos, é derivado por SHA-256 da identidade interna e também é persistido. Ele funciona inteiramente offline. Como existem apenas 900 mil códigos de seis dígitos úteis, colisões não podem ser eliminadas somente no aparelho: o futuro pareamento deverá reservar o código com restrição única no backend e solicitar/atribuir outro código em caso de conflito. Essa comunicação não faz parte desta etapa.

Configurações essenciais são armazenadas em `loopin_player_essential_config`. A migração é local e não reutiliza dados do Player 1.x.

## Kiosk e boot

O app declara categorias `HOME`, `LAUNCHER` e `LEANBACK_LAUNCHER`, mantém a tela ligada e reaplica immersive sticky ao recuperar foco. Lock task só é iniciado quando o pacote foi autorizado por Device Owner/MDM, evitando screen pinning interativo.

`BootReceiver` cobre boot padrão e ações quick-boot comuns. Android/OEM pode bloquear abertura de Activity em background; configurar o Player como launcher HOME é a garantia recomendada para TV Boxes gerenciadas.

## Logs

`BoundedFileLogger` centraliza Logcat e dois arquivos locais de até 512 KiB cada:

- `files/diagnostics/player.log`
- `files/diagnostics/player.previous.log`

O logger limita tamanhos de tag, mensagem e stack trace. Não existe envio de logs nesta etapa.

## Estado e extensões futuras

`DeviceStateManager` mantém um snapshot thread-safe e notificações leves, sem polling. Os estados `READY_OFFLINE` e `READY_ONLINE` são igualmente válidos.

`TelemetrySink` e `RemoteCommandHandler` definem contratos, mas as implementações atuais são deliberadamente diferidas e não abrem rede nem criam threads.

## Decisões importantes

- APIs de plataforma no app em vez de AppCompat/Compose.
- Nenhum serviço permanente nesta fundação.
- Nenhuma DI, banco, coroutines, WorkManager ou WebView.
- Media3 limitado a `media3-exoplayer` e `media3-ui` 1.8.1. A linha foi escolhida para preservar `minSdk 21`; Media3 1.9+ exige API 23.
- Sem preload: um vídeo ou uma imagem por vez.
- `largeHeap` desativado.
- release minificado e com shrink de recursos.
- Java 17 apenas no build; compatibilidade de dispositivo continua definida por `minSdk 21`.
- `targetSdk/compileSdk 36`, alinhados ao Player auditado, com package novo `com.loopin.player2` para não substituir produção.

## Build e testes

```text
gradlew.bat test assembleDebug assembleRelease lint
```

Testes instrumentados e validação de boot/lock task exigem um aparelho ou emulador; os testes unitários puros não dependem de Android.
