# Fase 8 — Auditoria de prontidão para integração real

Data: 26/08/2026

## Decisão

O Player 2.0 **não foi conectado diretamente ao Supabase** nesta etapa. O Admin ainda não oferece um contrato autenticado para dispositivos nem um manifesto compatível com as garantias transacionais do Player. Conectar o APK diretamente às tabelas com a chave anônima reproduziria regras do Admin no cliente, exporia dados de outros usuários e eliminaria garantias de integridade já validadas.

O design WEATHER, relógio, playback, Player de produção, Admin e MXQ não foram alterados.

## Estado do Player 2.0

### Funcional

- identidade interna persistente e código amigável de seis dígitos separados;
- inicialização offline, boot receiver, launcher/kiosk e lifecycle;
- reprodução Media3 de vídeo e imagem local;
- WEATHER como item temporário da mesma playlist;
- playlist ordenada e determinística, retry e recuperação;
- manifesto de mídia normal, download `.part`, tamanho/hash e publicação atômica;
- ACTIVE/PREVIOUS, rollback e recuperação de staging;
- sync one-shot abstrato com backoff e `JobScheduler`;
- cache de clima e fallback offline;
- diagnóstico local, estados operacionais e fundação de update;
- validação de APK por versão, tamanho, SHA-256, package e certificado.

### Mock

- playlist de demonstração em `LocalTestPlaylistRepository`;
- clima debug de São José do Piauí;
- mídias normais empacotadas de teste;
- URLs/configuração remota vazias;
- pairing, heartbeat, comandos e telemetria sem transporte real.

### Preparado para backend, mas sem integração

- `RemoteManifestSource` e `RemoteMediaSourceFactory`;
- `SyncManager` e cache transacional;
- `WeatherDataSource`/`WeatherRepository`;
- `PlayerUpdateSource`/`PlayerUpdateManager`/`PlayerInstaller`;
- estados de pairing, health, comandos, sync e update.

## Contratos encontrados no Admin

### `screens`

Campos observados no código e no REST atual:

- `id`, `user_id`, `name`, `device_id`;
- `location_id`, `active_playlist_id`, `theme_id`;
- `orientation`, `is_muted`, `is_paused`;
- `status`, `last_ping`, `current_content`;
- `playlist_items_count`, `cache_used_mb`;
- `weather_mode`: `AUTO` ou `CUSTOM`;
- `weather_lat`, `weather_lon`, `weather_city_name`.

O Admin cadastra manualmente `device_id`. A UI ainda apresenta o placeholder legado `TELA-XXXXXX`; não existe endpoint de reserva/pareamento do código numérico do Player 2.0 nem associação com sua identidade interna.

### `playlists` e `playlist_items`

`playlists` usa `id`, `user_id`, `name`, `description`, `loop_enabled` e `duration_total`.

`playlist_items` usa:

- `playlist_id`;
- `campaign_id` ou `widget_id`;
- `item_type`: `campaign` ou `widget`;
- `display_order`;
- `duration`;
- campos legados opcionais `position`, `duration_override` e `config_json`.

A ordem real já é representável por `display_order`. Portanto, não deve existir regra fixa de frequência do WEATHER.

### `campaigns`

Campos necessários ao Player encontrados:

- `id`, `name`;
- `media_url`, `media_type`;
- `duration_seconds`;
- `status`, `start_date`, `end_date`.

Não foram encontrados tamanho, SHA-256, MIME validado ou versão imutável do objeto. Sem esses dados, o backend atual não produz o contrato de cache forte exigido pelo Player.

### `dynamic_contents`

Campos:

- `id`, `user_id`, `name`;
- `content_type` (`weather`, `news`, `economy`, `ticker`, `html`);
- `configuration` JSON;
- `is_active`.

Para WEATHER, a configuração atual usa `{ "city": string, "interval": number }`. A duração efetiva do item vem de `playlist_items.duration`.

### Cidade e clima

Não existe tabela de cidades nas migrations ou no código atual. Há três conceitos diferentes:

1. lista `BRAZILIAN_CITIES` hardcoded na tela de conteúdo dinâmico;
2. texto `locations.city`;
3. configuração por tela em `weather_lat`, `weather_lon` e `weather_city_name`.

Logo, o requisito de administrador cadastrar/gerenciar cidades ainda não possui contrato persistido.

Existe uma função Cloudflare conceitual `GET /weather?city=...`, mas:

- o repositório não define URL de produção implantada;
- a variável `OPENWEATHER_API_KEY` está vazia no `wrangler.toml`;
- a chamada usa `app_id`, enquanto o parâmetro esperado pelo OpenWeather é `appid`;
- a resposta contém apenas temperatura, sensação, umidade, descrição, ícone, cidade e atualização;
- não fornece máxima, mínima nem previsão necessárias ao modelo atual.

### Operações

O Admin grava comandos em `screen_commands` com `screen_id`, `command`, `payload`, `status`, `created_at`, `executed_at` e `expires_at`. A UI envia `restart`, `pause`, `resume`, `screenshot` e `refresh`.

Logs são lidos de `player_logs`. Não foi encontrado protocolo autenticado para o Player consumir/confirmar comandos ou publicar health/logs.

### Auto-update

Não foi encontrada tabela, função ou endpoint com:

- canal STABLE/BETA;
- `versionCode`/`versionName` disponíveis;
- URL do APK;
- tamanho e SHA-256;
- certificado esperado;
- compatibilidade/minSdk;
- rollout ou política de fallback.

O Player possui a fundação local, mas não existe fonte remota real para conectá-la.

## Bloqueios comprovados

1. **Autenticação do dispositivo ausente.** Não há token de pairing, credencial rotacionável ou sessão própria da tela.
2. **Acesso anônimo excessivo.** Uma consulta somente leitura confirmou que a chave `anon` atual consegue ler registros de várias tabelas operacionais sem sessão de usuário. O Player não deve incorporar esse acesso.
3. **Manifesto de Player ausente.** O Admin não gera um documento versionado e atômico contendo mídias normais e dinâmicas.
4. **Integridade de mídia incompleta.** Campanhas não expõem tamanho e SHA-256.
5. **Identidade incompatível.** O Admin ainda trabalha visualmente com `TELA-XXXXXX`; o Player 2.0 exibe seis dígitos e preserva identidade interna separada.
6. **Cadastro de cidades ausente.** A lista ainda é hardcoded; permissões específicas para gerenciar cidades não existem no contrato versionado.
7. **Clima real incompleto.** Não há endpoint implantado comprovado nem payload suficiente para a tela aprovada.
8. **Update remoto ausente.** Não há metadata ou mecanismo de instalação escolhido.
9. **Schema incompleto no repositório.** As migrations presentes são incrementais e não criam as tabelas principais, portanto políticas RLS e constraints autoritativas não podem ser auditadas integralmente pelo código versionado.

## Contrato mínimo necessário para prosseguir

Sem prescrever implementação do Admin, o Player precisa receber de uma fronteira autenticada:

1. resultado de pairing que vincule `internalId` + código amigável a uma `screen` e emita credencial revogável;
2. manifesto versionado por tela contendo a ordem exata de `campaign` e `weather`;
3. para mídia: URL temporária/segura, tipo, duração, tamanho, SHA-256 e MIME;
4. para WEATHER: cidade de exibição, latitude/longitude e duração do item;
5. payload climático completo ou fonte oficialmente implantada;
6. metadata de update assinada/validável por canal;
7. endpoints autenticados de health, logs e comandos com cursor/ack, sem polling agressivo;
8. políticas RLS versionadas e verificáveis.

## Próximo passo recomendado

Definir e implementar no ecossistema um **contrato de Player autenticado** e um **gerador de manifesto por tela**. Depois disso, o Player 2.0 pode ganhar adaptadores concretos sem modificar playback, WEATHER ou cache transacional.

Até esse contrato existir, manter a fixture local é mais seguro que conectar o APK diretamente às tabelas atuais.
