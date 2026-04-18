param(
    [string]$PythonExe = "python",
    [string]$OutputName = "lyricsync-sidecar"
)

$ErrorActionPreference = "Stop"

Write-Host "[LyricSync] Installing build dependency: pyinstaller"
& $PythonExe -m pip install --upgrade pyinstaller

Write-Host "[LyricSync] Building sidecar executable"
& $PythonExe -m PyInstaller `
  --onefile `
  --name $OutputName `
  --clean `
  --hidden-import "pyncm.apis" `
  --hidden-import "winsdk.windows.media.control" `
  "server.py"

Write-Host "[LyricSync] Build complete: dist/$OutputName.exe"
