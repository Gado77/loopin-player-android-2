# Fase 7.1 — Composição visual por orientação

## Resultado

A camada de apresentação do Loopin Player 2.0 agora usa `PORTRAIT` como orientação padrão. Em uma superfície horizontal de 1600 × 900, o conteúdo é renderizado em um canvas central de 506 × 900, proporção 9:16, sem alterar playlist, cache, sincronização, identidade, operações ou lifecycle.

O aplicativo não gira a Activity inteira e não possui players separados por orientação. O `ContentCanvasLayout` limita somente a superfície visual; `PlaybackSurface` e o renderer dinâmico compartilham esse canvas.

## Arquitetura

`ContentPresentation` concentra a política de composição:

- `ContentOrientation`: `PORTRAIT`, `LANDSCAPE` e `AUTO`;
- padrão: `PORTRAIT`;
- proporção portrait: 9:16;
- proporção landscape preparada: 16:9;
- escala atual: `CENTER_CROP` uniforme;
- margens do relógio e largura do card climático derivadas do canvas.

`LANDSCAPE` e `AUTO` são fundações de modelo, não uma experiência completa. Não foram implementadas variantes remotas de mídia nem mudanças no manifesto remoto.

## Mídia e composição dinâmica

- Vídeo: Media3 `RESIZE_MODE_ZOOM`, preservando proporção e permitindo crop central controlado.
- Imagem: `ImageView.ScaleType.CENTER_CROP`, sem deformação.
- WEATHER: ocupa o mesmo canvas 9:16 da mídia normal e continua sendo item da playlist.
- Relógio/data: canto superior direito com margens relativas ao canvas; data sem dia da semana.
- Nenhuma dependência foi adicionada; não há WebView, blur, shader, polling ou processamento visual pesado.

## Testes automatizados

Foram adicionados 11 testes específicos para:

1. orientação padrão portrait;
2. continuidade da playlist;
3. proporção uniforme de vídeo;
4. proporção uniforme de imagem;
5. composição portrait do WEATHER;
6. margens relativas do relógio;
7. ordem da playlist após mudança de orientação;
8. independência do cache;
9. independência da sincronização;
10. independência da identidade;
11. fallback dinâmico/offline válido.

Matriz completa executada em 19/08/2026:

- 154 testes;
- 0 falhas;
- 0 erros;
- 0 ignorados;
- `lint`: aprovado;
- `assembleDebug`: aprovado;
- `assembleRelease`: aprovado.

Artefatos:

- debug: `app/build/outputs/apk/debug/app-debug.apk` — 5.322.699 bytes;
- release não assinado: `app/build/outputs/apk/release/app-release-unsigned.apk` — 1.075.097 bytes.

## Validação no LDPlayer

APK `2.0.0-phase7.1` instalado e iniciado em `emulator-5554`:

- display do emulador: 1600 × 900;
- canvas medido pela hierarquia: `[547,0][1053,900]`, ou 506 × 900;
- anúncios continuaram reproduzindo no canvas;
- WEATHER apareceu na posição programada da playlist;
- relógio e data permaneceram visíveis no canto superior direito do canvas;
- data observada sem dia da semana;
- retorno aos itens normais preservado;
- PSS durante WEATHER: 50.403 KiB, aproximadamente 49,2 MiB;
- Java Heap: 11.192 KiB;
- Native Heap: 9.528 KiB;
- WebViews: 0;
- nenhum marcador de crash, ANR ou OOM no logcat coletado.

Evidências:

- `diagnostics/ldplayer/phase7_1-normal.png`;
- `diagnostics/ldplayer/phase7_1-weather.png`;
- `diagnostics/ldplayer/phase7_1-ui.xml`;
- `diagnostics/ldplayer/phase7_1-weather-ui.xml`;
- `diagnostics/ldplayer/phase7_1-weather-meminfo.txt`;
- `diagnostics/ldplayer/phase7_1-final-logcat.txt`.

## Arquivos de implementação

Criados:

- `app/src/main/kotlin/com/loopin/player2/ContentCanvasLayout.kt`;
- `core/content/src/test/kotlin/com/loopin/player2/core/content/OrientationPresentationTest.kt`;
- este documento.

Alterados:

- `app/build.gradle.kts`;
- `app/src/main/kotlin/com/loopin/player2/MainActivity.kt`;
- `app/src/main/kotlin/com/loopin/player2/DynamicContentController.kt`;
- `core/content/src/main/kotlin/com/loopin/player2/core/content/ContentEngine.kt`;
- `core/playback/src/main/kotlin/com/loopin/player2/core/playback/PlaybackSurface.kt`;
- `agent.md`.

## Limitações e pendências

- A experiência landscape completa não foi implementada.
- `AUTO` não seleciona variantes de mídia; permanece reservado para política futura.
- Não existem variantes portrait/landscape no manifesto ou backend.
- A política atual usa crop central, sem seleção manual do ponto focal.
- O release continua não assinado, conforme configuração atual do projeto.
- Compatibilidade e desempenho finais em MXQ física continuam pendentes; os resultados acima comprovam somente JVM/build/lint e LDPlayer.
