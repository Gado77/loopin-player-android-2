# Fase 8.3 — Device Presence e Heartbeat autenticado

Data: 01/09/2026

## Objetivo e resultado

A fase transforma um Player já pareado em um dispositivo observável sem acoplar presença a boot, UI ou playback. O Player envia health mínimo autenticado, o backend determina a hora autoritativa da última comunicação e o Admin 2 deriva `ONLINE`/`OFFLINE` sem persistir um booleano obsoleto.

O escopo ficou restrito ao projeto isolado Loopin Player 2.0. Player/Admin antigos, WEATHER, playlist, cache, playback e Supabase de produção não foram alterados.

## Arquitetura

### Android

- `LocalHeartbeatSource` e `DeviceHealthManager` existentes continuam sendo a origem do snapshot.
- `DeviceHeartbeatPayloadFactory` transforma o snapshot no contrato remoto limitado.
- `DeviceHeartbeatRequest` cria o request HTTPS e o header Bearer; sua representação textual oculta headers.
- `DeviceHeartbeatDispatcher` separa presença de pairing, exige `PAIRED`, recupera a credencial existente e impede duas execuções simultâneas com `AtomicBoolean`.
- `DeviceHeartbeatHttpApi` usa `HttpURLConnection`, timeouts de 10 segundos e nenhum corpo grande em memória.
- `DeviceHeartbeatScheduler` usa `JobScheduler` persistido, one-shot e condicionado à rede.
- `DeviceHeartbeatJobService` cria uma thread somente durante a requisição, finaliza o job e registra o próximo ciclo no main looper.

Não foram adicionados Retrofit, OkHttp, WorkManager, coroutines, foreground service, wakelock, WebSocket ou MQTT.

### Backend

A Edge Function existente `device-pairing` continua sendo o único endpoint. A ação `heartbeat`:

1. recebe a credencial permanente no header Bearer HTTPS;
2. calcula SHA-256 no servidor;
3. localiza uma credencial ativa em `device_credentials`;
4. define `last_seen_at` e `updated_at` com o relógio do servidor;
5. persiste versão e metadata sanitizada.

A migration `20260901123000_lock_device_presence_writes.sql` remove a policy antiga de update e revoga `UPDATE` em `devices` de `anon` e `authenticated`. O Admin mantém somente a leitura legítima das próprias telas via RLS. `service_role` permanece somente no backend.

### Admin 2

`listScreens()` passou a ler `last_seen_at`, `app_version` e `metadata` na relação de `devices`. A UI distingue:

- `SEM PLAYER`: tela sem dispositivo;
- `AGUARDANDO PLAYER`: dispositivo vinculado sem heartbeat válido;
- `ONLINE`: última comunicação há no máximo 12 minutos;
- `OFFLINE`: última comunicação acima de 12 minutos.

`PAIRED` continua sendo exibido como status de vínculo separado. A versão e a última comunicação aparecem discretamente. Um único refresh one-shot de 60 segundos é mantido enquanto o dashboard está ativo; aba escondida não consulta e logout cancela o timer. Apenas a grade é atualizada, preservando modais abertos.

## Contrato

```http
POST /functions/v1/device-pairing
Authorization: Bearer <credencial permanente do dispositivo>
Content-Type: application/json
```

```json
{
  "action": "heartbeat",
  "app_version": "2.0.0-phase8.3-presence",
  "metadata": {
    "connection": "ONLINE",
    "playback_state": "PLAYING",
    "cache_state": "OK",
    "health_state": "HEALTHY",
    "free_storage_bytes": 123456789,
    "last_sync_epoch_ms": null
  }
}
```

O backend aplica allowlist aos seis campos. Não são enviados UUID interno, código amigável, logs, stack traces, arquivos, playlist ou segredo em metadata. A credencial em texto puro fica no sandbox do app e na memória somente durante a chamada; não aparece em logs ou query string.

## Intervalo, backoff e concorrência

- primeiro heartbeat: antecipado após startup pareado, conclusão de pairing ou transição para rede disponível;
- ciclo normal: aproximadamente 5 minutos;
- falhas retryable de rede, HTTP 408, 429 ou 5xx: aproximadamente 1, 5, 15 e depois 30 minutos;
- HTTP 401: uma hora, sem apagar pairing, trocar credencial ou iniciar novo pairing;
- sucesso: zera falhas e volta a 5 minutos;
- um único job ID e `AtomicBoolean` impedem sobreposição.

O agendamento é tolerante ao batching do Android e não promete precisão absoluta.

## Lifecycle e offline

- startup e playback não aguardam heartbeat;
- job exige rede e não executa offline;
- perda de rede não altera `PAIRED`, não reinicia o playback e não abre modal de pairing;
- o job persistido volta a ser elegível com rede;
- callback online antecipa nova presença sem reiniciar mídia;
- force-stop deixa o dispositivo naturalmente expirar para OFFLINE;
- nova abertura e boot voltam a antecipar heartbeat usando a mesma credencial.

## Testes automatizados

Android cobre:

- payload criado a partir de `DeviceHeartbeat`;
- versão e allowlist de metadata;
- header Bearer e representação redigida;
- bloqueio para `UNPAIRED`;
- HTTP 2xx, 401, 400 e 503;
- falha retryable;
- backoff e retorno ao intervalo normal;
- exclusão mútua durante execução concorrente.

Admin/Vitest cobre:

- query dos novos campos;
- `ONLINE`, `OFFLINE`, aguardando e sem Player;
- formatação da última comunicação;
- timer único;
- suspensão com aba escondida;
- cancelamento no logout;
- toda a regressão de Auth e pairing.

Resultado final automatizado: 177 testes Android/JVM e 29 testes Admin, com zero falhas, erros ou skips. `lintDebug`, `assembleDebug`, `assembleRelease`, `npm run build` e `npm audit` foram aprovados; o audit reportou zero vulnerabilidades.

## Validação real no Supabase isolado

Executada contra `zdhsfirabkmivuzwyids`, com dados sintéticos removidos ao final:

| Cenário | Resultado |
|---|---:|
| credencial válida | HTTP 200 |
| `last_seen_at` atualizado | aprovado |
| `app_version` atualizado | aprovado |
| metadata limitada a allowlist | aprovado |
| sem Authorization | HTTP 401 |
| credencial inexistente | HTTP 401 |
| credencial revogada | HTTP 401 |
| Admin tenta atualizar presença diretamente | HTTP 403 |
| proprietário lê presença da própria tela | aprovado |
| outro usuário lê o dispositivo | zero linhas |

Migration e Edge Function foram implantadas somente no projeto exclusivo do Player 2.0.

## Validação no LDPlayer

Ambiente: LDPlayer 9, integração Android emulador; não representa certificação da MXQ.

- APK debug instalado com versão `2.0.0-phase8.3-presence`;
- pairing real concluído;
- primeiro heartbeat recebido;
- seis campos de metadata persistidos;
- tela de login do Admin 2 aberta no navegador local; leitura real de presença validada pela mesma consulta autenticada/RLS usada pelo dashboard;
- job one-shot observado terminando por `jobFinished` e deixando o próximo ciclo agendado;
- estado `PAIRED` preservado após atualização/reabertura;
- perda de rede não gerou novo código de pairing;
- presença expirou para OFFLINE após a janela de 12 minutos;
- retorno de rede antecipou heartbeat e retornou a ONLINE;
- force-stop fez a presença expirar sem tráfego adicional;
- reabertura retomou heartbeat;
- reboot preservou pairing e retomou heartbeat após boot;
- sem crash, ANR, OOM ou loop agressivo de rede observado.

Os estados ONLINE/OFFLINE foram comprovados contra dados reais pela consulta autenticada do Admin e pelas funções de apresentação testadas em Vitest. O login visual com a conta efêmera não foi repetido no navegador, evitando transmitir uma senha de laboratório pela automação; essa conta e todos os registros de teste foram removidos ao final.

## Custo periódico

Uma requisição JSON pequena aproximadamente a cada cinco minutos quando pareado e online. O job cria uma thread apenas enquanto executa; não há thread ociosa permanente. O Admin realiza uma leitura pequena a cada 60 segundos somente com dashboard visível.

## Arquivos

Criados:

- `app/src/main/kotlin/com/loopin/player2/DeviceHeartbeatRuntime.kt`;
- `admin2/src/presence.ts` e `presence.test.ts`;
- `admin2/src/refresh.ts` e `refresh.test.ts`;
- `supabase/migrations/20260901123000_lock_device_presence_writes.sql`;
- `docs/PHASE8_3_DEVICE_PRESENCE.md`.

Alterados:

- `.gitignore`;
- `agent.md`;
- `app/build.gradle.kts`;
- `app/src/main/AndroidManifest.xml`;
- `LoopinApplication.kt`, `MainActivity.kt` e `PairingRuntime.kt`;
- `DeviceOperations.kt` e `DeviceOperationsTest.kt`;
- `admin2/src/api.ts`, `api.test.ts`, `types.ts`, `main.ts` e `styles.css`;
- `supabase/functions/device-pairing/index.ts`.

## Limitações e itens adiados

- certificação física e soak test na MXQ continuam pendentes;
- Supabase Realtime não foi adicionado;
- playlist/manifesto remoto, campanhas, upload, comandos, screenshots, restart/reboot remoto, clear cache e OTA continuam fora do escopo;
- WEATHER remoto e qualquer mudança visual do WEATHER continuam adiados;
- o release local continua unsigned e não é um artefato distribuível.

## Resultado final

O caminho `Player pareado → heartbeat autenticado → presença server-side → leitura RLS → ONLINE/OFFLINE no Admin 2` está implementado sem tornar rede crítica para boot ou playback e sem expor a credencial permanente.
