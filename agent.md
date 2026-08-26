# Loopin Player 2.0 — Documento mestre da reconstrução

> Última atualização: 19/08/2026 — estado concluído até a Fase 7.4.
>
> Este arquivo é o ponto de entrada para qualquer pessoa ou agente que continue o trabalho. Ele descreve o que existe, por que existe, como validar e, principalmente, o que ainda **não** existe. Consulte também os documentos em `docs/` para auditorias e resultados detalhados de cada fase.

## 1. Missão do projeto

Loopin Player 2.0 é uma reconstrução isolada do player Android da Loopin, escrita em Kotlin e destinada principalmente a TV Boxes MXQ de baixo custo operando 24/7.

Ordem permanente de prioridades:

1. estabilidade;
2. recuperação automática;
3. baixo consumo de recursos;
4. funcionamento offline;
5. gerenciamento remoto;
6. facilidade de manutenção;
7. experiência visual, desde que não prejudique os itens anteriores.

O projeto não é uma simples atualização in-place. Ele usa package próprio e não substitui o Player de produção.

## 2. Limites de segurança e escopo

Nunca alterar como consequência deste projeto:

- Loopin Admin de produção;
- Player Android de produção;
- firmware, bootloader ou configurações destrutivas da MXQ;
- banco/Supabase de produção;
- projetos externos ao `LoopinPlayer2`.

Identidade Android atual:

- applicationId: `com.loopin.player2`;
- versão: `2.0.0-phase7.4`;
- versionCode: `1`;
- minSdk: 21;
- targetSdk/compileSdk: 36;
- orientação: landscape;
- release: minificado e com resource shrinking;
- `largeHeap=false`;
- backup Android desabilitado.

Ainda não afirmar compatibilidade com MXQ. Toda validação física permanece pendente até execução no hardware real.

## 3. Princípios técnicos inegociáveis

- Inicialização crítica nunca depende da rede.
- O último conteúdo válido deve sobreviver a falhas de sync, download, manifesto e update.
- Arquivo `.part` nunca é conteúdo reproduzível.
- O ponteiro ACTIVE só muda depois de preparação e validação completas.
- Não usar WebView para reprodução ou widgets.
- Não manter polling agressivo, websocket, MQTT ou threads permanentes.
- Não adicionar biblioteca sem necessidade demonstrável.
- Não carregar a playlist inteira como bitmaps/decoders na memória.
- Somente o item atual mantém recursos pesados.
- Não implementar instalação silenciosa presumindo permissões inexistentes.
- Não tratar o código amigável como identidade interna ou como globalmente único.
- Não esconder limitações de emulador ou afirmar resultado de hardware não testado.

## 4. Estrutura de módulos

### `app`

Camada Android e composição final:

- `LoopinApplication` e `AppContainer`;
- `MainActivity` e lifecycle;
- boot, launcher HOME e kiosk;
- adaptadores Android de identidade, health, update e pairing;
- scheduler Android de sincronização;
- repositório local/mock de validação;
- composição visual, relógio e renderer WEATHER;
- scripts e recursos locais.

### `core:model`

Modelos Kotlin puros compartilhados:

- identidade e configuração essencial;
- estado do dispositivo;
- playlist e mídia;
- `PlaylistContent` normal/dinâmico;
- contratos de logger, telemetria e comandos antigos.

Não depende de Android.

### `core:foundation`

Fundação Android leve:

- `EssentialConfigStore`;
- `DeviceStateManager`;
- `NetworkStateObserver`;
- `BoundedFileLogger`;
- `GlobalExceptionHandler`;
- placeholders deliberadamente diferidos.

### `core:playback`

Motor e renderização do item atual:

- `LoopingPlaybackEngine`;
- máquina de estados de playback;
- `CurrentItemPlayer`;
- `Media3ItemPlayer` para vídeo/imagem;
- `WeatherItemPlayer` para mídia dinâmica WEATHER;
- `PlaybackSurface`;
- política de retry e avanço.

### `core:media-cache`

Persistência transacional de mídia:

- manifesto versionado;
- download/entrada segura;
- estados de cache;
- objetos imutáveis por hash;
- staging;
- ACTIVE/PREVIOUS;
- rollback e recuperação de abandono.

### `core:sync`

Sincronização remota abstrata e update:

- fontes de manifesto/mídia;
- HTTP leve com `HttpURLConnection`;
- `SyncManager` e backoff;
- contratos e preparação segura de APK;
- validação de tamanho, SHA-256 e assinatura.

Não existe endpoint real configurado.

### `core:operations`

Operação local:

- pairing;
- health;
- heartbeat;
- comandos futuros;
- estado operacional de update;
- eventos de operação.

### `core:content`

Composição e regras de conteúdo:

- `ContentItem`, tipos, agenda e prioridade;
- scheduler e resolver;
- layouts, temas e transições;
- clima, cache e seleção de background;
- política de memória de imagens.

## 5. Histórico da reconstrução

### Fase 1 — Fundação

Entregou:

- projeto modular isolado;
- identidade persistente;
- configuração essencial local;
- inicialização após boot;
- launcher/kiosk;
- lifecycle robusto;
- logger limitado;
- estado do dispositivo;
- contratos de telemetria/comandos;
- tratamento global de exceções;
- operação offline.

### Separação da identidade

Existem duas identidades diferentes:

1. `internalId`: identidade técnica persistente, nunca mostrada nem substituída;
2. `friendlyCode`: seis dígitos para suporte e futuro pareamento.

O código amigável é derivado por SHA-256 da identidade interna e persistido. Existem somente 900 mil códigos úteis; colisão é possível. O backend futuro deve reservar/validar a associação e nunca confiar somente no código.

Recuperação após reinstalação é possível quando o firmware preserva `ANDROID_ID`. Caso contrário, usa UUID aleatório persistido. Não prometer persistência após factory reset.

### Fase 2 — Playback local

Entregou:

- máquina de estados explícita;
- vídeo Media3/ExoPlayer;
- imagem com bitmap RGB_565 amostrado;
- duração para imagem;
- loop determinístico;
- retry único por item;
- salto de mídia inválida;
- estado ERROR seguro quando tudo falha;
- liberação por lifecycle.

Media3 permanece na linha 1.8.1 para manter API 21. Não atualizar para uma versão que eleve o minSdk sem decisão explícita e validação em MXQ.

### Fase 3 — Manifesto e cache

Entregou:

- `MediaManifest` versionado;
- estados MISSING/DOWNLOADING/READY/INVALID/FAILED;
- arquivo temporário `.part`;
- tamanho/checksum antes da promoção;
- persistência local do último manifesto válido;
- cache offline.

### Fase 3.1 — Auditoria de confiabilidade

Auditou interrupções, arquivos incompletos, validade, recuperação e riscos de publicação parcial. A conclusão principal foi que baixar arquivos individualmente não bastava: a playlist precisava de commit atômico.

### Fase 3.2 — Publicação atômica

Entregou:

- objetos endereçados por hash;
- diretório de staging;
- versão preparada READY;
- commit ACTIVE/PREVIOUS;
- escrita durável com fsync;
- recuperação de `.part` e staging abandonado;
- rollback;
- playback somente de objetos publicados.

### Fase 4 — Sync remoto e fundação de update

Entregou:

- abstrações de fonte remota/local;
- HTTP em streaming, sem arquivo inteiro na RAM;
- manifesto limitado a 1 MiB;
- timeouts de conexão/leitura;
- cancelamento cooperativo;
- estados e eventos de sync;
- concorrência única via `AtomicBoolean`;
- retry 30 s, 2 min, 10 min e depois janela regular;
- `JobScheduler` one-shot, padrão 6 horas;
- fundação segura do auto-update.

Nenhum job é registrado enquanto não houver URL HTTPS válida. Isso é intencional.

### Fase 5 — Operação e diagnóstico

Entregou:

- diagnóstico local oculto;
- fluxo “Aguardando configuração…”;
- `DevicePairingManager`;
- `DeviceHealthManager`;
- heartbeat local;
- contratos de comandos;
- canal STABLE/BETA;
- status de update;
- último sync e último erro persistidos.

A tela técnica abre por `INFO`, `GUIDE` ou `F1`. Não deve ficar visível na operação normal.

### Fase 6 — Composição dinâmica inicial

Entregou contratos de conteúdo, scheduler, layouts, temas, transições, relógio, data e clima desacoplado. A primeira implementação colocou clima como overlay, o que foi posteriormente considerado conceitualmente incorreto.

Também eliminou `java.time` no caminho Android porque API 21 não o oferece sem core library desugaring. O scheduler usa representação própria de dia/minuto e `Calendar` no adaptador Android.

### Fase 7 — WEATHER como item da playlist

Corrigiu a Fase 6:

- clima não é mais overlay permanente;
- WEATHER é `DynamicMediaContent` dentro de `PlaylistItem`;
- anúncios não mostram cidade/temperatura/card;
- o mesmo engine controla normal e dinâmico;
- `ScheduledItemPlayer` escolhe renderer;
- `WeatherItemPlayer` devolve `onCompleted` ao motor;
- relógio permanece discreto;
- data não mostra mais dia da semana;
- backgrounds são selecionados por condição e dia/noite.

Programação mock atual, declarada explicitamente:

```text
video-a
weather-a
image-a
video-b
weather-b
```

Essa sequência é apenas fixture local. Não introduzir regra “depois de N anúncios”. O futuro manifesto/Admin deve informar a ordem.

### Fase 7.1 — Composição visual portrait

Adicionou uma camada de apresentação independente do motor:

- `ContentPresentation` define orientação e política de escala;
- `PORTRAIT` 9:16 é o padrão;
- `LANDSCAPE` 16:9 e `AUTO` estão preparados no modelo, sem experiência completa;
- `ContentCanvasLayout` mede somente o canvas de conteúdo;
- vídeo e imagem usam crop central uniforme, sem esticar;
- WEATHER, relógio e data compartilham o canvas portrait;
- margens do relógio e largura do card climático são relativas ao canvas;
- Activity, playlist, cache, sync, identidade, operações e lifecycle não foram orientados nem duplicados.

Em um display horizontal 1600 × 900, o canvas portrait observado mede 506 × 900 e fica centralizado. Em uma superfície portrait compatível, a mesma medição pode preencher 9:16 sem reescrever o player.

### Fase 7.2 — WEATHER Liquid Glass e vídeo climático

Reconstruiu somente a apresentação WEATHER:

- `GlassPanelDrawable` simula vidro com gradiente, alpha, borda e highlight, sem blur;
- card principal e previsão agora são painéis separados;
- previsão possui até quatro células internas leves;
- vídeo local permanece atrás da interface durante WEATHER;
- `WeatherBackgroundCatalog` mapeia condição para URI local com fallback determinístico;
- o catálogo não carrega os vídeos: somente o item atual é entregue ao `WeatherItemPlayer`;
- nenhuma dependência, WebView, shader pesado ou segundo player simultâneo foi adicionado;
- o seletor agora reconhece também “nublado” em português.

Somente o `sample_video.mp4` está disponível nesta fase e funciona como placeholder/fallback. Os vídeos climáticos finais precisam ser previamente tratados, adicionados a `res/raw` e registrados no catálogo.

### Fase 7.3 — Vidro exclusivo do clima

Refinou a direção visual sem mudar arquitetura:

- relógio/data não têm vidro, fundo ou borda;
- apenas painel climático e previsão usam `GlassPanelDrawable`;
- o vidro ganhou gloss superior, brilho especular radial, borda óptica em gradiente, aro interno e profundidade;
- previsão é uma lâmina única com separadores discretos, não uma coleção de cards;
- o efeito permanece estático e não amostra nem desfoca o vídeo;
- anúncios continuam sem cidade, temperatura ou painéis climáticos.

### Fase 7.4 — Port fiel do WEATHER antigo

O Android antigo do repositório `Gado77/Loopin.tv`, commit `b7393f4`, foi auditado antes da implementação. O renderer autoritativo estava em `MainActivity.kt:872–1168`.

Foram transportados para a arquitetura atual:

- os cinco vídeos climáticos H.264 de aproximadamente 10 segundos;
- overlay preto `#55000000`;
- vidro branco `#20FFFFFF` com borda `#30FFFFFF`;
- cápsula interna `#30000000`;
- título espaçado, cidade uppercase bold, temperatura 80 sp bold e descrição;
- mapeamento limpo/nublado/chuva para dia/noite.

Os vídeos copiados têm SHA-256 idêntico ao legado. Não foram copiados Supabase, APIs, polling, cache, slots A/B nem ExoPlayer criado na Activity. Máxima/mínima, sensação e quatro dias são extensões atuais, visualmente adaptadas ao legado.

## 6. Modelo atual de playlist

`Playlist` contém uma lista ordenável de `PlaylistItem`.

Cada item contém `PlaylistContent`:

```text
PlaylistContent
├── NormalMediaContent
│   └── PlayableMedia (VIDEO ou IMAGE)
└── DynamicMediaContent
    └── DynamicContentType.WEATHER
```

`DynamicMediaContent` possui:

- tipo;
- duração;
- mapa de configuração, atualmente incluindo cidade.

O construtor antigo `PlaylistItem(id, order, media)` continua disponível para preservar compatibilidade.

O manifesto/cache remoto ainda serializa apenas mídia normal. Serialização transacional de conteúdo dinâmico é trabalho futuro e deve preservar compatibilidade de schema.

## 7. Playback e lifecycle

Estados:

- IDLE;
- PREPARING;
- PLAYING;
- PAUSED;
- COMPLETED;
- ERROR;
- RECOVERING.

Fluxo:

1. carregar playlist;
2. ordenar por `order` e id;
3. remover itens inválidos;
4. iniciar o primeiro;
5. renderer chama `onStarted`;
6. renderer chama `onCompleted`;
7. engine avança exatamente um índice;
8. ao voltar ao início, incrementa loops.

Falha:

1. libera item atual;
2. tenta uma vez novamente;
3. se persistir, marca falha e avança;
4. se todos falharem, entra em ERROR e mostra fallback Loopin.

Lifecycle Android:

- API > 23: inicia em `onStart`, libera em `onStop`;
- API <= 23: inicia em `onResume`, libera em `onPause`;
- `onDestroy` cancela tudo novamente de forma idempotente;
- immersive mode é reaplicado quando a janela recupera foco.

## 8. Renderer de mídia normal

Vídeo:

- uma instância ExoPlayer por item;
- buffers pequenos para hardware limitado;
- URI sempre local no playback;
- player liberado ao terminar/trocar/sair.

Imagem:

- decode fora da main thread;
- executor de no máximo uma thread e timeout ocioso;
- bounds antes de decodificar;
- `inSampleSize` de acordo com a tela;
- RGB_565;
- somente bitmap atual;
- duração por callback do Handler;
- recycle quando resultado chega após cancelamento.

## 9. Renderer WEATHER

`WeatherItemPlayer`:

- lê o item dinâmico e a cidade configurada;
- consulta `WeatherProvider` sem acoplar a HTTP;
- usa cache quando offline/falha;
- determina dia/noite pelo relógio local;
- escolhe `WeatherBackground`;
- resolve o URI do vídeo por `WeatherBackgroundMediaResolver`;
- reproduz o vídeo preparado em loop durante a duração do item;
- mostra card central;
- suporta pause/resume preservando tempo restante;
- em falha do vídeo, continua com fallback estático;
- libera player, callback e card antes do próximo item.

Backgrounds preparados:

- CLEAR_DAY;
- CLEAR_NIGHT;
- CLOUDY_DAY;
- CLOUDY_NIGHT;
- RAIN_DAY;
- RAIN_NIGHT;
- STORM;
- FALLBACK.

Atualmente todos resolvem para o mesmo vídeo pequeno empacotado. Substituir por vídeos reais preparados é futuro. Não aplicar blur, shader ou conversão durante runtime.

Clima debug atual é mock:

- São José do Piauí;
- 28 °C;
- ensolarado;
- máxima/mínima;
- três dias de previsão.

Release não possui API real. Sem fonte, usa cache ou estado indisponível discreto.

## 10. Relógio e data

Relógio:

- formato padrão `HH:mm`;
- `sans-serif-light` nativa;
- canto superior direito;
- atualização no próximo limite de minuto;
- sem polling por segundo;
- sem recriar Views.

Data:

- formato equivalente a `18 DE AGOSTO`;
- locale do dispositivo;
- sem dia da semana;
- fonte nativa sans-serif.

O relógio fica visível durante mídia normal e WEATHER. O card climático só existe durante WEATHER.

## 11. Clima, cache e offline

Modelo inclui:

- cidade;
- temperatura;
- sensação;
- condição;
- ícone;
- máxima/mínima;
- timestamp;
- previsão curta.

Estados:

- LOADING;
- AVAILABLE;
- STALE;
- UNAVAILABLE.

Separação:

- `WeatherDataSource`: obtenção futura;
- `WeatherCache`: persistência;
- `WeatherRepository`: política source/cache/stale;
- renderer: apresentação.

Nunca colocar HTTP dentro do widget/renderer. Uma futura fonte remota não pode bloquear a main thread; usar job/worker limitado e publicar cache pronto.

## 12. Manifesto e cache transacional

Estrutura conceitual do manifesto:

```text
schemaVersion
playlistId
playlistVersion
generatedAt
items[]
```

Mídia normal contém id, tipo, URL futura, nome lógico, duração, ordem, tamanho, SHA-256, MIME e metadata.

Fluxo seguro:

```text
remote/source
→ arquivo .part
→ tamanho
→ SHA-256
→ objeto imutável
→ staging READY
→ commit ACTIVE/PREVIOUS
→ playback
```

Nunca reproduzir diretamente de URL. Nunca mover ACTIVE antes de todos os itens obrigatórios estarem prontos.

## 13. Sincronização

Estados:

- IDLE;
- CHECKING;
- UP_TO_DATE;
- UPDATE_AVAILABLE;
- PREPARING;
- DOWNLOADING;
- VALIDATING;
- COMMITTING;
- SUCCESS;
- FAILED;
- OFFLINE.

Eventos relevantes são registrados sem log por bloco.

HTTP atual:

- `HttpURLConnection`;
- conexão 10 s;
- manifesto/read 20 s;
- mídia/read 30 s;
- manifesto máximo 1 MiB;
- buffer 8 KiB;
- suporte 304;
- header local `X-Loopin-Playlist-Version`;
- códigos 408, 429 e 5xx retryable;
- cancelamento chama `disconnect()`.

Agendamento:

- `JobScheduler` nativo;
- job persistido;
- requer rede;
- requer storage-not-low no API 26+;
- nenhuma thread enquanto o job não executa;
- intervalo mínimo 15 min;
- padrão 6 h;
- endpoint vazio por padrão.

## 14. Auto-update

Fundação existente:

- metadata de versão/canal/URL/tamanho/SHA-256;
- sem downgrade (`versionCode` remoto deve ser maior);
- streaming para `.part`;
- espaço reservado;
- tamanho, hash, package e certificado;
- rename somente após validação;
- APK preparado preservado se instalação falhar;
- interface `PlayerInstaller`.

Não existe fonte remota concreta nem instalação automática.

Android comum pode exigir “instalar apps desconhecidos” e confirmação do sistema. Instalação silenciosa normalmente exige Device Owner/MDM ou capacidade documentada do fabricante. ADB install não torna o app Device Owner.

Canais:

- STABLE (default);
- BETA.

## 15. Operação, pairing e diagnóstico

Pairing:

- UNPAIRED;
- PAIRING;
- PAIRED;
- PAIRING_ERROR.

Campos preparados:

- estabelecimento;
- nome da tela;
- cidade;
- localização lógica;
- playlist;
- configurações do dispositivo.

Não há backend. O código de seis dígitos não garante unicidade.

Health sob demanda:

- uptime;
- memória disponível;
- armazenamento;
- conexão;
- versão;
- playback;
- cache;
- sync;
- último sync;
- último erro.

Heartbeat é somente contrato/local. Não envia nada.

Comandos reservados, mas não executados remotamente:

- RELOAD_PLAYLIST;
- SYNC_NOW;
- RESTART_PLAYER;
- CLEAR_CACHE;
- CHECK_UPDATE;
- REBOOT_DEVICE;
- CAPTURE_SCREENSHOT;
- GET_STATUS.

Não implementar ação destrutiva apenas porque o enum existe.

## 16. Persistência local

Principais áreas:

- `loopin_player_essential_config`: internalId, friendlyCode, kiosk e schema;
- `loopin_pairing`: estado e assignment;
- `loopin_update_settings`: canal;
- `loopin_operational_state`: último sync/erro;
- `loopin_remote_sync`: URL e intervalo;
- `loopin_sync_schedule_state`: falhas consecutivas;
- `loopin_weather_cache`: último clima válido;
- `files/transactional-media`: objetos, staging, versões e ponteiros;
- `files/diagnostics`: logs limitados.

Alterações em formato persistido exigem migração compatível. Não limpar SharedPreferences/cache automaticamente.

## 17. Logger e eventos

`BoundedFileLogger` escreve Logcat e:

- `files/diagnostics/player.log`;
- `files/diagnostics/player.previous.log`.

Cada arquivo é limitado a 512 KiB. Mensagens e stack traces também são truncados.

Famílias de evento:

- DEVICE_STARTED/READY/OFFLINE/ONLINE;
- pairing;
- sync;
- update;
- cache/playback;
- CONTENT_SELECTED/STARTED/FINISHED/ERROR;
- WIDGET_STARTED/FAILED;
- WEATHER_UPDATED/STALE/UNAVAILABLE;
- LAYOUT_CHANGED;
- TRANSITION_STARTED.

Não criar log por frame, segundo, bloco de download ou callback repetitivo sem mudança real.

## 18. Boot e kiosk

Manifest declara:

- LAUNCHER;
- LEANBACK_LAUNCHER;
- HOME;
- BOOT_COMPLETED;
- quick boot comum de OEM.

O app:

- mantém tela ligada;
- usa immersive sticky;
- bloqueia Back/Home/Menu/Recents dentro do possível;
- só inicia lock task se autorizado.

Limitações Android:

- OEM pode impedir abrir Activity após boot;
- escolher o app como HOME é a rota recomendada;
- force-stop do sistema deixa o pacote em estado stopped até nova abertura explícita;
- lock task completo exige Device Owner/MDM.

## 19. Dependências e performance

Decisões atuais:

- Views Android nativas, sem Compose/AppCompat;
- Media3 somente onde necessário;
- kotlinx serialization já usado no manifesto;
- sem Room;
- sem DI framework;
- sem coroutines;
- sem WorkManager;
- sem WebView;
- sem biblioteca de imagem;
- sem fonte empacotada;
- sem core library desugaring;
- Gradle heap 1536 MiB;
- build não paralelo;
- cache Gradle habilitado.

Política de imagem de conteúdo futuro:

- dimensão padrão máxima 1920 px;
- máximo conceitual 16 MiB decodificados;
- no máximo um item prefetched;
- adaptar a capacidade real da MXQ após medição.

## 20. Toolchain local conhecida

Workspace:

```text
C:\Users\itach\OneDrive\Documentos\ChatGPT\LoopinPlayer2
```

JDK usado:

```text
C:\Users\itach\.cache\loopin-player2-toolchain\jdk
```

Android SDK usado:

```text
C:\Users\itach\.cache\loopin-player2-toolchain\android-sdk
```

ADB alternativo do LDPlayer:

```text
C:\LDPlayer\LDPlayer9\adb.exe
```

Console LDPlayer:

```text
C:\LDPlayer\LDPlayer9\ldconsole.exe
```

Antes de Gradle em PowerShell:

```powershell
$env:JAVA_HOME='C:\Users\itach\.cache\loopin-player2-toolchain\jdk'
$env:ANDROID_HOME='C:\Users\itach\.cache\loopin-player2-toolchain\android-sdk'
```

Build completo:

```powershell
.\gradlew.bat test lint assembleDebug assembleRelease
```

APKs:

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release-unsigned.apk
```

O release é unsigned neste workspace.

## 21. LDPlayer

Serial normalmente observado:

```text
emulator-5554
127.0.0.1:5555
```

Use os scripts em `scripts/adb/`. `Common.ps1` localiza ADB, serial, package e APK.

Scripts disponíveis:

- detectar dispositivo;
- instalar/atualizar debug;
- iniciar/parar;
- verificar processo;
- coletar logcat/dumpsys;
- gerar relatório;
- testar janela offline.

Se a execução de PowerShell estiver bloqueada, usar somente no processo atual:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\adb\script.ps1
```

Não alterar a execution policy global.

O comando de rede do LDPlayer pode interromper temporariamente o próprio canal ADB:

```powershell
& 'C:\LDPlayer\LDPlayer9\ldconsole.exe' action --index 0 --key call.network --value offline
& 'C:\LDPlayer\LDPlayer9\ldconsole.exe' action --index 0 --key call.network --value connect
```

Sempre restaurar em `finally`.

## 22. Estado de validação atual

Última matriz da Fase 7.4:

- 166 execuções de teste;
- zero falhas/erros/skips;
- lint aprovado;
- assembleDebug aprovado;
- assembleRelease aprovado;
- APK debug final instalado;
- versão no LDPlayer: `2.0.0-phase7.4`;
- canvas portrait 506 × 900 centralizado no display 1600 × 900;
- vídeo e imagem com escala uniforme e crop central;
- WEATHER apareceu somente nos itens programados;
- retorno WEATHER → próximo item confirmado;
- relógio e data confirmados;
- dia da semana ausente;
- processo sobreviveu a 20 s offline;
- force-stop/restart confirmado;
- PSS durante WEATHER: 69.596 KiB, aproximadamente 68,0 MiB;
- zero crash, ANR ou OOM.

Capturas atuais:

- `diagnostics/ldplayer/phase7_1-normal.png`;
- `diagnostics/ldplayer/phase7_1-weather.png`.
- `diagnostics/ldplayer/phase7_2-normal-final.png`;
- `diagnostics/ldplayer/phase7_2-weather-final-2.png`.
- `diagnostics/ldplayer/phase7_3-normal.png`;
- `diagnostics/ldplayer/phase7_3-weather.png`.
- `diagnostics/ldplayer/phase7_4-normal.png`;
- `diagnostics/ldplayer/phase7_4-weather-final.png`.

Relatórios:

- `diagnostics/ldplayer/phase7-validation.txt`.
- `docs/PHASE7_1_PORTRAIT_COMPOSITION.md`.
- `docs/PHASE7_2_LIQUID_GLASS_WEATHER.md`.
- `docs/PHASE7_3_WEATHER_GLASS_REFINEMENT.md`.
- `docs/PHASE7_4_LEGACY_WEATHER_PORT.md`.

## 23. Situação da MXQ física

Uma MXQ foi conectada por USB macho-macho, mas ADB não pôde ser assumido sem depuração USB previamente habilitada. Não alterar firmware, não desbloquear bootloader, não fazer factory reset e não tentar mudar configurações Android sem autorização.

Validações físicas ainda necessárias:

- USB/ADB após configuração explícita;
- boot/quick boot;
- escolha HOME;
- lock task/Device Owner;
- codecs H.264 reais;
- estabilidade 24/7;
- memória e CPU prolongadas;
- Ethernet/Wi-Fi;
- teclas do controle;
- armazenamento e filesystem;
- update gerenciado;
- vídeos climáticos finais.

Ver `docs/HARDWARE_CONNECTION_TEST.md`.

## 24. Estado do Admin e backend

O Admin foi analisado apenas como referência nas etapas iniciais. Nenhuma alteração foi feita. O Player 2.0 não está conectado ao Admin ou Supabase.

Não há ainda contrato remoto final para:

- pareamento;
- manifesto dinâmico;
- cidade;
- layouts;
- heartbeat;
- comandos;
- update;
- autenticação/autorização.

Ao integrar futuramente:

- versionar schemas;
- aceitar campos desconhecidos de forma planejada;
- autenticar dispositivo pela identidade interna, não pelo código visual;
- validar colisão do código no backend;
- usar HTTPS;
- preservar último estado válido;
- nunca bloquear startup por falha remota;
- não alterar tabelas/contratos de produção sem migração e aprovação.

## 25. Pendências conhecidas

Prioridade técnica futura, sem autorização implícita para implementar:

1. serializar `DynamicMediaContent` em manifesto versionado;
2. definir contrato backend de pairing;
3. escolher API/backend de clima e política de atualização;
4. fornecer vídeos de background reais e pequenos;
5. mapear cada background a arquivo validado no cache;
6. integrar layout/cidade/duração vindos da programação;
7. implementar source remoto de update;
8. escolher modelo normal vs Device Owner/MDM para instalação;
9. validar tudo na MXQ;
10. executar soak test de 24–72 horas;
11. medir leaks, codec, CPU, temperatura e armazenamento;
12. revisar README, que ainda descreve principalmente fases iniciais.

## 26. Armadilhas importantes

- Não voltar a tornar WEATHER overlay permanente.
- Não criar uma segunda lista para conteúdo dinâmico.
- Não derivar frequência do clima por contador hardcoded.
- Não acessar `PlaylistItem.media` quando o item é dinâmico; verificar `dynamic`/`content` ou usar o player composto.
- Não usar `java.time` no caminho Android API 21 sem decisão de desugaring.
- Não chamar fonte remota de clima na main thread.
- Não permitir que falha de background impeça o callback de conclusão.
- Não salvar dados mock como se fossem resposta de produção.
- Não executar clear cache remoto sem confirmação/política.
- Não usar release unsigned como artefato distribuível.
- Não confundir “processo sobreviveu offline no LDPlayer” com certificação MXQ.
- Não usar `adb install` como evidência de permissão de auto-update silencioso.
- Não apagar ACTIVE/PREVIOUS durante limpeza de staging.
- Não remover arquivos do usuário ou mudanças alheias caso o workspace vire repositório Git.

## 27. Checklist para qualquer nova fase

Antes de editar:

1. ler este arquivo;
2. ler a especificação nova inteira;
3. ler os documentos das fases relacionadas;
4. inspecionar modelos, lifecycle e persistência existentes;
5. confirmar que não está no Admin/produção;
6. preservar API 21 e offline-first;
7. identificar migração de dados, se houver.

Durante:

1. preferir extensão mínima dos contratos;
2. não adicionar dependência por conveniência;
3. manter I/O fora da main thread;
4. tornar cleanup idempotente;
5. registrar somente eventos significativos;
6. criar testes para erro, interrupção, restart e offline;
7. não alterar funcionalidades fora do escopo.

Ao concluir:

1. executar todos os testes;
2. executar lint;
3. compilar debug e release;
4. instalar o APK final, não um build intermediário;
5. validar no LDPlayer;
6. coletar logcat e memória;
7. verificar crash/ANR/OOM;
8. restaurar rede/estado do emulador;
9. documentar decisões e limitações;
10. manter “PENDENTE — VALIDAÇÃO EM MXQ” onde aplicável.

## 28. Índice de documentação

- `README.md`: visão original da fundação/playback; parcialmente desatualizado.
- `docs/VALIDATION.md`: matriz inicial e pendências físicas.
- `docs/HARDWARE_CONNECTION_TEST.md`: diagnóstico USB/ADB da MXQ.
- `docs/LDPLAYER_TEST_ENVIRONMENT.md`: ambiente do LDPlayer.
- `docs/LDPLAYER_TEST_REPORT.md`: validações iniciais no emulador.
- `docs/PHASE3_MANIFEST_CACHE.md`: manifesto/cache.
- `docs/PHASE3_1_AUDIT.md`: auditoria de confiabilidade.
- `docs/PHASE3_2_TRANSACTIONAL_PUBLISH.md`: commit atômico.
- `docs/PHASE4_SYNC.md`: sincronização.
- `docs/PLAYER_AUTO_UPDATE.md`: update e limitações Android.
- `docs/PHASE5_DEVICE_OPERATIONS.md`: operação/diagnóstico.
- `docs/PHASE6_DYNAMIC_CONTENT.md`: composição inicial e histórico do overlay.
- `docs/PHASE7_DYNAMIC_PLAYLIST.md`: arquitetura dinâmica atual e autoritativa.
- `docs/PHASE7_1_PORTRAIT_COMPOSITION.md`: composição portrait, testes e validação no LDPlayer.
- `docs/PHASE7_2_LIQUID_GLASS_WEATHER.md`: WEATHER em vidro leve, catálogo de vídeos e validação.
- `docs/PHASE7_3_WEATHER_GLASS_REFINEMENT.md`: vidro exclusivo do clima e refinamento visual.
- `docs/PHASE7_4_LEGACY_WEATHER_PORT.md`: auditoria do Android antigo, port visual e assets.
- `docs/PHASE7_5_WEATHER_COMPOSITION_REFINEMENT.md`: painel WEATHER único, remoção da cápsula térmica e relógio compacto.
- `docs/PHASE7_6_WEATHER_FOCUS_REFINEMENT.md`: temperatura numericamente centralizada, grau independente e remoção da previsão visual.
- `docs/PHASE8_BACKEND_READINESS_AUDIT.md`: auditoria dos contratos reais do Admin, lacunas de segurança e bloqueios para integração operacional.
- `docs/PHASE8_1_SECURE_PAIRING.md`: contrato e implementação do pareamento rotativo por código/QR, ainda pendente de deploy controlado.

## 29. Fonte de verdade

Quando houver divergência:

1. o código compilado e os testes atuais são a fonte operacional;
2. este `agent.md` é a visão consolidada;
3. o documento da fase mais recente prevalece sobre documentos antigos;
4. README e documentos históricos não devem ser usados para negar funcionalidades adicionadas depois;
5. requisitos explícitos novos do usuário prevalecem, desde que não autorizem implicitamente alteração de produção ou ação destrutiva.

O estado atual é uma base local sólida e demonstrável, mas ainda não é um produto conectado ao backend nem certificado no hardware MXQ.

## 30. Publicação automática no GitHub

- Após cada modificação concluída e validada no Loopin Player 2.0, criar um commit e enviar a alteração para `origin/main` no repositório `Gado77/loopin-player-android-2`.
- Não deixar alterações finalizadas somente no ambiente local.
- Não publicar quando o usuário pedir explicitamente para não enviar, quando a validação necessária falhar ou quando houver risco de incluir segredos ou artefatos indevidos. Nesses casos, informar claramente o bloqueio.
- O Loopin Admin permanece em repositório separado e só deve ser alterado ou publicado quando isso estiver explicitamente dentro do escopo autorizado.
