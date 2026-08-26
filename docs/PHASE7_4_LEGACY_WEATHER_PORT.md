# Fase 7.4 — Reconstrução do WEATHER a partir do Android antigo

## Fonte analisada

Referência: `Gado77/Loopin.tv`, branch `main`, commit `b7393f4973a78eae446835c7568262b7e8fb34a1`.

A cópia local do frontend não continha `Android/`. A análise foi realizada em um clone temporário somente leitura. O repositório antigo não foi modificado.

Arquivos autoritativos:

- `Android/app/src/main/java/com/loopin/loopintv/MainActivity.kt`, linhas 872–1168;
- `Android/app/src/main/java/com/loopin/loopintv/SupabaseManager.kt`, contratos e montagem de WEATHER;
- `Android/app/src/main/res/layout/player_view.xml`;
- `Android/app/src/main/res/layout/activity_main.xml`;
- `Android/app/src/main/assets/weather/*.mp4`.

## Auditoria visual do legado

O renderer antigo construía a tela programaticamente:

1. `PlayerView` cobrindo toda a tela com `RESIZE_MODE_ZOOM`;
2. vídeo H.264 local em loop e volume zero;
3. overlay preto `#55000000`;
4. card central `WRAP_CONTENT`, margem 60 px e elevação 20;
5. vidro com branco `#20FFFFFF`, borda 1 px `#30FFFFFF` e raio 60 px;
6. conteúdo vertical centralizado com padding 120 × 72 px;
7. título 11 sp, branco 80%, letter spacing 0,25;
8. cidade 30 sp bold e uppercase;
9. temperatura 80 sp bold;
10. descrição 17 sp;
11. cápsula preta `#30000000`, raio 50 px e borda `#20FFFFFF` para umidade/vento.

Não havia blur em runtime, refração real, shader ou componente Material de card. A aparência de vidro dependia principalmente dos vídeos previamente desfocados.

O legado também não implementava previsão de quatro dias, máxima/mínima ou sensação térmica. Esses elementos foram incorporados como requisitos atuais, mantendo a linguagem visual antiga.

## Dados e seleção de mídia antigos

O item continha cidade, duração e ordem. O renderer buscava temperatura, descrição, umidade, vento, weather ID e noite/dia, com cache de 15 minutos.

Mapeamento antigo:

- dia limpo: `ceu_limpo.mp4`;
- dia nublado: `ceu_nublado.mp4`;
- chuva/temporal diurno: `dia_chuva.mp4`;
- noite sem chuva: `noite_normal.mp4`;
- chuva/temporal noturno: `chuva_noite.mp4`.

Os cinco vídeos são H.264 Main, 480 × 600, 30 fps, duração aproximada de 10 segundos e possuem desfoque incorporado.

## Adaptação ao Player 2.0

Foram preservados:

- vídeos, confirmados por SHA-256 idêntico;
- overlay, transparências, bordas e cápsula;
- hierarquia e pesos tipográficos;
- crop central e loop silencioso;
- mapeamento de condições dia/noite.

Foram adaptados:

- dimensões para o canvas portrait 9:16;
- tamanho da cidade para permanecer em uma linha;
- afastamento vertical do relógio;
- máxima/mínima e sensação dentro da cápsula antiga;
- previsão em segundo painel com o mesmo vidro e divisões discretas.

Não foram transportados:

- ExoPlayer criado dentro da Activity;
- Supabase e APIs climáticas;
- polling e cache legado;
- slots A/B e crossfade de 800 ms;
- dependências Glide/Gson/OkHttp do aplicativo antigo.

O Player 2.0 continua usando `DynamicMediaContent.WEATHER`, `WeatherItemPlayer`, `WeatherBackgroundCatalog`, cache offline atual, lifecycle atual e playlist única.

## Assets incorporados

- `weather_ceu_limpo.mp4`: 2.920.384 bytes;
- `weather_ceu_nublado.mp4`: 2.168.849 bytes;
- `weather_chuva_noite.mp4`: 2.873.722 bytes;
- `weather_dia_chuva.mp4`: 2.988.586 bytes;
- `weather_noite_normal.mp4`: 2.516.884 bytes.

## Validação

Versão: `2.0.0-phase7.4`.

- 166 testes;
- zero falhas, erros ou skips;
- lint: zero erros e dois avisos preexistentes de strings debug não utilizadas;
- `assembleDebug`: aprovado;
- `assembleRelease`: aprovado;
- APK debug instalado no LDPlayer;
- vídeo legado noturno reproduzindo atrás do painel confirmado;
- WEATHER → mídia normal confirmado;
- ausência de clima sobre anúncio confirmada;
- force-stop/restart confirmado;
- PSS durante WEATHER: 69.596 KiB, aproximadamente 68,0 MiB;
- Java Heap: 26.792 KiB;
- Native Heap: 9.344 KiB;
- WebViews: 0;
- nenhum crash, ANR ou OOM.

Artefatos:

- debug: `app/build/outputs/apk/debug/app-debug.apk` — 18.725.312 bytes;
- release não assinado: `app/build/outputs/apk/release/app-release-unsigned.apk` — 14.548.069 bytes.

Evidências:

- `diagnostics/ldplayer/phase7_4-weather-final.png`;
- `diagnostics/ldplayer/phase7_4-normal.png`;
- `diagnostics/ldplayer/phase7_4-weather-final-ui.xml`;
- `diagnostics/ldplayer/phase7_4-weather-meminfo.txt`;
- `diagnostics/ldplayer/phase7_4-logcat.txt`.

## Arquivos do Player 2.0

Criados:

- cinco MP4 em `app/src/main/res/raw/`;
- este documento.

Alterados:

- `app/src/main/kotlin/com/loopin/player2/GlassPanelDrawable.kt`;
- `app/src/main/kotlin/com/loopin/player2/DynamicContentController.kt`;
- `app/src/main/kotlin/com/loopin/player2/MainActivity.kt`;
- `app/src/main/res/values/strings.xml`;
- `app/build.gradle.kts`;
- `agent.md`.

## Pendências

- validar decoder, crop e PSS na MXQ física;
- validar os cinco vídeos em execução real, pois a fixture mock atual selecionou o fundo noturno;
- considerar futura recompressão somente após comparação visual e teste de hardware;
- manter a previsão nova compatível quando o backend fornecer dados reais.
