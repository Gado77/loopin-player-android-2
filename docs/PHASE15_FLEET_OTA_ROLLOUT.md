# Fase 15 — Grupos de telas e rollout OTA de frota

Data: 05/09/2026

## Resultado

A publicação de um release não implica mais distribuição. Um Player somente recebe uma atualização quando seu device está no snapshot de um rollout `ACTIVE`, pertence à onda atual, usa o mesmo canal, está dentro da janela de manutenção opcional e o release continua `PUBLISHED`.

O Android não foi alterado: ele continua fazendo o check leve já existente e recebe apenas a decisão autoritativa do backend. Não foi criado scheduler, serviço, polling, biblioteca ou estado local novo.

## Arquitetura

- `screen_groups` e `screen_group_members`: grupos genéricos, many-to-many e isolados por proprietário.
- `update_rollouts`: release, estado, ondas, onda atual, agendamento, janela e limiar do circuit breaker.
- `update_rollout_group_targets` e `update_rollout_screen_targets`: seleção editável enquanto o rollout está em `DRAFT`.
- `update_rollout_devices`: snapshot imutável da frota resolvida no momento da ativação.
- `get_eligible_player_release`: decisão service-only usada pela Edge Function; o Player não conhece grupos, cohorts ou outros devices.
- `player-update`: `check`, `download` e `authorize_install` exigem elegibilidade no rollout. SHA, certificado, preparação e PackageInstaller da Fase 14 permanecem intactos.

## Grupos, tenancy e snapshot

`set_screen_group` cria/renomeia o grupo e substitui atomicamente seus membros. Cada screen informada é validada contra `auth.uid()`. RLS impede leitura de grupo, membership, rollout e devices de outro tenant; membership e estruturas de rollout não aceitam escrita direta de `anon` ou `authenticated`.

Na ativação, targets diretos e membros de grupos são unidos e deduplicados. O snapshot guarda a screen, o device pareado encontrado naquele instante, o cohort e incompatibilidades (`NO_DEVICE`/`CHANNEL_MISMATCH`). Alterações posteriores no grupo não mudam o rollout já ativado.

## Estados e controle

Estados do rollout: `DRAFT`, `SCHEDULED`, `ACTIVE`, `PAUSED`, `PAUSED_AUTO`, `COMPLETED` e `CANCELED`.

O Admin permite criar, ativar, pausar, retomar, avançar onda e cancelar. Rollouts terminados não retornam a estados ativos. Um device não pode participar simultaneamente de outro rollout `SCHEDULED`, `ACTIVE`, `PAUSED` ou `PAUSED_AUTO`.

## Ondas e cohort

As ondas padrão são `5, 25, 100`. A lista deve ser estritamente crescente, conter de 1 a 10 entradas e terminar em 100. O cohort é determinístico para o par rollout/device, mapeado para `0..9999`; a onda compara o score com percentual × 100. Avançar a onda nunca retira devices já elegíveis.

O cohort é apenas uma divisão estável de frota, não uma fronteira criptográfica.

## Agendamento e janela de manutenção

Uma ativação futura produz `SCHEDULED`; na primeira consulta elegível após o instante programado o backend promove o rollout para `ACTIVE`. A janela opcional exige timezone IANA válido e dois horários diferentes. Intervalos comuns e intervalos atravessando meia-noite são calculados no banco no timezone configurado. Fora da janela, check/download/autorização não oferecem o release.

## Circuit breaker

O trigger de `device_update_attempts` reflete instalação e falha no snapshot. A partir de cinco devices tentados, uma taxa de falha maior ou igual ao limiar (30% por padrão) muda automaticamente o rollout ativo para `PAUSED_AUTO`. A retomada é uma decisão explícita do operador. Na onda final, quando todos os snapshots estão em estado terminal, o rollout passa para `COMPLETED`.

## Admin 2

A navegação agora contém `Telas`, `Mídias`, `Playlists`, `Grupos` e `Atualizações`. Grupos podem ser criados, renomeados e ter membros adicionados/removidos. Releases publicados exibem a ação de criar rollout. O dashboard mostra estado, release, onda/percentual, janela, métricas, devices e filtros, com confirmação antes de ações e alerta adicional ao avançar com falhas. O layout recebeu apenas estilos responsivos necessários; não houve redesign geral.

## Segurança

- credencial permanente continua somente no device e no header HTTPS;
- `service_role`, tokens e hashes não são expostos no Admin nem em logs;
- o Player não escolhe release, cohort, onda ou janela;
- RLS isola tenants e RPCs validam propriedade novamente;
- mutações operacionais exigem `release_admin` e proprietário do rollout;
- `get_eligible_player_release` só pode ser executada por `service_role`;
- release revogado deixa imediatamente de ser elegível;
- a whitelist de `GET_STATUS` foi sincronizada com os campos OTA persistidos na Fase 14, sem aceitar payload arbitrário.

## Validação

### Automatizada

- Android/JVM: 229 testes; `test`, `lintDebug`, `assembleDebug` e `assembleRelease` aprovados.
- Admin: 101 testes Vitest aprovados.
- Admin: TypeScript e Vite production build aprovados.
- `npm audit`: zero vulnerabilidades.
- Novos testes puros cobrem ondas inválidas, janela incompleta/atravessando meia-noite, percentual atual, métricas, cohorts anteriores, próxima onda, READY derivado e presença de falhas.

### Supabase isolado

As migrations `20260906100000_phase15_fleet_ota_rollout.sql` e `20260906101000_phase15_maintenance_window_validation.sql` foram aplicadas no projeto exclusivo `zdhsfirabkmivuzwyids`. `player-update` e `player-commands` foram implantadas.

Uma rodada sintética com usuários, cinco screens/devices, grupo, release e credencial temporários exercitou criação de grupo, isolamento entre tenants, bloqueio de escrita direta, snapshot deduplicado, cohort, rollout ativo, pausa, retomada, conflito, janela fechada e agendamento. O primeiro ensaio revelou a diferença real do schema (`devices` não possui `owner_id`); o fixture foi corrigido e os dados temporários foram removidos. Nenhum segredo foi registrado no relatório.

### LDPlayer

O APK já instalado permaneceu em `versionCode 14` / `2.0.0-phase14-update-install`, como esperado, pois esta fase não altera Android. Pairing, credencial e configuração essencial permaneceram após force-stop e reboot. O processo reiniciou manualmente sem crash ou ANR. Neste reboot específico, o LDPlayer não entregou evidência de autoabertura via `BOOT_COMPLETED` em até 40 segundos; isso é uma limitação/regressão de integração a revalidar, não foi mascarada como aprovação de MXQ.

## Custo operacional

Não há custo periódico novo no Player. A elegibilidade acrescenta uma consulta indexada e atualização oportunística de rollout agendado ao check OTA já existente. O Admin carrega grupos/rollouts no refresh já existente; não cria timer adicional.

## Arquivos

- `supabase/migrations/20260906100000_phase15_fleet_ota_rollout.sql`
- `supabase/migrations/20260906101000_phase15_maintenance_window_validation.sql`
- `supabase/functions/player-update/index.ts`
- `supabase/functions/player-commands/index.ts`
- `admin2/src/api.ts`
- `admin2/src/types.ts`
- `admin2/src/navigation.ts`
- `admin2/src/navigation.test.ts`
- `admin2/src/fleet.ts`
- `admin2/src/fleet.test.ts`
- `admin2/src/main.ts`
- `admin2/src/styles.css`
- `agent.md`

## Limitações e itens adiados

- E2E binário v14→v15, confirmação/cancelamento reais do PackageInstaller e assinatura LAB continuam pendentes porque esta fase não produz APK v15 e o laboratório de release/inspector/signing não foi provisionado.
- Auto-start no reboot observado nesta rodada do LDPlayer deve ser repetido; inicialização manual funcionou e os stores persistiram.
- Certificação, instalação silenciosa e soak test 24/7 na MXQ física continuam pendentes.
- Campanhas, calendário editorial, Realtime, MDM, firmware, rollback binário automático e rollout por atributos dinâmicos permanecem fora de escopo.

## Conclusão

A decisão de distribuição passou do release global para um rollout de frota explícito, auditável e tenant-safe, sem aumentar o peso do Player. A infraestrutura remota e o Admin estão operacionais; a certificação física e o E2E com um APK v15 real permanecem declaradamente pendentes.
