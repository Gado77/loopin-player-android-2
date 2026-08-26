# Ambiente de testes LDPlayer

## Objetivo

Usar o LDPlayer como laboratório automatizado e isolado para o Loopin Player 2.0. Nenhuma ação deste procedimento modifica o Loopin Admin, o Player de produção ou a TV Box MXQ.

## Ambiente validado em 18/08/2026

- LDPlayer 9.5.31.0, instância `LDPlayer` (índice 0).
- ADB habilitado na configuração do emulador.
- Serial ADB: `emulator-5554` (encaminhamento local na porta 5555; servidor ADB na porta 5037).
- Fabricante/modelo reportado: OnePlus PJD110.
- Android 9, API 28.
- ABIs: x86_64, x86, arm64-v8a, armeabi-v7a e armeabi.
- 4 CPUs virtuais.
- Tela física reportada: 900 x 1600, densidade 240 dpi; o Player opera em paisagem, 1600 x 900.
- Memória total reportada: 3.076.172 kB (aproximadamente 3 GB).

O ADB utilizado está em `C:\LDPlayer\LDPlayer9\adb.exe`. Os scripts também procuram o ADB do toolchain local do Player 2.0 e aceitam a variável `LOOPIN_ADB`.

## Uso

Os comandos estão em `scripts\adb`. Como a política local do PowerShell bloqueia scripts não assinados, execute-os com `-ExecutionPolicy Bypass`; isso vale apenas para o processo iniciado e não muda a política permanente:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\adb\detect.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\adb\install-debug.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\adb\start-player.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\adb\collect-diagnostics.ps1
```

O pacote testado é `com.loopin.player2`, e a activity é `com.loopin.player2/.MainActivity`. O APK esperado fica em `app\build\outputs\apk\debug\app-debug.apk`.

## Offline

Não use `adb shell svc wifi disable` no LDPlayer: o transporte ADB do emulador depende da interface de rede virtual e pode ficar inacessível. Use `ldconsole.exe action --index 0 --key call.network --value offline` e restaure com o valor `connect`.

## Limitações

- O LDPlayer valida lifecycle, persistência, reprodução e automação, mas não substitui testes no chipset e nos decodificadores reais da MXQ.
- A declaração de launcher HOME do Player faz o Android exibir o seletor de launcher quando HOME é acionado e nenhum padrão foi escolhido.
- `dumpsys media.codec` não está disponível nesta imagem. A enumeração veio dos XMLs do sistema e o codec efetivamente escolhido foi confirmado no logcat.
