# Automação ADB do laboratório LDPlayer

Execute os scripts com PowerShell sem alterar permanentemente a política do Windows:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\adb\detect.ps1
```

Scripts disponíveis:

- `detect.ps1`: detecta exatamente um emulador LDPlayer online.
- `install-debug.ps1`: instala o APK debug em um ambiente limpo.
- `update-debug.ps1`: atualiza o APK preservando os dados locais.
- `start-player.ps1` / `stop-player.ps1`: inicia ou encerra o Player.
- `is-player-alive.ps1`: consulta o PID do processo.
- `collect-logcat.ps1`: salva o logcat.
- `collect-diagnostics.ps1`: coleta propriedades, tela, memória, CPU, atividades, pacote, codecs, processos e logcat.
- `generate-report.ps1`: gera um resumo Markdown do estado atual.
- `clear-player-data.ps1 -ConfirmClear`: apaga dados locais somente com confirmação explícita; use apenas em testes intencionais.

Para selecionar outro alvo, defina `LOOPIN_DEVICE_SERIAL`. Para usar outro ADB, defina `LOOPIN_ADB`.

O modo offline seguro do LDPlayer deve ser controlado pelo console do emulador, pois desligar o Wi-Fi com `adb shell svc wifi disable` também interrompe o transporte ADB virtual:

```powershell
& 'C:\LDPlayer\LDPlayer9\ldconsole.exe' action --index 0 --key call.network --value offline
& 'C:\LDPlayer\LDPlayer9\ldconsole.exe' action --index 0 --key call.network --value connect
```
