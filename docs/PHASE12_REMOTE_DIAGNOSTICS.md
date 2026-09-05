# Fase 12 — Telemetria operacional e diagnóstico remoto leve

## Objetivo e resultado

A Fase 12 transforma o `DeviceHealthSnapshot` já existente em diagnóstico persistente: o heartbeat autenticado atual envia um snapshot enriquecido, o Supabase mantém o estado corrente e somente transições importantes, e o Admin 2 mostra saúde, execução e causa recente sem acesso físico à TV. Não foi criado scheduler adicional nem transporte de logs.

## Snapshot único

O mesmo `DeviceHealthSnapshot` alimenta heartbeat periódico e `GET_STATUS`. Além dos campos anteriores, ele inclui `session_id`, sinal de baixa memória, ACTIVE/PREVIOUS, ETag do manifesto, item/conteúdo atual e erro estruturado. Não envia `internalId`, friendly code, credencial, hash, URL, arquivo ou stack trace.

Campos remotos: versão, session ID, uptime, memória disponível/low-memory, armazenamento livre/total, connection, playback/cache/sync/health, último sync, ACTIVE id/versão/manifest ETag, PREVIOUS id, item/kind/media atual e último erro code/summary/time. A coleta usa apenas `MemoryInfo`, `StatFs`, ponteiro transacional pequeno e estado em memória; não percorre cache, não abre mídia e não calcula hash.

`session_id` é UUID aleatório por criação do processo. Ele não substitui device ID, identidade interna ou credencial e permite observar nova sessão sem inventar se a causa foi reboot, force-stop ou morte pelo sistema.

## Heartbeat e segurança

O `device-pairing action=heartbeat` continua sendo a fronteira HTTPS autenticada pela credencial permanente. Frequência normal permanece em aproximadamente cinco minutos via `JobScheduler` one-shot, somente PAIRED e com rede, usando o backoff anterior. Nenhuma thread fica residente.

O novo body contém `action`, `app_version`, `session_id`, `runtime` e `last_error`. A Edge Function aceita somente tipos, enums, comprimentos e inteiros conhecidos; campos desconhecidos são descartados. Request permanece limitado a 16 KiB. Erro tem código de até 64 caracteres e resumo sanitizado de até 256. Tokens, Bearer, query secrets, quebras de linha e excesso são removidos/rejeitados. Clientes antigos com `metadata` continuam aceitos durante rollout.

## Persistência SQL

`device_runtime_status` mantém uma linha por device por UPSERT, com owner/screen derivados do device pareado. `device_health_events` registra apenas transições relevantes. RLS permite SELECT somente ao proprietário; anon não lê e o browser não escreve. O device nunca acessa tabelas diretamente: a Edge Function chama `record_device_runtime_status` como service role.

Índices cobrem status por owner/screen e eventos por device ou owner/screen + tempo. A escrita remove oportunisticamente eventos acima de 30 dias e conserva no máximo os 100 mais recentes por device. O Admin carrega no máximo 50 eventos recentes.

## Transições

O backend compara o snapshot anterior, evitando duplicações. Eventos possíveis: `PLAYER_SESSION_STARTED`, health degraded/recovered, playback error/recovered, sync failed/recovered, cache error/recovered, low storage/recovered e low memory/recovered. Severidades são INFO, WARNING e ERROR. Um heartbeat HEALTHY repetido apenas atualiza status; não cria histórico.

Storage é baixo quando livre abaixo de 500 MiB ou abaixo de 10% do total. Memória baixa usa `ActivityManager.MemoryInfo.lowMemory`. Não existe limpeza automática.

Offline continua derivado por `last_seen_at` com tolerância de aproximadamente 12 minutos; um aparelho desconectado não precisa conseguir enviar um evento OFFLINE.

## Erros

Novos caminhos usam códigos estáveis iniciais: `PLAYBACK_MEDIA_ERROR`, `SYNC_NETWORK_ERROR`, `SYNC_MANIFEST_INVALID`, `SYNC_DOWNLOAD_FAILED` e `SYNC_CHECKSUM_FAILED`. O texto local continua útil, mas remotamente é limitado e sanitizado. Sucesso de sync limpa o erro persistido. O logger rotativo local permanece exclusivamente local.

## Admin 2

Cards de Telas preservam vínculo, presença, versão, playlist e última comunicação, acrescentando `SAUDÁVEL`, `ATENÇÃO` ou `ERRO`, playback, sync e armazenamento livre. O botão Diagnóstico abre painel responsivo com sessão, uptime, memória, storage, playback, cache, sync, ACTIVE, item atual, último sync/comunicação/erro e versão. Abaixo aparecem até 50 transições recentes. Não há gráficos, Realtime ou tabela horizontal.

## GET_STATUS

`GET_STATUS` usa `DeviceRuntimeSnapshotFactory`, exatamente a mesma projeção segura do health usada pelo heartbeat. O contrato da Edge Function e da RPC de conclusão foi evoluído para aceitar os novos campos sem permitir JSON arbitrário. `SYNC_NOW` e `RELOAD_PLAYLIST` permanecem inalterados.

## Testes e validação real

- Baseline: 214 Android/JVM e 72 Admin aprovados antes da mudança.
- Android: snapshot enriquecido, session ID distinto, payload limitado, identidade omitida, erro sanitizado, ACTIVE/item e GET_STATUS compartilhado, além de toda a regressão de heartbeat/comandos.
- Admin: health HEALTHY/DEGRADED/ERROR/missing, storage baixo, uptime/bytes, histórico isolado/limitado e API de eventos, além de presença e comandos anteriores.
- Supabase isolado: dois tenants; primeiro/segundo heartbeat; HEALTHY repetido sem duplicar; HEALTHY→DEGRADED e recuperação; nova sessão; RLS de status/eventos; campo desconhecido descartado; erro enorme rejeitado; sem Authorization 401; credencial revogada 401. Dados, função e segredo laboratoriais foram removidos.
- LDPlayer: pareamento real; playlist WEATHER v1 sincronizada e ACTIVE; heartbeat mostrou HEALTHY/PLAYING, memória/storage/uptime e versão `2.0.0-phase12-diagnostics`; GET_STATUS coincidiu com o periódico; reboot gerou segunda sessão e preservou pairing/ACTIVE; durante Wi-Fi desligado o log mostrou vários `CONTENT_STARTED`, e a rede/heartbeat retornaram. Nenhum crash, ANR ou OOM relacionado foi encontrado.

A degradação controlada foi feita no laboratório real do Supabase sem corromper cache: sync/health passaram a ERROR/DEGRADED, o Admin autenticado pôde ler motivo e eventos, e o heartbeat seguinte criou as recuperações.

## Performance e lifecycle

O custo novo ocorre dentro do heartbeat existente: consultas Android constantes/baratas, JSON de poucos KB e um UPSERT. Não há segundo job, polling de métricas, profiler, leitura de vídeo, SHA, upload de log, serviço permanente ou wake lock. Playback e startup offline não aguardam telemetria.

## Arquivos principais

- `core/operations/.../DeviceOperations.kt` e `RemoteCommands.kt`
- `app/.../OperationsRuntime.kt`, `DeviceHeartbeatRuntime.kt`, `LoopinApplication.kt`, `MainActivity.kt`
- `supabase/migrations/20260904170000_phase12_remote_diagnostics.sql`
- `supabase/functions/device-pairing/index.ts` e `player-commands/index.ts`
- `admin2/src/diagnostics.ts`, tipos, API, tela, estilos e testes
- este documento e `agent.md`

## Limitações

Não existem upload/download de logs, stack trace remoto, proof-of-play, analytics, gráficos, alertas, push/Realtime, screenshot, restart/reboot/clear-cache, OTA, shell, MDM, campanhas ou alteração de WEATHER. ONLINE/OFFLINE histórico não usa cron; presença visual continua derivada. Certificação e soak test na MXQ física permanecem pendentes.
