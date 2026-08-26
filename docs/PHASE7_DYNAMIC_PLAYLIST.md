# Fase 7 — Mídia dinâmica na playlist

## Correção arquitetural

O clima deixou de ser um widget permanente. `PlaylistItem` agora contém um `PlaylistContent`, que pode ser:

- `NormalMediaContent`: vídeo ou imagem local já validada;
- `DynamicMediaContent`: conteúdo com tipo, duração e configuração; nesta fase somente `WEATHER`.

O construtor antigo de `PlaylistItem` com `PlayableMedia` foi preservado, portanto cache, manifesto, sync e testes anteriores continuam compatíveis. A mídia dinâmica não é colocada no cache binário como se fosse um arquivo.

## Um único motor e uma única ordem

Não existe playlist paralela. `LoopingPlaybackEngine` continua filtrando, ordenando, avançando, repetindo, tratando retry e calculando loops sobre uma única lista imutável.

`ScheduledItemPlayer` apenas resolve o renderer do item atual:

- mídia normal → `Media3ItemPlayer`;
- WEATHER → `WeatherItemPlayer`.

Ao concluir a duração do clima, o mesmo callback `onCompleted` devolve o controle ao motor, que avança exatamente um índice. Não existe regra “a cada cinco anúncios”. A programação mock desta fase declara explicitamente:

`video-a → weather-a → image-a → video-b → weather-b`

O futuro manifesto do Admin poderá fornecer diretamente posição, frequência, cidade, duração e tipo.

## Renderer WEATHER

O renderer:

1. lê cidade/configuração do item e estado climático local disponível;
2. determina dia/noite com o relógio do dispositivo;
3. classifica condição via `WeatherBackgroundSelector`;
4. seleciona um URI de vídeo previamente preparado;
5. reproduz o fundo em loop somente durante a duração do item;
6. mostra camada escura e card central;
7. encerra player, callbacks e Views antes de avançar.

Mapeamentos preparados: `CLEAR_DAY`, `CLEAR_NIGHT`, `CLOUDY_DAY`, `CLOUDY_NIGHT`, `RAIN_DAY`, `RAIN_NIGHT`, `STORM` e `FALLBACK`.

Há apenas um vídeo local de demonstração nesta fase, reutilizado como recurso preparado. Não existe blur, shader, download ou processamento do vídeo. Novos arquivos poderão ser associados ao seletor sem alterar o motor.

O card apresenta cidade, temperatura, condição, máxima/mínima e previsão curta. Se não houver dado válido, apresenta estado local discreto e conclui normalmente. Com rede indisponível, `WeatherRepository` usa o último cache válido.

## Relógio

O relógio permanece no canto superior direito durante mídia normal e dinâmica. Usa `sans-serif-light`/Roboto nativa, formato `HH:mm` e agenda somente o próximo limite de minuto.

A data agora contém apenas `18 DE AGOSTO`; o dia da semana foi removido. Não há caixa, fonte externa, atualização por segundo ou reconstrução da interface.

## Lifecycle e performance

- cada mídia mantém somente seu renderer ativo;
- ExoPlayer do background existe apenas durante WEATHER;
- callbacks de duração respeitam pause/resume;
- conclusão, stop e release removem callbacks, liberam player e ocultam o card;
- falha do vídeo de fundo usa apresentação estática e não bloqueia a playlist;
- nenhuma biblioteca ou serviço foi adicionado;
- sem WebView, polling, blur ou animação contínua.

## Testes

Foram adicionados testes explícitos para:

- normal → normal, normal → WEATHER e WEATHER → normal;
- múltiplos WEATHER, primeiro, último, nenhum e somente WEATHER;
- seleção diurna/noturna;
- cache offline e ausência de clima;
- retorno ao próximo índice;
- imutabilidade da playlist;
- restart;
- execução sem dependência de internet.

## Validação no LDPlayer

Executada em 19/08/2026 com APK debug `2.0.0-phase7`:

- 143 execuções de teste passaram, sem falhas, erros ou testes ignorados;
- Android Lint, `assembleDebug` e `assembleRelease` passaram;
- anúncio normal não contém cidade, temperatura ou card climático;
- WEATHER aparece somente nos índices `weather-a` e `weather-b`;
- card central, vídeo de fundo, cidade, temperatura, condição, máxima/mínima e previsão foram exibidos;
- relógio ficou no canto superior direito;
- data apareceu sem dia da semana;
- após WEATHER, o log confirmou avanço para `image-a`;
- o processo preservou o PID `21350` durante 20 segundos sem rede;
- force-stop e restart produziram novo processo saudável (`22298`);
- PSS durante WEATHER observado em aproximadamente 52 MiB;
- nenhum crash, ANR ou OOM.

O comando de rede do LDPlayer interrompe o canal ADB durante a janela. Nesta execução específica, o callback `DEVICE_OFFLINE` não apareceu no trecho final, embora o processo e a programação tenham continuado; `DEVICE_ONLINE` foi registrado no retorno. O fallback offline permanece coberto por testes determinísticos e pelo cache validado nas fases anteriores.

## Limitações

- clima e previsão são mock no debug; não há API real;
- cidade ainda é configuração local do item;
- somente WEATHER é dinâmico;
- apenas um vídeo demonstrativo está disponível para todos os backgrounds;
- manifesto remoto ainda não serializa `DynamicMediaContent`;
- consumo, codecs e vídeos finais: **PENDENTE — VALIDAÇÃO EM MXQ**.
