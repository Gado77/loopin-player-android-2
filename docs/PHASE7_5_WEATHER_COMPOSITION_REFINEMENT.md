# Fase 7.5 — Refinamento da composição WEATHER

## Resultado

A tela WEATHER passou a usar uma única peça de vidro para o clima atual e a previsão dos próximos dias. A previsão continua pertencendo ao mesmo item dinâmico da playlist e não ganhou renderer, ciclo de vida ou duração próprios.

## Alterações visuais

- removido o fundo em cápsula de máxima, mínima e sensação térmica;
- `PRÓXIMOS DIAS` e suas quatro células foram incorporados ao painel principal;
- removido o segundo painel de vidro da previsão;
- condição climática aproximada discretamente da temperatura;
- título e cidade deslocados levemente para baixo;
- relógio reduzido de 30 sp para 18 sp e alterado para `sans-serif-medium`;
- data reduzida de 13 sp para 9 sp;
- relógio reposicionado com margens pequenas no canto superior direito, sem vidro.

## Escopo preservado

- ordem e duração da playlist;
- `DynamicMediaContent.WEATHER` e `WeatherItemPlayer`;
- vídeo climático de fundo;
- lifecycle e liberação do player de vídeo;
- cache e contratos de clima;
- demais tipos de mídia.

## Validação

- testes: 166 execuções, sem falhas;
- lint: 0 erros e 2 avisos preexistentes de strings de debug não utilizadas;
- `assembleDebug`: aprovado;
- `assembleRelease`: aprovado;
- APK debug instalado com sucesso no LDPlayer;
- composição portrait 9:16 inspecionada no LDPlayer;
- WEATHER apareceu como item temporário e a playlist continuou alternando normalmente;
- PSS observado: 70.383 KiB, aproximadamente 68,7 MiB;
- não foram observados crash, ANR ou OOM nesta validação.

Captura de referência: `diagnostics/ldplayer/phase7_5-sample-4.png`.

## Limitação

PENDENTE — VALIDAÇÃO EM MXQ e em uma TV física de 29–32 polegadas para confirmar a escala percebida à distância.
