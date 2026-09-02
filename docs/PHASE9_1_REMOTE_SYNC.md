# Fase 9.1 — sincronização remota real

## Objetivo e arquitetura

A Fase 9 definiu o snapshot schema 2 e sua associação a uma tela. A Fase 9.1 liga o caminho operacional: Player pareado → manifesto autenticado → autorização individual de asset → download transacional → ACTIVE → playback.

O Android continua leve: `HttpURLConnection`, `JobScheduler` one-shot e o `TransactionalPlaylistStore` existente. Não foram adicionados Retrofit, OkHttp, coroutines, WorkManager, serviço permanente ou cache paralelo.

## Manifesto

`AuthenticatedRemoteManifestSource` chama `BuildConfig.MANIFEST_ENDPOINT` com a credencial permanente no header Bearer. A resposta 200 é limitada a 1 MiB e decodificada estritamente por `VersionedManifestCodec`. Estados: 200 disponível; 204 sem atribuição; 304 inalterado; 401 autenticação; 409 estrutural; 408/429/5xx retryable; I/O offline.

O ETag do servidor é persistido em `PublishedVersionRef.manifestSha256`. `versionRef` permanece o SHA-256 canônico local usado para validar o diretório imutável. Essa separação permite `If-None-Match` real sem enfraquecer a integridade local e aceita A v8 → B v1 ou rollback v8 → v7.

## Mídia e storage

`player-media` recebe Bearer + `asset_id`, autentica a credencial ativa, resolve dispositivo/tela/versão atribuída e só autoriza asset MEDIA presente naquele snapshot. O caminho interno vem de `player_media_assets`; o manifesto nunca contém storage path ou signed URL.

O bucket `player2-media` é privado. A URL assinada expira em 900 segundos, não é persistida e não entra em logs. Cada abertura resolve URL nova. O corpo da mídia é transmitido diretamente ao cache: `.part` → tamanho → SHA-256 → objeto imutável. WEATHER participa de staging/ACTIVE/PREVIOUS sem download.

## Publicação, playback e offline

Uma falha em qualquer item rejeita staging e preserva ACTIVE. O commit só ocorre depois de todos os objetos estarem válidos. 204 também preserva ACTIVE. Sem ACTIVE, o programa bundled é fallback; com ACTIVE remoto, a ordem recebida é usada exatamente, sem WEATHER artificial.

Depois do commit, `PlaylistActivationNotifier` entrega o novo ACTIVE à Activity. `LoopingPlaybackEngine.replaceAfterCurrent()` mantém o item corrente e troca no próximo avanço, sem reiniciar Activity, aplicativo ou dispositivo. Reboot offline carrega o ponteiro ACTIVE e seus objetos locais.

## Scheduler e lifecycle

O job exige rede e Player `PAIRED`. Há tentativa antecipada no startup pareado, após pairing e no retorno da rede. O ciclo normal é 5 minutos. Falhas retryable usam 1, 5, 15 e 30 minutos; 401 usa aproximadamente 1 hora. `AtomicBoolean` impede dois syncs simultâneos.

## Segurança

Credencial, seu hash, JWT, pairing token, signed URL, senha, PAT e service role não são registrados nem versionados. O Player recebe somente sua credencial permanente e nunca acessa tabelas privilegiadas diretamente. `service_role` existe apenas dentro das Edge Functions.

## Validação

Testes JVM cobrem estados HTTP, headers Bearer/ETag, schema 2, identidade remota, troca numericamente menor, WEATHER sem download, deduplicação/cache transacional, integridade, retry e transição segura do playback. A suíte completa inclui cache, pairing, presença, operações, conteúdo e regressões anteriores.

No Supabase isolado foram aplicados o bucket privado e as funções `player-manifest`/`player-media`. O laboratório real confirmou pareamento, seed de vídeo + WEATHER, manifesto 200, autorização 200 do asset pertencente, negação 403 do asset ausente, download, um objeto local e ACTIVE publicado. O gateway apresentou ETag fraco; cliente e endpoint passaram a normalizar `W/` antes da comparação. Usuários, tela, versão, asset, objeto de storage, credencial e dispositivo laboratoriais foram removidos, assim como a função temporária de seed.

No LDPlayer, o APK foi instalado limpo, pareado e sincronizou a mídia real do bucket. ACTIVE sobreviveu a force-stop/reabertura e reboot completo; não houve crash ou ANR observado. A ordem e transição são verificadas deterministicamente por teste do engine, pois não havia monitor físico para confirmação visual. LDPlayer não certifica MXQ.

## Adiado

Upload/editor de mídia, editor de playlist, campanhas, comandos, screenshots, OTA, Realtime, analytics e mudanças visuais de WEATHER permanecem fora do escopo. Certificação física MXQ continua pendente.
