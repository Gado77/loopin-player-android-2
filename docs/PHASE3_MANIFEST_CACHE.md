# Fase 3 — Manifesto e cache offline

## Arquitetura

O módulo `core:media-cache` concentra o formato do manifesto, persistência, estados e gravação segura. A fundação permanece inalterada e o Playback Engine continua recebendo o mesmo `Playlist` com URI exclusivamente local.

Fluxo atual:

```text
manifesto mock empacotado
        ↓
ManifestStore (último JSON válido)
        ↓
SafeMediaCache (.part → tamanho/hash → arquivo final)
        ↓
Playlist com somente arquivos READY
        ↓
Playback Engine existente
```

## Manifesto

`MediaManifest` contém `schemaVersion`, playlist, versão, instante de geração e itens ordenados. Cada `ManifestItem` contém identificador, tipo, URL remota opcional, nome local seguro, duração, ordem, tamanho, SHA-256, MIME type e metadados.

O arquivo ativo é `files/media/manifest/active-manifest.json`. A escrita usa `.part`, releitura/validação e backup antes da ativação. Na inicialização, o último manifesto válido pode ser lido sem rede; JSON inválido ou schema incompatível não é aceito.

## Cache

Estados disponíveis: `MISSING`, `DOWNLOADING`, `READY`, `INVALID` e `FAILED`. Somente `READY`, após validação real do arquivo, produz URI para reprodução.

O arquivo é escrito como `<nome>.part`. Tamanho e SHA-256 são conferidos quando informados. Só então ele substitui o destino. Falhas removem o `.part`; a troca preserva temporariamente a mídia anterior e permite recuperação após interrupção. Partes órfãs são descartadas na abertura do cache.

`HttpMediaSource` prepara downloads HTTP com timeouts e validação de status, mas não é chamado nesta fase. A API de cópia é bloqueante e deverá ser executada por um agendador fora da main thread quando a sincronização de rede for implementada.

## Fonte mock

`LocalTestPlaylistRepository` persiste o manifesto mock na primeira execução e copia os recursos locais empacotados através do mesmo caminho seguro do cache. Assim, o teste atual exercita manifesto, `.part`, tamanho e checksum sem internet.

Não foram adicionados Supabase, comandos remotos, OTA ou conteúdo dinâmico.

## Validação no LDPlayer

O APK `2.0.0-phase3` foi atualizado preservando os dados e executado no Android 9/API 28. O manifesto schema 1 foi persistido, os três arquivos ficaram `READY`, e tamanho e SHA-256 foram confirmados no próprio Android. Não restaram arquivos `.part` ou `.previous`.

O playback avançou normalmente usando URIs do cache. Após force-stop, reiniciou com o cache já existente. No ensaio offline controlado pelo LDPlayer, o contador avançou de `loop 2` para `loop 8`; o processo permaneceu ativo e a rede foi restaurada ao final. Não foram observados FATAL EXCEPTION, ANR ou OOM.
