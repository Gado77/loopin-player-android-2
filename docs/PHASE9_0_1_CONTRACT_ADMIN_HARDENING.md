# Fase 9.0.1 — Contract & Admin Hardening

## Objetivo

Correção curta da Fase 9 antes de qualquer ativação de rede no cache. Não adiciona playlist remota, download, signed URL, polling ou reprodução nova.

## Correções

### Admin 2

- A FK `screen_playlist_assignments.screen_id`, que também é PK, é tratada como relação one-to-one.
- A query usa o alias explícito `playlist_assignment` e o tipo é objeto/null.
- A grade usa delegação de eventos. Substituir `innerHTML` no refresh de presença não remove o listener do botão de pairing nem do seletor.
- A opção “Sem playlist” foi removida. Desassociação não é simulada enquanto não existir operação backend explícita.

### Manifesto Android

- Schema 2 exige lista não vazia.
- WEATHER exige exatamente `city`, `lat` e `lon`, strings não vazias e coordenadas dentro de latitude ±90 e longitude ±180.
- `remoteUrl` não existe no schema 2 e é rejeitada como campo desconhecido.
- A identidade canônica depende somente do snapshot estável, nunca de signed URL temporária.
- Schema 1 permanece separado e compatível.

### Supabase

Migration `20260901190000_phase9_0_1_contract_hardening.sql`:

- publicação MEDIA recebe apenas `assetId` e propriedades editoriais;
- servidor deriva `mediaType`, tamanho, SHA-256 e MIME de `player_media_assets` pertencente ao tenant;
- metadados divergentes enviados pelo cliente são campos desconhecidos e são recusados;
- trigger usa as mesmas regras Android para WEATHER, tipos MIME, lista vazia, campos conhecidos e coordenadas;
- URL não é aceita em snapshot MEDIA.

## Segurança

RLS, credencial permanente e autoridade device→screen permanecem inalteradas. Nenhum segredo foi adicionado ao APK, Admin, migrations, testes ou documentação.

## Fora do escopo

- fetch do manifesto no Player;
- resolução de URL temporária;
- download e ativação remota;
- upload/editor;
- unassign de playlist;
- alterações no WEATHER visual;
- produção ou MXQ.

## Critério de saída

Contrato Android/backend com paridade, Admin funcional após refresh, metadados MEDIA autoritativos, URL fora da identidade, regressões completas aprovadas e validação real no Supabase isolado.

## Validação real

Onze verificações passaram no Supabase isolado com dois tenants temporários: derivação autoritativa dos quatro metadados MEDIA; ausência e rejeição de URL; rejeição de metadados fornecidos pelo cliente; manifesto vazio; cidade vazia; latitude fora do intervalo; coordenada nula; asset de outro tenant; shape one-to-one como objeto; e entrega do snapshot endurecido pela Edge Function. Todos os registros e usuários de laboratório foram removidos ao final.

No LDPlayer, o APK `2.0.0-phase9.0.1-contract-hardening` foi instalado por atualização, iniciou com processo vivo, zero crash/ANR e zero job de sincronismo de conteúdo. Isso confirma a ausência de ativação acidental da Fase 9.1; não certifica a MXQ física.

Regressão final: 183 testes Android e 32 testes Admin aprovados; `lintDebug`, `assembleDebug`, `assembleRelease` e build web aprovados; `npm audit` sem vulnerabilidades.
