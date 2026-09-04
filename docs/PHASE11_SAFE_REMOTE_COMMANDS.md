# Fase 11 — Comandos remotos seguros e auditáveis

## Resultado

A Fase 11 prova a cadeia `Admin 2 → RPC autorizada → fila persistente → Player autenticado → execução whitelistada e idempotente → confirmação`. Ela implementa exclusivamente `GET_STATUS`, `SYNC_NOW` e `RELOAD_PLAYLIST`. O subsistema é secundário: falhas, ausência de rede ou indisponibilidade do backend não bloqueiam boot, playback nem o ACTIVE local.

## Arquitetura

- `device_commands`: fila e trilha de auditoria persistente, vinculada a owner, screen e device.
- `enqueue_player_command`: única entrada administrativa; deriva owner/device no servidor e aceita somente tela própria, Player pareado, tipo permitido e payload vazio.
- `claim_player_commands`: claim transacional com `FOR UPDATE SKIP LOCKED`, lote máximo de cinco e lease de reentrega de 60 segundos.
- `complete_player_command`: conclusão idempotente, restrita ao device correto e com resultado pequeno.
- `player-commands`: Edge Function dedicada, autenticada pela credencial permanente já existente.
- `DeviceCommandHttpApi`, dispatcher, executor, scheduler e `JobService`: transporte leve por `HttpURLConnection`, sem trabalho na main thread e sem serviço permanente.
- Admin 2: três ações, confirmação para as ações mutantes, últimos cinco comandos e refresh temporário enquanto houver trabalho pendente.

## Fila, estados e expiração

Os estados são `PENDING`, `DELIVERED`, `SUCCEEDED`, `FAILED` e `EXPIRED`. Todos os comandos possuem TTL de 15 minutos. A RPC de claim expira comandos vencidos antes de selecionar trabalho. O Player também confere `expires_at` antes de executar. Um claim fica elegível para reentrega após 60 segundos caso a confirmação não chegue.

O backend registra criação, solicitante, tela, device, entrega/tentativas, conclusão e resultado. Credenciais e Authorization nunca são persistidos na fila.

## Autenticação, autorização e RLS

O Player envia `Authorization: Bearer <credencial permanente>` somente por HTTPS. A Edge Function calcula SHA-256, resolve uma credencial ativa e exige device `PAIRED`; o body não fornece identidade autoritativa. A credencial, seu hash e tokens não aparecem em logs ou respostas administrativas.

RLS permite ao usuário autenticado apenas ler comandos de que é proprietário. Não há INSERT/UPDATE/DELETE administrativo direto: criação passa pela RPC. O Player acessa a fila somente pela Edge Function. Testes reais confirmaram isolamento entre tenants e devices.

## Contrato do Player

Fetch:

```json
{"action":"fetch"}
```

Conclusão:

```json
{"action":"complete","command_id":"uuid","status":"SUCCEEDED","result":{"code":"scheduled"}}
```

O fetch retorna no máximo cinco itens. O body é limitado a 16 KiB, a resposta Android a 64 KiB e o resultado a 4 KiB. `GET_STATUS` aceita exatamente as oito chaves previstas. As demais ações aceitam apenas `code` limitado. Payload inesperado é rejeitado.

## Execução whitelistada

- `GET_STATUS`: coleta `app_version`, connection, playback/cache/sync/health state, armazenamento livre e último sync a partir do `DeviceHealthManager` existente.
- `SYNC_NOW`: antecipa o scheduler existente e responde `scheduled` ou `already_running`; não espera downloads nem cria outro `SyncManager`.
- `RELOAD_PLAYLIST`: reaplica o ACTIVE pelo mecanismo transacional existente no próximo ponto seguro; não baixa mídia, não limpa cache, não reinicia Activity e retorna `no_active_playlist` quando não existe ACTIVE.

`RESTART_PLAYER`, `CLEAR_CACHE`, `CHECK_UPDATE`, `REBOOT_DEVICE`, `CAPTURE_SCREENSHOT`, tipos desconhecidos e qualquer execução arbitrária permanecem `unsupported` e não são exibidos no Admin.

## Idempotência, concorrência e replay

O Player grava em `SharedPreferences` os últimos 100 IDs, status e resultados antes de confirmar o backend. Reentrega do mesmo ID apenas reenvia o resultado conhecido. O conjunto é limitado e sobrevive a recriação do runtime e reboot. Um `AtomicBoolean` impede duas execuções locais simultâneas. No servidor, locking transacional impede dois fetches de claimarem o mesmo comando.

## Scheduler, lifecycle e offline

O `JobScheduler` usa job one-shot persistente, exige rede e volta ao intervalo normal de aproximadamente 60 segundos após sucesso. Timeout/429/5xx usam 1, 5, 15 e 30 minutos; 401 usa uma hora e não apaga pairing nem gera credencial. Startup de Player pareado, conclusão do pairing e retorno da rede antecipam uma consulta. Não existe thread ociosa permanente, foreground service, wake lock, WebSocket ou polling de segundos. Resposta vazia não gera log periódico.

Offline, o job aguarda rede e o ACTIVE continua. No retorno da conexão, comandos ainda válidos podem ser consultados sem reiniciar playback.

## Admin 2

Uma tela pareada mostra `Atualizar status`, `Sincronizar agora` e `Recarregar playlist`. Telas sem Player não mostram ações. SYNC/RELOAD exigem confirmação simples. O histórico apresenta os últimos cinco comandos, estado, horário e resultado limitado. Um único refresh de 12 segundos existe apenas enquanto houver `PENDING`/`DELIVERED`; ele é cancelado ao terminar, trocar de tela ou sair. Supabase Realtime não foi adicionado.

## Validação automatizada

- Android/JVM: executor, whitelist, status limitado, sync em andamento, ACTIVE ausente, expiração, lotes, unpaired, credencial ausente, 200 vazio/com comandos, 401, 5xx, completion failure/401, backoff, concorrência, store limitado e replay após recriação.
- Admin/Vitest: RPC e novos campos, três tipos, apresentações pending/succeeded/failed/expired, histórico e refresh único/cancelável.
- Suíte integral: registrada no fechamento da fase com `gradlew test lintDebug assembleDebug assembleRelease`, `npm test`, `npm run build` e `npm audit`.

## Supabase real

No projeto isolado `zdhsfirabkmivuzwyids`, foram validados: enqueue próprio; bloqueio cross-tenant; rejeição de tipo/payload; entrega somente ao device correto; conclusão alheia negada; GET_STATUS válido; complete repetido idempotente; histórico isolado; claim concorrente único; expiração; e request sem Authorization com HTTP 401. Dados, função e segredo laboratoriais foram removidos. A migration e `player-commands` definitivos permaneceram implantados.

## LDPlayer E2E

Um APK `2.0.0-phase11-remote-commands` foi instalado e pareado no LDPlayer. Playlist real VIDEO/WEATHER recebeu nova versão. `GET_STATUS`, `SYNC_NOW` e `RELOAD_PLAYLIST` chegaram e terminaram em `SUCCEEDED`; GET_STATUS devolveu os campos operacionais previstos, sync antecipou a atualização e reload manteve a Activity/playback. A validação encontrou timestamps Supabase com microssegundos e o parser Android foi corrigido para aceitá-los em API 21.

Reentrega de `RELOAD_PLAYLIST` manteve uma única execução. Nova reentrega após reboot também manteve uma execução, comprovando persistência. O pairing sobreviveu ao reboot. Durante indisponibilidade de rede houve `DEVICE_OFFLINE`, o log continuou registrando ciclos de conteúdo e o retorno produziu `DEVICE_ONLINE`. Não foram encontrados crash, ANR ou OOM relacionados. LDPlayer é integração; certificação e soak test na MXQ continuam pendentes.

## Custo periódico

Um job de rede curto por minuto quando pareado e online, lote máximo cinco, JSON pequeno e nenhum worker residente. Logs só registram processamento/falha relevante e permanecem limitados pelo logger rotativo existente.

## Arquivos da fase

- `supabase/migrations/20260904120000_phase11_safe_remote_commands.sql`
- `supabase/functions/player-commands/index.ts`
- `supabase/config.toml`
- `core/operations/.../RemoteCommands.kt` e testes
- `core/operations/.../DeviceOperations.kt` e teste ajustado
- `core/sync/.../SyncManager.kt`
- `app/.../DeviceCommandRuntime.kt`, `LoopinApplication.kt`, `MainActivity.kt`, manifest e build config
- `admin2/src/api.ts`, `types.ts`, `commands.ts`, `command-refresh.ts`, `main.ts`, `styles.css` e testes
- este documento e `agent.md`

## Limitações e itens adiados

Permanecem fora do escopo comandos destrutivos, shell/intents arbitrários, OTA, screenshots, logs remotos completos, Realtime, WebSocket/MQTT/Firebase, campanhas, redesign, Player/Admin antigos, Supabase de produção e firmware. A MXQ física ainda exige validação.
