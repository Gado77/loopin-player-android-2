# Fase 6 — Conteúdo dinâmico e experiência visual

## Escopo

A Fase 6 adiciona composição local de mídia e widgets nativos ao Loopin Player 2.0. Não existe Supabase, Admin, API de clima, backend, editor visual, MQTT, WebSocket ou WebView. O vídeo/imagem e o cache transacional das fases anteriores permanecem responsáveis pela mídia principal.

## Arquitetura

O módulo Kotlin/JVM `core:content` implementa a sequência:

`ContentItem → ContentScheduler → ContentResolver → ContentRenderer → View Android`

Regras de horário, dia, evento e prioridade não ficam na `MainActivity`. O aplicativo Android contém apenas os adaptadores visuais e o lifecycle do overlay.

Contratos principais:

- `ContentItem`, `ContentType`, `ContentSchedule` e `ContentPriority`;
- `ContentRenderer` e `ContentResolver` com registro por tipo;
- `ContentLayout`, `LayoutEngine`, `LayoutTheme` e `TransitionSpec`;
- `WeatherDataSource`, `WeatherCache`, `WeatherProvider` e `WeatherRepository`;
- `ImageMemoryPolicy`.

Tipos preparados: vídeo, imagem, clima, relógio, data, texto, informação e evento especial. Adicionar um renderer não exige modificar o Playback Engine.

## Scheduler e prioridade

`ContentScheduler` opera sobre `Sequence`, filtra antes de materializar e ordena apenas os candidatos ativos. As janelas aceitam horários normais e intervalos que cruzam meia-noite, dias da semana e eventos `COPA`, `FESTA_LOCAL`, `FERIADO` e `DATAS_COMEMORATIVAS`.

Prioridades: `CRITICAL`, `HIGH`, `NORMAL`, `LOW`. Widgets padrão são LOW, portanto não substituem a mídia publicitária principal.

## Widgets

`DynamicContentController` cria três Views independentes:

- relógio local em `HH:mm`;
- data local em português/locale do dispositivo;
- clima desacoplado do transporte.

O relógio agenda a próxima atualização exatamente no limite do minuto. Não recria a árvore visual, não executa loop por segundo e remove callbacks no `onDestroy`.

O build debug usa clima mock local para validação visual no LDPlayer. O build release não contém uma API disfarçada: sem fonte configurada, usa somente cache válido ou esconde discretamente o widget.

## Clima offline

O repositório tenta a fonte apenas quando informado que há conexão. Um resultado válido é salvo em `loopin_weather_cache`. Sem rede ou em falha, o último dado é classificado como `AVAILABLE` ou `STALE` conforme idade. Se nunca houve dado, retorna `UNAVAILABLE` e o widget fica oculto; o playback nunca espera clima.

Estados: `LOADING`, `AVAILABLE`, `STALE`, `UNAVAILABLE`.

## Layouts, temas e transições

O layout padrão combina mídia em tela inteira, relógio/data no canto superior direito e clima no canto inferior esquerdo. Temas preparados: `LOOPIN_DEFAULT`, `LOOPIN_EVENT`, `LOOPIN_SPORTS` e `LOOPIN_HOLIDAY`.

Transições preparadas: `FADE`, `CROSSFADE` e `SLIDE`. O overlay usa um único fade de 250 ms ao iniciar. Não há animação contínua, partículas ou vídeo de fundo.

## Fallback e offline

A perda de rede não cobre nem interrompe o conteúdo. O estado OFFLINE continua restrito ao diagnóstico. Se o playback não tiver item reproduzível, a tela local exibe “Conteúdo temporariamente indisponível” e o código amigável em vez de permanecer preta.

## Memória e performance

- nenhuma biblioteca nova;
- sem WebView, serviço ou thread permanente;
- somente mídia atual e no máximo um item futuro previstos pela política;
- imagens limitadas conceitualmente a 1920 px e 16 MiB decodificados;
- `sampleSize` reduz bitmaps acima do limite;
- widgets são TextViews reutilizadas;
- callbacks e animações são cancelados com o lifecycle;
- a playlist continua baseada em referências locais, sem decodificar todos os itens em RAM.

## Logs

Eventos definidos: `CONTENT_SELECTED`, `CONTENT_STARTED`, `CONTENT_FINISHED`, `WIDGET_STARTED`, `WIDGET_FAILED`, `WEATHER_UPDATED`, `WEATHER_STALE`, `WEATHER_UNAVAILABLE`, `LAYOUT_CHANGED`, `TRANSITION_STARTED` e `CONTENT_ERROR`.

Somente mudanças de conteúdo/estado são registradas. Não existe log por segundo.

## Limitações

- não há API real de clima ou configuração de cidade remota;
- layouts e eventos são contratos locais, sem payload do Admin;
- `CROSSFADE` e `SLIDE` estão modelados, mas apenas o fade curto é renderizado;
- política de bitmap está disponível para futuros renderers de imagem; o renderer Media3 atual continua gerenciando a mídia existente;
- teclas, decodificação e consumo real no hardware: **PENDENTE — VALIDAÇÃO EM MXQ**.

## Validação executada

Em 19/08/2026:

- 111 testes passaram, incluindo os 18 cenários novos;
- Android Lint passou após remover o uso de `java.time` incompatível com API 21;
- builds debug e release concluíram;
- APK debug `2.0.0-phase6` instalado no LDPlayer;
- reprodução alternou `video-a`, `image-a` e `video-b`, com eventos de início/fim;
- relógio, data localizada e clima mock foram confirmados na hierarquia e em captura real;
- processo manteve o mesmo PID durante 20 segundos offline;
- retorno da rede, force-stop e nova inicialização foram concluídos;
- cache de clima e widgets reapareceram após restart;
- PSS observado após restart ficou em aproximadamente 46 MiB;
- nenhum crash, ANR ou `OutOfMemoryError` foi encontrado.
