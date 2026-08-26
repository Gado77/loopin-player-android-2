# Fase 4 — Sincronização remota abstrata

## Arquitetura

```text
RemoteManifestSource
        ↓
SyncManager ── RemoteMediaSourceFactory
        ↓                 ↓
TransactionalPlaylistStore.prepare
        ↓
validação + objetos imutáveis
        ↓
commit ACTIVE/PREVIOUS
        ↓
PlaylistRepository → Playback Engine
```

`RemoteManifestSource` e `LocalManifestSource` são contratos separados. O playback não conhece HTTP e `TransactionalPlaylistStore` recebe apenas `MediaSource`, sem conhecer Supabase, CDN ou API específica.

O endpoint remoto não vem configurado no APK. `RemoteSyncConfigStore` mantém URL e intervalo para uma configuração futura; somente HTTPS habilita o job. Sem endpoint, nenhum job ou conexão é criado e o Player continua exclusivamente offline.

## HTTP

A implementação usa `HttpURLConnection`, sem framework adicional:

- conexão: 10 segundos;
- leitura de manifesto: 20 segundos;
- leitura de mídia: 30 segundos;
- manifesto limitado a 1 MiB;
- redirects habilitados;
- códigos 408, 429 e 5xx são retryable;
- streams são processados com buffer de 8 KiB;
- cancelamento cooperativo interrompe o stream e chama `disconnect()`;
- nenhum arquivo completo é carregado em RAM.

O source envia a versão local no header `X-Loopin-Playlist-Version` e entende HTTP 304. Esse header é neutro e pode ser ignorado por qualquer backend.

## Estados e eventos

Estados do `SyncManager`:

`IDLE → CHECKING → UP_TO_DATE | UPDATE_AVAILABLE → PREPARING → DOWNLOADING → VALIDATING → COMMITTING → SUCCESS`

Falhas terminam em `FAILED` ou `OFFLINE`. Um `AtomicBoolean` rejeita execução simultânea com `AlreadyRunning`.

Eventos de baixa frequência enviados ao logger central:

- `SYNC_STARTED`
- `SYNC_CHECKED`
- `SYNC_UP_TO_DATE`
- `SYNC_UPDATE_AVAILABLE`
- `SYNC_DOWNLOAD_STARTED`
- `SYNC_DOWNLOAD_COMPLETED`
- `SYNC_VALIDATION_FAILED`
- `SYNC_COMMIT_STARTED`
- `SYNC_COMMIT_SUCCESS`
- `SYNC_FAILED`
- `SYNC_OFFLINE`

Não há log por bloco ou polling.

## Retry e agendamento

O aplicativo usa `JobScheduler` nativo, disponível desde API 21. Não foi adicionado WorkManager, banco auxiliar ou serviço permanente.

Cada execução agenda um job único seguinte:

| Falhas consecutivas | Próxima tentativa |
|---:|---:|
| 1 | 30 segundos |
| 2 | 2 minutos |
| 3 | 10 minutos |
| 4 ou mais | próxima janela regular |

O intervalo regular padrão é 6 horas e não pode ser configurado abaixo de 15 minutos. O job exige conectividade e, no API 26+, armazenamento não baixo. É persistido através de reboot. Para TV Box normalmente alimentada continuamente, não foi imposta restrição de bateria/carregamento.

O JobService cria uma thread nomeada somente durante uma tentativa e a encerra ao concluir. `onStopJob` interrompe o worker. Não existe `while(true)` nem polling frequente.

## Offline-first

O agendamento e o sync não fazem parte do caminho crítico de inicialização. Identidade, configuração, ponteiro ACTIVE, objetos e playback continuam locais.

Se manifesto ou mídia falhar:

1. o candidato fica rejeitado/incompleto;
2. ACTIVE/PREVIOUS não mudam;
3. o playback já aberto continua usando objetos imutáveis;
4. o scheduler aplica backoff;
5. uma tentativa futura pode reutilizar objetos completos já validados.

A política atual do playback aplica a nova ACTIVE na próxima carga do engine — por exemplo, próximo lifecycle/restart. A mídia em execução não é interrompida no meio.

## Testes

Os testes automatizados cobrem servidor HTTP disponível, servidor offline, manifesto igual/novo, interrupção, checksum inválido, commit válido, candidato parcial, backoff, restart, playback offline e sincronizações simultâneas.

No LDPlayer, `scripts/adb/test-phase4-offline.ps1` automatiza início, remoção/restauração da rede, verificação do PID, progressão do playback, imutabilidade do ponteiro ACTIVE e busca de crash/ANR/OOM.

Validação executada em 19/08/2026 no LDPlayer com o APK debug `2.0.0-phase4`:

- PID permaneceu `12835` durante a janela offline;
- playback avançou do loop 20 para o loop 24;
- ponteiro ACTIVE permaneceu inalterado;
- nenhum crash, ANR ou `OutOfMemoryError` foi encontrado;
- a conectividade do emulador foi restaurada pelo bloco `finally`;
- 75 testes JVM existentes passaram, sem falhas, erros ou testes ignorados;
- lint, APK debug e APK release foram gerados com sucesso.

Como não existe endpoint HTTPS configurado nesta fase, nenhum job de rede é registrado por padrão no LDPlayer. Isso evita tráfego inútil e mantém a inicialização completamente local; o agendamento passa a existir quando uma configuração remota válida for fornecida em fase futura.

## Limitações

- Nenhum endpoint real está configurado e nenhum backend específico foi implementado.
- O teste HTTP usa servidor local controlado nos testes JVM; o ensaio LDPlayer valida o comportamento offline e a integração Android.
- A aplicação de ACTIVE nova acontece na próxima carga do engine, não por hot-swap.
- Validação física, filesystem e conectividade da TV Box: **PENDENTE — VALIDAÇÃO EM MXQ**.
