@echo off
rem Extrai o codigo do Hi-Link de dentro do celular virtual do Android Studio.
rem Antes: celular virtual LIGADO e app Hi-Link ABERTO nele.
set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
if not exist "%ADB%" (
  echo Nao achei o adb. Confira se o Android Studio terminou de instalar.
  pause
  exit /b 1
)
cd /d "%~dp0"
echo [1/4] Pedindo acesso root ao celular virtual...
"%ADB%" root
timeout /t 3 >nul
"%ADB%" wait-for-device
echo [2/4] Enviando o script...
"%ADB%" push dump.sh /data/local/tmp/dump.sh
echo [3/4] Copiando o codigo do Hi-Link (pode levar 1 minuto)...
"%ADB%" shell sh /data/local/tmp/dump.sh
echo [4/4] Trazendo o arquivo para esta pasta...
"%ADB%" pull /data/local/tmp/hl.tgz hl.tgz
echo.
echo Pronto! Mande o arquivo hl.tgz desta pasta para o Claude.
pause
