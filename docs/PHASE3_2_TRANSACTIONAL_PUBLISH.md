# Fase 3.2 — Publicação transacional de playlists

## Garantia principal

Uma atualização incompleta, corrompida ou interrompida não substitui uma playlist ativa válida. O Playback Engine recebe somente objetos imutáveis alcançáveis pelo ponteiro `ACTIVE` confirmado.

## Estrutura

```text
files/transactional-media/
├── objects/
│   └── <sha256>
├── staging/
│   └── <manifest-sha256>/
│       ├── manifest.json
│       ├── preparation.json
│       └── media/*.part
├── versions/
│   └── <manifest-sha256>/
│       ├── manifest.json
│       ├── preparation.json
│       └── media/
└── pointers/
    ├── active-playlist.json
    ├── active-playlist.json.bak
    └── active-playlist.json.part
```

## Identidade imutável e reutilização

O nome físico de uma mídia é seu SHA-256 em minúsculas, sem depender do nome lógico recebido no manifesto. O mesmo conteúdo é armazenado uma única vez e pode ser referenciado por várias versões ou IDs. Conteúdo novo nunca sobrescreve outro objeto: se um arquivo com aquele hash existir e falhar na validação, o candidato é rejeitado.

O publicador transacional exige SHA-256 e tamanho esperado. Essa restrição é deliberada: sem ambos não é possível provar integridade nem estimar espaço antes da preparação.

## Preparação e staging

`TransactionalPlaylistStore.prepare`:

1. valida schema, IDs, ordem, nomes e identidade transacional;
2. calcula a identidade da versão pelo SHA-256 da serialização canônica local do manifesto;
3. cria staging isolado e grava manifesto/estado com flush e `FileDescriptor.sync()`;
4. valida uma vez cada objeto compartilhado já existente;
5. consulta a política de espaço;
6. transmite cada mídia ausente para `.part`, sem carregar o arquivo na RAM;
7. interrompe se o stream exceder o tamanho esperado;
8. valida tamanho e SHA-256;
9. promove o objeto imutável;
10. marca staging como `READY` somente quando todos os objetos estão válidos.

Falha em qualquer item produz `PreparationResult.Rejected`. O ponteiro ativo não é lido nem alterado durante essa rejeição. Objetos completos promovidos antes de uma rejeição são órfãos seguros e não ficam visíveis ao playback; não há coleta automática nesta fase.

## Commit atômico

`commit(versionRef)` revalida manifesto e todos os objetos, promove o diretório de staging para `versions` e monta um único `PlaylistPublicationState`:

```json
{
  "schemaVersion": 1,
  "active": {
    "playlistId": "...",
    "playlistVersion": 2,
    "manifestSha256": "...",
    "versionRef": "...",
    "publishedAtEpochMs": 0
  },
  "previous": {
    "playlistId": "...",
    "playlistVersion": 1,
    "manifestSha256": "...",
    "versionRef": "...",
    "publishedAtEpochMs": 0
  }
}
```

O estado completo `ACTIVE + PREVIOUS` é gravado em `.part`, sincronizado e relido. O ponteiro atual vira `.bak`; depois o `.part` é renomeado para `active-playlist.json` no mesmo diretório. Assim não existem duas trocas independentes que possam combinar active e previous incorretamente.

Antes do commit, a política de espaço é consultada novamente para a margem do ponteiro. A versão antiga e seus objetos não são removidos.

## Playback

O `Playlist` resolvido contém apenas URIs `file:` de objetos imutáveis pertencentes à versão ACTIVE. Staging e objetos órfãos nunca são enumerados para o Playback Engine.

Uma instância do playback que já usa v1 continua com os mesmos caminhos depois do commit de v2. Como v1 passa a PREVIOUS e não é removida, reaberturas e novos loops continuam válidos. O ExoPlayer não foi alterado.

A resolução, preparação e SHA-256 são executados pela implementação mock em um worker curto chamado `loopin-playlist-prepare`. A callback volta para a main thread antes de chamar o engine. Não há serviço ou thread permanente.

## Rollback

`rollback()`:

1. lê o estado atual;
2. exige PREVIOUS;
3. revalida manifesto e todas as mídias de PREVIOUS;
4. revalida ACTIVE;
5. grava atomicamente o estado invertido.

Se qualquer mídia anterior estiver ausente ou corrompida, o rollback é rejeitado e ACTIVE permanece inalterada.

## Recuperação após interrupção

| Interrupção | Resultado |
|---|---|
| durante stream | sobra apenas `.part` em staging; ACTIVE não muda |
| staging parcial | removido por recuperação; ACTIVE não muda |
| staging READY antes do commit | ACTIVE não muda; candidato pode ser confirmado posteriormente |
| versão promovida antes do ponteiro | versão órfã válida; ACTIVE não muda |
| depois de active virar `.bak`, antes do rename | `.bak` restaura o estado anterior |
| imediatamente após o rename | o novo ACTIVE válido é usado; `.bak` mantém o estado anterior |
| ACTIVE referencia mídia perdida | tenta PREVIOUS embutido; depois tenta `.bak` válido |

`FileDescriptor.sync()` é usado para conteúdo crítico. O rename do ponteiro ocorre dentro do mesmo diretório. Permanece a limitação já documentada de não haver `fsync` portátil do diretório pela API Java usada; queda física deve continuar sendo ensaiada na MXQ real.

## Espaço e retenção

`SpacePolicy` é uma abstração injetável. `ReservedSpacePolicy` preserva, por padrão, 64 MiB livres e exige espaço para todos os objetos ausentes, manifesto, metadados e margem de commit.

A política nunca apaga ACTIVE ou PREVIOUS. Não foi adicionada limpeza agressiva. Objetos órfãos podem acumular até que uma política de coleta consciente de leases de playback seja projetada.

## Concorrência

Todas as operações críticas usam o lock process-local por caminho canônico introduzido na Fase 3.1. Preparações, commit, rollback e recuperação não podem modificar o mesmo armazenamento simultaneamente. O playback usa arquivos imutáveis fora do lock e não enxerga staging.

O projeto continua single-process. Lock entre processos não foi implementado.

## Fonte mock

O aplicativo prepara v1 e depois v2 usando exclusivamente os recursos empacotados. Antes do commit de v2 registra a versão ACTIVE; depois registra `ACTIVE=2` e `PREVIOUS=1`. As duas versões reutilizam os mesmos dois objetos físicos, demonstrando deduplicação sem rede.

## Testes

A suíte transacional cobre:

1. commit v1 → v2;
2. candidato incompleto;
3. mídia corrompida;
4. mesmo nome lógico com conteúdo diferente;
5. conteúdo compartilhado;
6. objeto novo;
7. manifesto inválido;
8. staging incompleto;
9. interrupção antes da ativação do ponteiro;
10. recuperação depois de ativação interrompida;
11. rollback v2 → v1;
12. previous sem mídia;
13. falta de espaço;
14. duas atualizações concorrentes;
15. snapshot de playback durante staging;
16. snapshot durante/depois do commit;
17. objeto órfão;
18. staging abandonado;
19. recuperação pelo previous embutido sem `.bak`.

## Limitações restantes

- Não há HTTP, Supabase, OTA ou comando remoto.
- Não há garbage collector automático para objetos/versões órfãos.
- Não há lease persistente para um processo de playback muito antigo além de ACTIVE + PREVIOUS; por isso limpeza não foi implementada.
- O lock é process-local.
- A durabilidade definitiva do rename deve ser comprovada no filesystem da MXQ com corte de energia controlado.

## Validação no LDPlayer

- APK `2.0.0-phase3.2` atualizado com sucesso, preservando identidade e dados anteriores.
- A publicação mock registrou `ACTIVE=1` antes do commit de v2 e, após o commit, `ACTIVE=2 / PREVIOUS=1`.
- As duas versões publicadas permaneceram em diretórios distintos identificados pelo hash do manifesto.
- Foram criados somente dois objetos físicos para três itens e duas versões, confirmando reutilização por conteúdo.
- O diretório de staging ficou vazio após o commit.
- Um staging abandonado criado para o ensaio foi removido automaticamente após force-stop/início; o logger registrou `Recovered abandoned staging count=1`.
- Após force-stop, o ponteiro permaneceu em `ACTIVE=2 / PREVIOUS=1` e o playback retomou em loop usando a versão ativa.
- Não houve FATAL EXCEPTION, ANR ou OOM.

Não foi provocado corte físico de energia no LDPlayer. As interrupções nos dois lados do rename do ponteiro foram executadas por fault injection nos testes JVM; os resultados observados foram v1 antes da ativação e uma versão completa recuperável depois da ativação.

## Problema encontrado durante os testes

A primeira execução da matriz revelou que uma exceção lançada pelo provedor de `MediaSource` escapava de `prepare`. ACTIVE permanecia intacta, mas o worker poderia falhar sem retornar `Rejected`. A obtenção da fonte foi encapsulada e agora qualquer falha rejeita formalmente o candidato, preservando a versão ativa.
