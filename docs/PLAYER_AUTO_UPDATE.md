# Fundação do auto-update do Loopin Player

## Escopo implementado

Foram criados contratos independentes:

- `PlayerUpdateInfo`: versão, URL, tamanho, SHA-256, canal e notas;
- `PlayerUpdateSource`: consulta abstrata de versão;
- `PlayerUpdateManager`: comparação, preparação e entrega ao instalador;
- `PlayerInstaller`: fronteira para instalação normal ou gerenciada;
- `ApkSignatureVerifier`: validação do pacote/certificado;
- `AndroidApkSignatureVerifier`: compara package name e certificado do APK com o aplicativo instalado.

Não há source de produção configurado e nenhuma instalação é iniciada nesta fase.

## Segurança

O fluxo preparado é:

```text
metadata válida
    ↓
versão remota > instalada
    ↓
espaço suficiente
    ↓
download para <sha256>.apk.part
    ↓
tamanho + SHA-256 + package name + certificado
    ↓
rename para <sha256>.apk
    ↓
PlayerInstaller
```

O APK é transmitido em buffer de 8 KiB e nunca carregado integralmente na RAM. Download interrompido ou maior que o esperado remove `.part`. O APK preparado não é apagado quando a instalação está indisponível ou falha, e o aplicativo instalado não é manipulado antes de todas as validações.

Uma versão igual ou menor é `UpToDate`; downgrade automático não é permitido.

## Instalação normal no Android/Android TV

Um aplicativo comum não deve presumir instalação silenciosa. Em Android 8/API 26 ou superior, o usuário precisa autorizar individualmente a fonte em “Instalar apps desconhecidos”, e o instalador deve consultar `canRequestPackageInstalls()`. Em versões anteriores, a política usa a configuração global de fontes desconhecidas. Consulte a documentação oficial de [PackageManager.canRequestPackageInstalls](https://developer.android.com/reference/android/content/pm/PackageManager.html#canRequestPackageInstalls()) e [publicação/unknown apps](https://developer.android.com/studio/publish/#publishing-unknown).

Uma sessão do `PackageInstaller` pode retornar `STATUS_PENDING_USER_ACTION`; o aplicativo precisa apresentar a UI/intent fornecida pelo sistema. Os possíveis estados e falhas estão em [PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller.html). Em uma TV Box sem monitor ou controle disponível, essa confirmação pode tornar o fluxo comum impraticável.

O projeto ainda não declara `REQUEST_INSTALL_PACKAGES`, pois esta fase não instala APKs.

## Device Owner, MDM e dispositivos dedicados

Segundo a API oficial, sessões podem concluir sem intervenção quando o instalador é Device Owner ou Profile Owner afiliado. Apps comuns continuam preparados para `STATUS_PENDING_USER_ACTION`. Consulte [PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller.html) e o guia de [dedicated devices](https://developer.android.com/work/dpc/dedicated-devices/cookbook#install_apk).

Um MDM precisa provisionar um DPC/Device Owner ou usar capacidades documentadas pelo fabricante. Instalar o Player normalmente por ADB não o transforma em Device Owner nem dá permissão silenciosa. Não foram implementados root, bootloader unlock ou contorno de segurança.

Android 12/API 31 adicionou `setRequireUserAction`, mas a dispensa de interação depende de condições como installer/update owner, permissões e target SDK; os requisitos avançam entre versões. O instalador sempre deve tratar `STATUS_PENDING_USER_ACTION`. Consulte [SessionParams.setRequireUserAction](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int)).

## Assinatura

Além de tamanho e SHA-256, uma atualização precisa manter package name e certificado compatível com o aplicativo instalado. `AndroidApkSignatureVerifier` compara os certificados antes da fronteira de instalação; o próprio Android volta a impor compatibilidade durante a atualização.

Rotação planejada de chave exigirá política explícita compatível com o histórico de signing certificates. Não é aceita nesta fase apenas porque uma URL ou checksum está correto.

## Canais

`releaseChannel` aceita identificadores como `stable` e `beta`. A seleção é argumento de `PlayerUpdateSource`; não há troca automática de canal nem UI.

## Testes

Há cobertura para versão igual, nova e inferior; assinatura inválida; checksum inválido; interrupção; falta de espaço; instalador indisponível e erro de instalação.

## Próximos passos

1. escolher distribuição normal com ação do usuário ou provisionamento Device Owner/MDM;
2. definir endpoint assinado/autenticado de metadata;
3. implementar `PlayerUpdateSource` concreto;
4. decidir política de rotação de certificado;
5. implementar um `PlayerInstaller` compatível com o modo de gestão escolhido;
6. validar instalação e retorno após reboot.

Instalação gerenciada e silenciosa na MXQ: **PENDENTE — VALIDAÇÃO EM MXQ**.
