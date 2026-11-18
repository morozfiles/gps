@echo off
setlocal EnableExtensions DisableDelayedExpansion
rem SAFE BACKUP ONLY. This script never uninstalls apps and never deletes phone data.
rem Run it in Windows CMD with Android platform-tools (adb.exe) available.
rem USB debugging must be enabled. Accept the computer's USB authorization.
rem Old app must still be INSTALLED and be a debuggable APK.
where adb.exe >nul 2>nul
if errorlevel 1 (
  echo adb.exe not found. Install official Android platform-tools, then run again.
  pause
  exit /b 1
)
set "PACKAGE=ua.obdgps.nav"
set "OUT=%~dp0UkraineOfflineBackup"
if not exist "%OUT%" mkdir "%OUT%"
echo.
echo Device status:
adb devices
echo.
echo Checking access to the still-installed OLD Android application...
adb shell run-as %PACKAGE% ls files/offline-vectors/ >nul 2>nul
if errorlevel 1 (
  echo Cannot access the app's private files via run-as.
  echo Check USB debugging, old app installation and its debug build.
  echo DO NOT UNINSTALL THE OLD APP.
  pause
  exit /b 2
)
echo.
echo BACKUP: original data is NOT modified.
if exist "%OUT%\ukraine.map" (
  echo Map backup exists, not overwriting: %OUT%\ukraine.map
) else (
  echo Backing up map. Large files may take a while.
  adb exec-out run-as %PACKAGE% cat files/offline-vectors/selected.map > "%OUT%\ukraine.map"
  if errorlevel 1 (
    del "%OUT%\ukraine.map" >nul 2>nul
    echo Could not copy map. Is selected.map saved in the old app?
  )
)
if exist "%OUT%\ukraine.poi" (
  echo POI backup exists, not overwriting: %OUT%\ukraine.poi
) else (
  echo Backing up address database...
  adb exec-out run-as %PACKAGE% cat files/offline-vectors/selected.poi > "%OUT%\ukraine.poi"
  if errorlevel 1 (
    del "%OUT%\ukraine.poi" >nul 2>nul
    echo Could not copy POI database. Is selected.poi saved in the old app?
  )
)
echo.
echo Files:
dir "%OUT%"
echo.
echo IMPORTANT:
echo 1. Check that BOTH backups have plausible nonzero sizes.
echo 2. Keep them on your PC until confirmed usable.
echo 3. After installing the new APK, transfer these files to Android Download
echo    and import them using Offline Maps - Choose Ukraine .map / .poi.
echo 4. NEVER uninstall the old APK before the backup is verified.
echo.
pause
endlocal
