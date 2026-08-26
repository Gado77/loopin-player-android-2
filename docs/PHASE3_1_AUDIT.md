# Fase 3.1 — Auditoria de confiabilidade do cache

> Atualização: o bloqueador transacional identificado neste documento foi tratado na Fase 3.2. Consulte `PHASE3_2_TRANSACTIONAL_PUBLISH.md` para a arquitetura e os testes atuais.

Data: 18/08/2026

## Veredito

A implementação atual é adequada para continuar os ensaios locais da Fase 3, mas **não está aprovada para sincronização remota nem para produção experimental com atualização de playlists**. O bloqueio principal é a ausência de uma transação de atualização completa que mantenha simultaneamente v1 e v2 em arquivos imutáveis/versionados.

Não foram integrados backend, Supabase ou HTTP real nesta auditoria.

## Achados

### CRÍTICO — Atualização v1 → v2 não é transacional

- **Impacto:** se v2 reutilizar `localFileName` de v1, `SafeMediaCache.store` substitui o caminho que a playlist v1 referencia. Um descritor já aberto pode continuar no Linux, mas a próxima abertura/volta do loop lê o novo arquivo. Falha parcial de v2 pode produzir uma mistura de versões. Não existe operação que prepare todos os itens e só então altere um ponteiro ativo.
- **Arquivos:** `SafeMediaCache.kt`, `ManifestStore.kt` e o futuro coordenador de sincronização, ainda inexistente.
- **Causa:** cache endereçado por nome lógico e ativação individual, sem diretório de staging por versão.
- **Correção proposta:** armazenar mídia por checksum ou por `playlistId/playlistVersion`; preparar e validar v2 integralmente; persistir manifesto candidato; trocar atomicamente apenas um ponteiro/registro de manifesto ativo; manter v1 até confirmação e período de rollback.
- **Status:** não corrigido nesta auditoria, pois exige decisão arquitetural e seria uma refatoração ampla. É obrigatório antes da sincronização remota.

### ALTO — Rollback semântico ainda é parcial

- **Impacto:** agora o `ManifestStore` retém uma versão anterior e consegue preferi-la quando o JSON ativo está ilegível. Porém, um manifesto sintaticamente válido com problema operacional não é automaticamente classificado como defeituoso, não há API de rollback e as mídias anteriores podem ter sido sobrescritas.
- **Arquivo:** `ManifestStore.kt`.
- **Causa:** ausência de health check/commit da versão e retenção conjunta do conjunto de mídias.
- **Correção proposta:** implementar junto da transação versionada um estado `candidate/active/previous`, critério de sucesso e rollback explícito.
- **Status:** fallback estrutural corrigido; rollback operacional permanece obrigatório antes da sincronização remota.

### ALTO — Não existe política de espaço, quota ou coleta

- **Impacto:** downloads podem consumir todo o armazenamento; o tamanho esperado só é conferido depois da gravação; não há reserva mínima, limite total ou coleta que proteja active/previous.
- **Arquivo:** `SafeMediaCache.kt`.
- **Causa:** política de armazenamento ainda não foi definida.
- **Correção proposta:** antes do backend, definir orçamento do cache, conferir espaço utilizável antes/durante a cópia, impor limite de bytes, interromper download excedente e coletar somente versões não ativas/não anteriores.
- **Status:** documentado, não implementado conforme o escopo. Obrigatório antes de mídia remota.

### ALTO — Concorrência era protegida apenas por instância — CORRIGIDO

- **Impacto anterior:** duas instâncias para o mesmo diretório podiam escrever o mesmo `.part`, perder atualizações do arquivo de estado ou intercalar troca e inspeção.
- **Arquivos:** `DirectoryLocks.kt`, `SafeMediaCache.kt`, `ManifestStore.kt`, `CacheStateStore.kt`.
- **Causa:** `@Synchronized` bloqueava somente `this`.
- **Correção:** lock compartilhado por caminho canônico dentro do processo. Teste concorrente com duas instâncias confirma serialização.
- **Risco residual:** não há file lock entre processos. O aplicativo atual usa um único processo; isso deve virar requisito explícito ou receber locking interprocesso antes de qualquer componente multiprocess.

### ALTO — Backup válido era removido imediatamente — CORRIGIDO

- **Impacto anterior:** corrupção/power loss posterior à troca deixava somente o manifesto novo, sem último conhecido válido.
- **Arquivo:** `ManifestStore.kt`.
- **Correção:** o backup anterior agora é retido e usado se o ativo estiver vazio, truncado, com JSON incompleto, schema inválido ou semanticamente inválido.
- **Teste:** ativo corrompido retorna v1; ativo e backup inválidos retornam ausência segura.

### MÉDIO — Durabilidade de arquivo não era forçada — PARCIALMENTE CORRIGIDO

- **Impacto anterior:** `writeText`/close antes do rename não garantiam flush físico em uma queda abrupta.
- **Arquivos:** `ManifestStore.kt`, `SafeMediaCache.kt`, `CacheStateStore.kt`.
- **Correção:** manifesto temporário, mídia temporária e estado agora executam `FileDescriptor.sync()` antes da ativação.
- **Risco residual:** Java/Android não oferece aqui uma forma portátil de `fsync` do diretório; `File.renameTo` no mesmo diretório é atomicamente visível, mas a durabilidade de metadados após perda de energia depende do filesystem/kernel. Deve ser validada na MXQ real com testes de corte de energia controlados.

### MÉDIO — Estado e arquivo não formam uma única transação

- **Impacto:** uma queda depois do rename e antes de gravar `READY` deixa o estado persistido atrasado. O inverso não ocorre: `READY` só é escrito após a ativação.
- **Arquivo:** `SafeMediaCache.kt`.
- **Mitigação atual:** `inspect` sempre revalida existência, tamanho e SHA-256; portanto o estado não transforma arquivo inválido em reproduzível. `.part` órfão é removido e `.previous` é restaurado quando não há destino.
- **Correção proposta:** no futuro, tratar o índice como derivado ou usar journal transacional. Pode esperar enquanto `inspect` continuar sendo a autoridade para playback.

### MÉDIO — Validação SHA-256 custa O(bytes) na inicialização

- **Impacto:** em TV Box lenta, validar dezenas de vídeos grandes na main thread pode atrasar a Activity ou causar ANR. O teste com mocks pequenos não representa gigabytes.
- **Arquivos:** `LocalTestPlaylistRepository.kt`, `SafeMediaCache.kt`.
- **Correção aplicada:** removida a segunda validação redundante durante a conversão do mock para `Playlist`.
- **Correção futura:** mover scan/hash para worker limitado, reutilizar índice validado com atributos confiáveis e manter a última playlist pronta enquanto a auditoria ocorre. Obrigatório antes de escalar para mídias remotas grandes.

### MÉDIO — Cobertura de logs é insuficiente para operação remota

- **Coberto hoje:** instalação do manifesto mock e resultado da materialização inicial.
- **Ausente:** manifesto carregado/rejeitado, início e conclusão genéricos, motivo de checksum/tamanho, recuperação de `.previous`, fallback, erro de armazenamento e rollback.
- **Arquivos:** classes de `core:media-cache` e integração em `LocalTestPlaylistRepository.kt`.
- **Correção proposta:** injetar uma interface pequena de eventos/logs no módulo, com um evento por transição e sem logging no loop de playback. Obrigatório antes da sincronização remota; não foi adicionado para evitar ampliar a API durante a auditoria.

### BAIXO — Registro de locks não remove caminhos antigos

- **Impacto:** o mapa process-local preserva um objeto por diretório canônico. No aplicativo há dois diretórios fixos, portanto o crescimento é constante.
- **Arquivo:** `DirectoryLocks.kt`.
- **Correção proposta:** nenhuma agora; revisar apenas se diretórios passarem a ser criados dinamicamente.

### INFORMATIVO — Segurança de caminhos aprovada no modelo atual

- `localFileName` aceita somente nome simples de até 127 caracteres e rejeita `/`, `\\`, `..` como componente e caminhos absolutos.
- `mediaId` é usado como chave escapada de `Properties`, não como caminho.
- Manifestos não fornecem uma URI local arbitrária ao playback; a URI vem do arquivo resolvido dentro do cache privado.
- Symlinks exigiriam comprometimento prévio do sandbox privado do aplicativo.

## Análise de falha de energia e corrupção

| Ponto da interrupção | Recuperação atual |
|---|---|
| Durante download | `.part` nunca é reproduzível e é removido na próxima abertura. |
| Após download, antes da validação | permanece `.part`; removido no reboot do processo. |
| Após mover destino para `.previous` | `.previous` é restaurado se o destino não existir. |
| Após ativar novo arquivo | destino completo existe; `.previous` residual é removido na abertura. |
| Durante gravação do manifesto | ativo anterior permanece; `.part` é ignorado. |
| Entre backup e ativação do manifesto | `loadLastValid` lê o `.bak`. |
| Ativo corrompido depois da ativação | fallback para backup retido. |
| Estado truncado/perdido | estado pode ser reconstruído pela inspeção do arquivo; nenhuma mídia é aceita só pelo estado. |
| Ativo e backup inválidos | repositório não tem playlist persistida recuperável; no mock empacotado há fallback, mas no futuro remoto deverá existir retenção adicional/playlist de emergência. |

## Playback durante atualização

O cenário exigido não é garantido pelo modelo atual:

1. v1 continua apenas enquanto seus caminhos não são substituídos ou enquanto o descritor antigo permanece aberto.
2. Itens de v2 podem ser baixados individualmente, mas não há conceito de candidato completo.
3. Arquivos ativos podem ser substituídos se os nomes coincidirem.
4. O manifesto pode ser salvo antes de todas as mídias estarem prontas por um integrador futuro.
5. Uma falha em v2 pode afetar reaberturas de itens de v1.

Por isso, o coordenador transacional e arquivos imutáveis/versionados são bloqueadores explícitos para backend remoto.

## Escala

Teste JVM com payloads de 14 bytes, SHA-256, estado persistido com `fsync` e 10/50/100 itens:

| Itens | Escrita | Leitura/validação | Estado |
|---:|---:|---:|---:|
| 10 | 294 ms | 14 ms | 209 bytes |
| 50 | 1.462 ms | 58 ms | 849 bytes |
| 100 | 2.682 ms | 55 ms | 1.649 bytes |

O uso de memória permaneceu limitado ao manifesto, mapa de itens e buffer de 8 KiB; a mídia é transmitida por stream. As operações de escrita e `fsync` crescem linearmente por item. O custo de SHA-256 cresce com o total de bytes, não apenas com o número de itens; os números acima não autorizam concluir desempenho aceitável com 100 vídeos grandes em uma MXQ.

## Testes adicionados

- concorrência entre duas instâncias no mesmo diretório;
- fallback de ativo corrompido para backup anterior;
- ativo vazio com backup inválido;
- recuperação de `.previous`;
- escala de 10, 50 e 100 itens;
- os testes anteriores continuam cobrindo interrupção, checksum, tamanho implícito, `.part` órfão, corrupção final e path traversal.

## LDPlayer: force-stop e reboot

- APK debug atualizado preservando dados: `Success`.
- Após force-stop, a Activity iniciou e reproduziu a playlist a partir do cache existente; os timestamps das mídias permaneceram inalterados.
- Após reboot completo da instância, manifesto, arquivo de estado e três mídias continuaram presentes e `READY`.
- SHA-256 pós-reboot: os três valores permaneceram idênticos ao manifesto.
- Não havia `.part` ou `.previous` residual.
- Ao iniciar a Activity via ADB depois do boot, o playback retornou em `PLAYING • video-a • loop 1`.
- Não houve FATAL EXCEPTION, ANR ou OOM.
- Observação fora do mecanismo de cache: o LDPlayer voltou para `com.android.settings/.FallbackHome` e não iniciou o Player automaticamente nesse reboot. Isso não corrompeu o cache, mas a automação de boot/kiosk deve continuar sendo acompanhada separadamente como validação da fundação.

## Decisão

1. **Fase 3 aprovada para produção experimental?** Não para atualização remota. Sim apenas para o laboratório local/mock já validado.
2. **Riscos remanescentes:** transação v1/v2, rollback operacional, quota/espaço, custo de hashing em mídia grande, logs operacionais e durabilidade do rename no hardware real.
3. **Obrigatório antes da sincronização remota:** cache imutável/versionado, staging completo, commit atômico do manifesto, retenção active/previous, quota e reserva de espaço, execução fora da main thread e eventos operacionais completos.
4. **Pode esperar:** locking interprocesso enquanto o app for single-process, journal sofisticado para estado derivado e limpeza do pequeno registro process-local de locks.
