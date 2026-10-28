param([string]$Python = 'python')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runDirectory = Join-Path $projectRoot ('artifacts\validation\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
Push-Location $projectRoot
try {
    # No connectedAndroidTest, emulador, servidor personal ni llamadas a Ollama.
    & .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin :server:test --rerun-tasks --max-workers=1 --console=plain 2>&1 | Tee-Object -FilePath (Join-Path $runDirectory 'gradle.log')
    if ($LASTEXITCODE -ne 0) { throw 'La validación Gradle falló. Revisa gradle.log.' }
    & $Python (Join-Path $PSScriptRoot 'Export-Validation.py') --output $runDirectory
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo exportar una validación correcta.' }
    Write-Host "Evidencia guardada en $runDirectory"
} finally { Pop-Location }
