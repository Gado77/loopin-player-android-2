# Fase 9 — Contrato autenticado de manifesto versionado por tela

## Resultado

A Fase 9 estabelece o contrato de publicação e entrega de playlists do Player 2.0 sem ativar sincronismo ou download remoto no APK. O Player continua usando somente o conteúdo local já válido. A ativação do consumo remoto fica explicitamente para a Fase 9.1.

## Contrato de manifesto

O schema 1 histórico permanece legível e sua serialização não foi alterada. O schema 2 é estrito, determinístico e normalizado por `VersionedManifestCodec`.

- raiz: `schemaVersion`, `playlistId`, `playlistVersion`, `generatedAtEpochMs`, `items`;
- campos desconhecidos, IDs repetidos, ordens repetidas e mistura de campos são rejeitados;
- `MEDIA`: `mediaType`, `assetId`, tamanho, SHA-256 e MIME obrigatórios; duração para imagem; URL não pertence ao snapshot canônico;
- `DYNAMIC`: somente `WEATHER`, duração positiva e configuração pequena; não aceita asset, hash, tamanho, MIME ou URL.

`assetId` é a identidade lógica estável. O cache imutável continua endereçado pelo SHA-256 do conteúdo. A identidade da versão local é o SHA-256 dos bytes determinísticos do manifesto.

## Cache e compatibilidade

`TransactionalPlaylistStore` aceita manifests schema 1 e 2. Para schema 2, somente itens `MEDIA` entram em cálculo de espaço, staging, origem, validação e diretório `objects`. Um item `WEATHER` participa de versão, ordem, ACTIVE/PREVIOUS, playback e rollback, mas não cria arquivo.

A leitura de versões publicadas detecta `manifest.json` (v1) ou `manifest-v2.json` (v2). Foram validados upgrade ACTIVE v1 → v2, recriação do store simulando reboot e rollback para PREVIOUS v1. Falha ou corrupção do candidato não troca o ponteiro ativo.

## Backend isolado

Migration `20260901180000_phase9_versioned_screen_manifest.sql`:

- `player_playlists`: playlist lógica por proprietário;
- `player_media_assets`: identidade e integridade do asset por proprietário;
- `player_playlist_versions`: snapshots publicados imutáveis, numerados e com hash estável;
- `screen_playlist_assignments`: uma versão publicada atribuída à tela;
- `publish_player_playlist_version`: publicação transacional, gera versão e snapshot no servidor;
- `assign_player_playlist_version`: associação transacional com verificação conjunta de tenant, tela ativa e playlist.

A migration complementar `20260901181000_validate_manifest_snapshots.sql` instala validação obrigatória antes de qualquer `INSERT`, inclusive via papel privilegiado: raiz e itens desconhecidos/mistos, tipos, campos obrigatórios e duplicidades são recusados. Um publish válido e a recusa de campo desconhecido foram novamente verificados no Supabase após a instalação do trigger.

RLS permite leitura apenas do proprietário. Não há `INSERT/UPDATE/DELETE` direto de versão ou associação para o Admin. Publicação e associação passam pelas RPCs. Associação cruzada entre tenants é recusada.

## Endpoint do dispositivo

`GET /functions/v1/player-manifest`

Autenticação: `Authorization: Bearer <credencial permanente do dispositivo>`. A função calcula SHA-256 e aceita somente credencial existente e não revogada ligada a dispositivo `PAIRED`. A tela e o dispositivo são derivados no servidor; o cliente não os escolhe.

- `200`: snapshot schema 2 atribuído;
- `204`: tela sem versão atribuída;
- `304`: versão/ETag já conhecida;
- `401`: header ausente, credencial inválida ou revogada;
- `409`: atribuição ou snapshot estruturalmente inválido.

Headers de resposta: `ETag` baseado no hash publicado e `X-Loopin-Playlist-Version`. Nenhuma credencial, hash de credencial, service role ou URL assinada é registrada em log.

## Admin 2.0

O dashboard preserva pairing e presence e acrescenta somente:

- leitura das versões publicadas visíveis por RLS;
- leitura da associação atual de cada tela;
- seletor simples de versão publicada;
- alteração exclusivamente pela RPC segura.

Não foi criado editor de playlist, upload ou dashboard novo.

## Runtime Android e offline

`BuildConfig.MANIFEST_ENDPOINT` documenta o endpoint futuro, mas não é conectado ao `RemoteSyncConfigStore`. A URL configurável de sync continua vazia, `RemoteSyncConfig.enabled` continua falso e nenhum `ContentSyncJobService` é agendado. Assim, boot, playback, WEATHER e operação offline permanecem independentes do backend.

## Validação automatizada

- linha de base: 177 testes Android e 29 Admin;
- fase: testes de codec determinístico, tipos MEDIA/DYNAMIC, campos desconhecidos/mistos, duplicidade, WEATHER sem objeto e upgrade/reboot/rollback v1↔v2;
- Admin: consulta de associação e versões e alteração via RPC;
- regressão final obrigatória: `gradlew test lintDebug assembleDebug assembleRelease`, `npm test`, `npm run build`, `npm audit`.

## Validação real no Supabase Player 2.0

Executada somente no projeto isolado `zdhsfirabkmivuzwyids`, com dois tenants temporários e limpeza final:

- tenant lê somente suas versões;
- associação cruzada recusada;
- escrita direta de associação e mutação de versão recusadas;
- credencial válida: 200 e schema 2 misto;
- cabeçalho de versão e 304 validados;
- ausência de associação: 204;
- sem Authorization, credencial inexistente e credencial revogada: 401;
- snapshot inválido controlado: 409.

Todos os usuários, telas, dispositivos, credenciais, playlists e assets do ensaio foram temporários e removidos. Valores secretos não foram impressos nem persistidos.

## LDPlayer

APK debug `2.0.0-phase9-manifest` instalado com sucesso na validação original. O hardening posterior atualizou a versão para `2.0.0-phase9.0.1-contract-hardening`. O processo iniciou, exibiu o fluxo de pareamento, não produziu crash/ANR e não registrou job de sincronismo de conteúdo. Um reboot completo do LDPlayer foi executado; o aplicativo continuou instalável/inicializável e o pareamento rotativo voltou normalmente. O LDPlayer é validação de integração, não certificação da MXQ.

## Custo e limitações

Esta fase adiciona zero polling, zero thread permanente, zero serviço, zero wakelock e zero requisição periódica no APK. O custo existe apenas no Admin sob ação do usuário e na Edge Function quando explicitamente chamada.

Pendências deliberadas para a Fase 9.1: cliente HTTP autenticado do manifesto, resolução separada `assetId → URL temporária`, downloads, scheduling remoto, retries, ativação transacional após rede e testes físicos na MXQ.

## Arquivos principais

- `core/media-cache/.../ManifestModels.kt`
- `core/media-cache/.../TransactionalPlaylistStore.kt`
- `core/media-cache/.../VersionedManifestTest.kt`
- `supabase/migrations/20260901180000_phase9_versioned_screen_manifest.sql`
- `supabase/functions/player-manifest/index.ts`
- `admin2/src/api.ts`, `types.ts`, `main.ts`, `styles.css`, `api.test.ts`
- `app/build.gradle.kts`
