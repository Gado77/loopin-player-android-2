# Fase 10 — biblioteca e gestão mínima de conteúdo

## Objetivo e arquitetura

A Fase 10 torna utilizável pelo Admin 2 o pipeline remoto já entregue nas Fases 9/9.1. O fluxo operacional é: sessão autenticada → upload privado → asset registrado → rascunho mutável → snapshot imutável → associação à tela → manifesto autenticado → download validado → ACTIVE → playback. Não foi criado um segundo modelo de conteúdo e nenhum código Android precisou mudar.

## Upload, Storage e hashing

O Admin aceita apenas `video/mp4` (até 300 MiB), `image/jpeg` e `image/png` (até 20 MiB). O SHA-256 é calculado antes do upload por uma implementação incremental testada, em chunks de 2 MiB; o arquivo completo não é carregado em memória. Os estados exibidos são “Calculando integridade”, “Enviando”, “Registrando” e “Concluído”.

O bucket continua privado e exclusivo: `player2-media`. Objetos novos usam `users/<auth.uid()>/<asset_uuid>/original.<ext>`, nunca recebem URL pública e são enviados com overwrite desativado. A policy permite apenas INSERT autenticado no namespace próprio, com UUID e extensão conhecida; não há leitura/listagem pública nem acesso cruzado. Se o SHA já existir para o mesmo proprietário, o asset é reutilizado antes de novo upload. Deduplicação não atravessa tenants.

`register_player_media_asset` deriva proprietário e caminho a partir de `auth.uid()`, confirma objeto, bucket, tamanho e MIME no Storage, valida SHA/tipo/limites e grava o asset. O browser não escolhe `owner_id` nem `storage_path`. Se o upload concluir e o registro falhar, o objeto pode ficar órfão para limpeza administrativa futura: não foi concedido DELETE amplo ao cliente porque versões imutáveis podem depender de mídias antigas.

## Draft, editor e publicação

`player_playlist_drafts` mantém um JSONB mutável separado das versões. O RLS permite leitura apenas ao proprietário e as alterações passam por `save_player_playlist_draft`. O servidor valida até 200 itens, campos permitidos, IDs e ordens únicos/contíguos, ownership dos assets, duração de imagem/WEATHER entre 1 e 3600 segundos, cidade e coordenadas.

O editor mínimo oferece seleção da biblioteca, duração para IMAGE, criação manual de WEATHER (`city`, `lat`, `lon`, `durationMs`), subir/descer, remover, salvar e publicar. A ordem é renormalizada para `0..n-1` depois de cada operação. Salvar não publica, não incrementa versão e não altera telas.

`publish_player_playlist_draft` lê o draft próprio e delega à RPC transacional comprovada `publish_player_playlist_version`. Ela bloqueia a playlist, deriva tipo/tamanho/SHA/MIME dos assets, valida WEATHER, calcula o manifesto schema 2 e cria o próximo snapshot imutável. Publicar não atribui automaticamente. O histórico aparece por playlist e o seletor existente em Telas continua usando `assign_player_playlist_version`.

## Admin e fluxo do Player

O Admin 2 agora possui as áreas Telas, Mídias e Playlists sem router ou framework adicional. Login, pairing, presença e refresh existentes foram preservados. Os novos cards/editor são responsivos, não dependem de hover, não reproduzem vídeos em massa e não oferecem exclusão destrutiva.

O Player 2 continua na versão `2.0.0-phase9.1-remote-sync`, `minSdk 21`. O pipeline Android não mudou: manifesto e URLs temporárias continuam autenticados, mídia é baixada para `.part`, tamanho/SHA são verificados e somente então o conjunto vira ACTIVE. WEATHER não é baixado.

## Validação automatizada

- Baseline: 195 testes Android/JVM e 32 testes Admin aprovados antes da alteração.
- Admin final: 61 testes em 8 arquivos, cobrindo regressões de login/pairing/presença, navegação, limites/MIME, SHA incremental e Blob chunked, deduplicação, namespace/upload/falha, listagens, criação, draft, MEDIA/IMAGE/WEATHER, coordenadas/duração, ordem/remoção e RPCs de publicação.
- `npm.cmd run build`: aprovado.
- `npm.cmd audit`: zero vulnerabilidades.
- Regressão Android: `test lintDebug assembleDebug assembleRelease` aprovada; 195 testes permanecem aprovados.

## Supabase real e isolamento

A migration `20260903160000_phase10_content_admin.sql` foi aplicada somente ao projeto isolado `zdhsfirabkmivuzwyids`. Um laboratório temporário criou dois usuários confirmados e foi removido depois do teste. O fluxo real confirmou:

- A enviou VIDEO/IMAGE via funções do Admin, registrou assets e deduplicou por SHA;
- B não listou assets/playlists de A, não gravou no namespace de A e não salvou draft de A;
- registro com objeto/caminho indevido falhou;
- v1 e v2 foram monotônicas e distintas;
- UPDATE direto de snapshot imutável foi negado;
- criação, draft, publicação e associação ocorreram sem seed de conteúdo ou SQL manual.

Os usuários, dados, objetos, função temporária e segredo temporário foram removidos. Permanecem somente migrations e Edge Functions operacionais do produto.

## LDPlayer

O APK debug existente foi reinstalado limpo e pareado por código rotativo com uma tela criada pelo fluxo do Admin. O cenário real publicou VIDEO → WEATHER → IMAGE. O Player recebeu v1, baixou exatamente dois objetos, manteve WEATHER sem download, validou e fez `SYNC_COMMIT_SUCCESS active=1`; os logs confirmaram a reprodução na ordem publicada.

Depois, o draft foi reordenado e WEATHER removido, gerando v2. Após reabertura, o Player reaproveitou os dois objetos (`created=0`), publicou `active=2`, preservou v1 como PREVIOUS e reproduziu IMAGE → VIDEO. Durante a janela sem rede o playback continuou; após reinício/reconexão, ACTIVE v2 e pairing permaneceram persistidos. Não houve crash, ANR ou loop apertado observado. O LDPlayer é integração, não certificação física.

## Custo e limitações

Não foram adicionadas dependências, threads, polling, serviços ou buffers grandes. Hashing é CPU/chunk local somente quando o usuário escolhe upload. Progresso byte a byte e thumbnails ficaram adiados. Também permanecem adiados exclusão/retenção completa, limpeza automática de órfãos, campanhas, calendário, transcoding, upload em lote, drag-and-drop, analytics, comandos, OTA, Realtime e mudanças no WEATHER.

**PENDENTE — VALIDAÇÃO EM MXQ:** codecs, armazenamento real, boot/offline, consumo prolongado e estabilidade 24/7 continuam exigindo hardware físico configurado com ADB.
