# Fase 7.6 — Foco na temperatura atual

## Resultado

O WEATHER foi simplificado para manter o foco absoluto na temperatura atual.

- cidade reduzida discretamente;
- valor numérico e símbolo de grau passaram a ser elementos independentes;
- o número fica centralizado matematicamente no painel;
- o símbolo `°` é posicionado à direita e acima, sem alterar o centro do número;
- removida a previsão dos próximos dias da composição visual;
- relógio e data da Fase 7.5 foram preservados;
- máxima, mínima e sensação continuam sem cápsula ou fundo próprio.

Nenhum contrato de clima foi removido. Os dados de previsão podem continuar existindo no modelo/cache; somente deixaram de ser renderizados nesta composição.

## Escopo preservado

- item `DynamicMediaContent.WEATHER`;
- duração e avanço da playlist;
- vídeo climático de fundo;
- lifecycle do `WeatherItemPlayer`;
- persistência e cache de clima;
- mídias comuns e demais funcionalidades.

## Validação

- 166 execuções de testes sem falhas;
- lint sem erros e com os 2 avisos preexistentes de strings de debug;
- builds debug e release aprovados;
- APK debug instalado no LDPlayer;
- composição 9:16 inspecionada visualmente;
- nenhum crash, ANR ou OOM observado.

Captura: `diagnostics/ldplayer/phase7_6-sample-1.png`.

PENDENTE — VALIDAÇÃO EM MXQ e TV física de 29–32 polegadas.
