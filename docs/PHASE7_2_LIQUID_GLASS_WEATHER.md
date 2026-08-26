# Fase 7.2 — WEATHER Liquid Glass

## Resultado

A tela WEATHER foi reconstruída como uma composição premium de digital signage dentro do canvas portrait 9:16. Ela continua sendo `DynamicMediaContent / WEATHER`, aparece somente na posição definida pela playlist e libera o player de fundo ao terminar.

A referência do Figma informada não pôde ser aberta no ambiente de execução. A descrição textual anexada foi utilizada como especificação oficial e a composição foi ajustada por capturas reais no LDPlayer.

## Composição

- vídeo climático local ocupa o canvas atrás da interface;
- relógio/data permanecem no canto superior direito em todas as mídias;
- card principal separado, com cidade, temperatura, condição, máxima/mínima e sensação;
- segundo painel independente para quatro dias de previsão;
- células internas leves e translúcidas;
- dimensões principais proporcionais ao canvas 9:16;
- fontes nativas `sans-serif`, `sans-serif-light` e `sans-serif-medium`;
- fade único de 250 ms na entrada.

O efeito de vidro é simulado por `GlassPanelDrawable`, que desenha apenas:

- gradiente translúcido;
- borda branca de baixo alpha;
- highlight curto na borda superior;
- cantos arredondados;
- sombra de texto discreta.

Não existe blur em tempo real, RenderScript, WebView, shader pesado, partícula ou dependência nova.

## Vídeos climáticos

`WeatherBackgroundSelector` continua convertendo a descrição climática em `WeatherBackground`. A nova classe genérica `WeatherBackgroundCatalog<T>` resolve a condição para uma mídia local e sempre oferece fallback determinístico.

Fluxo:

```text
condição + período do dia
        ↓
WeatherBackgroundSelector
        ↓
WeatherBackgroundCatalog
        ↓
URI local ou fallback local
        ↓
WeatherItemPlayer / Media3
```

O APK desta fase contém somente `sample_video.mp4`, usado como placeholder para `CLEAR_DAY` e fallback das demais condições. Os vídeos finais previamente tratados ainda precisam ser adicionados a `res/raw` e registrados no mapa de `MainActivity`. Nenhum vídeo externo ou de licença desconhecida foi incorporado.

O `WeatherItemPlayer` mantém somente um `ExoPlayer` enquanto WEATHER está ativo, usa loop sem áudio adicional e libera esse player em conclusão, erro, troca de item, stop e lifecycle.

## Testes

Foram adicionados seis testes específicos:

1. sol resolve vídeo preparado;
2. chuva resolve outro vídeo;
3. condição não mapeada usa fallback offline;
4. nublado noturno é reconhecido em português;
5. troca de condição troca a resolução sem reter o valor anterior;
6. `FALLBACK` explícito é determinístico.

Matriz completa final:

- 166 execuções;
- 0 falhas;
- 0 erros;
- 0 ignorados;
- lint: 0 erros e 2 avisos preexistentes de strings debug não utilizadas;
- `assembleDebug`: aprovado;
- `assembleRelease`: aprovado.

Artefatos:

- `app/build/outputs/apk/debug/app-debug.apk`: 5.495.002 bytes;
- `app/build/outputs/apk/release/app-release-unsigned.apk`: 1.078.105 bytes.

## LDPlayer

Validação realizada com a versão `2.0.0-phase7.2`:

- instalação do debug: aprovada;
- WEATHER observado dentro da playlist;
- retorno para mídia normal observado;
- nenhum elemento de clima no anúncio normal;
- relógio/data presentes e sem dia da semana;
- force-stop e reinicialização aprovados;
- PSS durante WEATHER: 54.570 KiB, aproximadamente 53,3 MiB;
- Java Heap: 12.532 KiB;
- Native Heap: 8.728 KiB;
- WebViews: 0;
- nenhum marcador de crash, ANR ou OOM.

O fallback offline é coberto por testes automatizados. A alternância de rede do LDPlayer não produziu uma medição conclusiva nesta execução porque o utilitário de controle excedeu a janela do comando; a conectividade foi explicitamente restaurada. O requisito crítico continua atendido arquiteturalmente por vídeo local e cache climático local.

Evidências:

- `diagnostics/ldplayer/phase7_2-weather-final-2.png`;
- `diagnostics/ldplayer/phase7_2-normal-final.png`;
- `diagnostics/ldplayer/phase7_2-weather-final-ui.xml`;
- `diagnostics/ldplayer/phase7_2-weather-final-meminfo.txt`;
- `diagnostics/ldplayer/phase7_2-final-logcat.txt`.

## Arquivos

Criados:

- `app/src/main/kotlin/com/loopin/player2/GlassPanelDrawable.kt`;
- `core/playback/src/test/kotlin/com/loopin/player2/core/playback/WeatherBackgroundCatalogTest.kt`;
- este documento.

Alterados:

- `app/src/main/kotlin/com/loopin/player2/DynamicContentController.kt`;
- `app/src/main/kotlin/com/loopin/player2/MainActivity.kt`;
- `app/src/main/res/values/strings.xml`;
- `app/build.gradle.kts`;
- `core/content/src/main/kotlin/com/loopin/player2/core/content/Weather.kt`;
- `core/playback/src/main/kotlin/com/loopin/player2/core/playback/WeatherItemPlayer.kt`;
- `agent.md`.

## Pendências

- adicionar os vídeos finais de cada condição, previamente tratados e sem áudio;
- validar os codecs e o consumo com esses arquivos reais;
- repetir offline prolongado no LDPlayer;
- validar 720 × 1280 e 1080 × 1920 físicos;
- validar memória, decoder, lifecycle e operação prolongada na MXQ real.
