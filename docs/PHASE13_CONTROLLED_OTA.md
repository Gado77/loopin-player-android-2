# Fase 13 — OTA controlado

## Resultado e fronteira

A fase estabelece a cadeia `release_admin → APK privado → inspeção controlada → DRAFT → PUBLISHED → Player autenticado → .part → validações → READY_TO_INSTALL`. Instalação silenciosa não é declarada nem executada. Playback, cache ACTIVE/PREVIOUS, pairing e boot offline não dependem do OTA.

## Modelo e canais

`player_releases` guarda release UUID, canal `STABLE|BETA`, versionCode monotônico, versionName, package fixo `com.loopin.player2`, tamanho, SHA-256, caminho privado, fingerprint SHA-256 do certificado, status `DRAFT|PUBLISHED|REVOKED`, notas, autor e timestamps. `(package_name,channel,version_code)` é único. Release publicada é imutável; correção exige revogação e versionCode novo.

Cada device possui `update_channel`, default STABLE. A RPC `set_device_update_channel` deriva owner pela sessão e só altera Player pareado de tela própria. O canal enviado pelo Player não concede autoridade: `player-update` usa o canal persistido no device.

O app desta fase usa `versionCode=13` e `versionName=2.0.0-phase13-ota`. Builds laboratoriais podem sobrescrever ambos por `LOOPIN_VERSION_CODE/LOOPIN_VERSION_NAME`; releases normais devem crescer monotonamente e nunca comparam versionName.

## Storage, upload e inspeção

O bucket `player2-releases` é privado, limitado a 100 MiB. Objetos usam `releases/<channel>/<release_uuid>/player.apk`, sem upsert. O Admin 2 cria DRAFT e recebe token curto de upload; usuário comum é bloqueado no backend porque `admin-releases` exige entrada em `release_admins`.

Package, versionCode, versionName e certificado não vêm de campos do navegador. O script `scripts/publish/inspect-player-release.ps1` executa `aapt` e `apksigner`, calcula SHA-256 incremental com ferramenta do sistema e envia o resultado usando o segredo separado `LOOPIN_RELEASE_INSPECTOR_TOKEN`. A Edge Function confere papel, token do inspetor e tamanho do objeto antes de marcar `inspected_at`. O navegador somente publica um DRAFT já inspecionado pela RPC.

Variáveis locais do inspetor: `LOOPIN_RELEASE_ADMIN_TOKEN` e `LOOPIN_RELEASE_INSPECTOR_TOKEN`. Nenhuma é versionada.

## Assinatura

Release Gradle aceita exclusivamente configuração externa:

- `LOOPIN_RELEASE_KEYSTORE`
- `LOOPIN_RELEASE_KEY_ALIAS`
- `LOOPIN_RELEASE_STORE_PASSWORD`
- `LOOPIN_RELEASE_KEY_PASSWORD`

`.jks`, `.keystore` e `.p12` são ignorados. Sem essas quatro variáveis, `assembleRelease` produz APK unsigned e ele não pode ser publicado. O fingerprint público é derivado por `apksigner`; a chave privada jamais entra no repositório.

LAB/DEBUG pode usar uma chave temporária para provar upgrade no LDPlayer. Essa identidade não é a âncora RELEASE/STABLE de produção. A fingerprint definitiva deve ser registrada somente quando a chave operacional for provisionada em cofre seguro.

Fingerprint pública observada na prova LAB/DEBUG: `12947e8327871ffade55f924d4280ed67aa5a63f1931b1654ed67d7f0b4298b7` (`Android Debug`). Ela é deliberadamente inadequada para produção.

## Endpoint do Player

`player-update` exige `Authorization: Bearer <device credential>`, resolve device pareado e nunca aceita device_id do body.

- `action=check`: recebe versionCode atual; devolve 204 se não houver versão superior publicada no canal autoritativo, ou metadata limitada sem storage path/signed URL.
- `action=download`: revalida release PUBLISHED e canal e devolve signed URL privada de 5 minutos.

Credencial e URL assinada não são persistidas nem logadas.

## Android

`AuthenticatedPlayerUpdateSource` usa `HttpURLConnection`. `DeviceUpdateScheduler` é JobScheduler one-shot, requer rede e agenda janela normal aproximada de seis horas. Não existe thread residente, foreground service, WebSocket ou polling rápido. `CHECK_UPDATE` apenas antecipa o job e conclui o comando imediatamente.

`PlayerUpdateManager` existente continua autoritativo:

1. bloqueia versionCode igual/inferior e canal divergente;
2. reserva espaço antes de rede;
3. baixa em streaming para `.apk.part` com limite de tamanho;
4. faz fsync;
5. verifica tamanho e SHA-256;
6. `AndroidApkSignatureVerifier` confere package fixo, versionCode do archive, certificado informado e igualdade com o certificado do app instalado;
7. renomeia atomicamente para `.apk` apenas após tudo passar;
8. mantém o arquivo validado em `READY_TO_INSTALL`.

`.part` é removido em erro/startup e nunca chega ao installer. APK preparado é revalidado ao ser reutilizado. Falha não toca app atual, pairing, playlist nem cache. O instalador desta fase declara capacidade `INTERACTIVE`; não presume Device Owner ou privilégio de firmware.

Estados: `CHECKING`, `UP_TO_DATE`, `UPDATE_AVAILABLE`, `DOWNLOADING`, `VALIDATING`, `READY_TO_INSTALL`, `FAILED`, `INSUFFICIENT_STORAGE` e estados históricos de instalação. A telemetria acrescenta canal, versionCode atual, estado, versão disponível/preparada, último check/erro e capacidade.

## Admin e segurança

A navegação ganha Atualizações somente para `release_admin`. Mostra canal, versão, status, tamanho, SHA reduzido e data. Permite upload DRAFT, publicação após inspeção e revogação. Telas pareadas ganham canal STABLE/BETA e `CHECK_UPDATE`. RLS não permite escrita direta em releases; publicação global exige role real no backend.

## Testes e validação

Testes JVM cobrem versão superior/igual/inferior, assinatura, SHA, interrupção, `.part`, espaço, installer indisponível e novos bloqueios de package/certificado/CHECK_UPDATE. Vitest cobre a quarta área, seleção de rota e validações de upload/publicação. A suíte integral e os números finais ficam no relatório do commit.

O Supabase isolado recebeu as migrations, bucket privado e Edge Functions. Foram conferidos deploy e rejeição sem Authorization. Ensaios laboratoriais usam dados/chave temporários e são removidos ao final.

LDPlayer é integração, não certificação MXQ. A prova com duas builds assinadas pela mesma chave temporária deve confirmar READY e, quando a UI do Package Installer puder ser operada, upgrade interativo preservando dados. Wrong signature/package devem falhar antes de READY.

Na validação executada nesta fase, o APK v13 iniciou no LDPlayer sem crash/ANR/OOM e uma v14 release assinada pela mesma chave LAB foi aceita pelo Android com `adb install -r`, preservando o package e iniciando como `2.0.0-phase13-lab14`. O aparelho foi devolvido ao APK debug v13 após limpeza dos dados laboratoriais. A validação unitária rejeitou package, certificado, SHA e tamanho incorretos antes de READY. A cadeia remota completa com DRAFT inspecionado e download pelo device depende do provisionamento de um usuário `release_admin` e do segredo do inspetor; esses segredos operacionais não foram inventados nem versionados. Portanto essa prova administrativa permanece explicitamente pendente, sem alterar a segurança do fluxo.

## Limitações e Fase 14

- Não há instalação silenciosa.
- Device Owner/privileged não são afirmados.
- A chave RELEASE/STABLE definitiva precisa ser provisionada fora do Git.
- Cancelamento imediato de download já iniciado após revogação depende do próximo contato; nenhum novo download revogado é autorizado.
- Rollback automático, root, shell, firmware, MDM, reboot e restart remoto permanecem fora do escopo.
- Certificação e soak test: **PENDENTE — MXQ FÍSICA**.

A Fase 14 poderá tratar instalação gerenciada e recuperação pós-update após evidência real da MXQ.
