param([string]$BindAddress)
$ErrorActionPreference = 'Stop'
if (-not $BindAddress) {
    $candidates = @(Get-NetIPConfiguration | Where-Object {
        $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq 'Up' -and $_.NetAdapter.HardwareInterface
    })
    if ($candidates.Count -eq 0) { throw 'No se encontró una red física conectada. Usa -BindAddress con la IPv4 de tu Wi-Fi.' }
    $selected = $candidates | Sort-Object @{Expression={
        (Get-NetIPInterface -InterfaceIndex $_.InterfaceIndex -AddressFamily IPv4).InterfaceMetric
    }} | Select-Object -First 1
    $BindAddress = @($selected.IPv4Address.IPAddress)[0]
    Write-Host "Red seleccionada: $($selected.InterfaceAlias) ($BindAddress)"
}
$address = $null
if (-not [Net.IPAddress]::TryParse($BindAddress, [ref]$address) -or $address.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) { throw 'Usa la IPv4 de la red Wi-Fi del PC.' }
if (-not (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -eq $BindAddress })) { throw 'La dirección no pertenece a este PC.' }
if ([Net.IPAddress]::IsLoopback($address) -or $BindAddress -eq '0.0.0.0' -or $BindAddress.StartsWith('169.254.')) { throw 'Elige una dirección de la red local accesible desde el celular.' }

# Consultar tags no carga ni ejecuta el modelo.
function Get-OllamaModels {
    try { return Invoke-RestMethod -Uri 'http://127.0.0.1:11434/api/tags' -TimeoutSec 2 }
    catch { return $null }
}
$models = Get-OllamaModels
if ($null -eq $models) {
    $ollama = (Get-Command ollama -ErrorAction SilentlyContinue).Source
    if (-not $ollama) {
        $ollama = Join-Path $env:LOCALAPPDATA 'Programs\Ollama\ollama.exe'
        if (-not (Test-Path -LiteralPath $ollama)) { throw 'No se encontró Ollama instalado en este PC.' }
    }
    $previousHost = $env:OLLAMA_HOST
    try {
        $env:OLLAMA_HOST = '127.0.0.1:11434'
        $ollamaProcess = Start-Process -FilePath $ollama -ArgumentList 'serve' -WindowStyle Hidden -PassThru
    } finally {
        if ($null -eq $previousHost) { Remove-Item Env:OLLAMA_HOST -ErrorAction SilentlyContinue }
        else { $env:OLLAMA_HOST = $previousHost }
    }
    Write-Host 'Iniciando Ollama…'
    $deadline = (Get-Date).AddSeconds(30)
    do {
        Start-Sleep -Milliseconds 500
        $models = Get-OllamaModels
    } while ($null -eq $models -and (Get-Date) -lt $deadline -and -not $ollamaProcess.HasExited)
    if ($null -eq $models) { throw 'Ollama no respondió. Comprueba que el puerto 11434 esté disponible.' }
}
Write-Host 'Ollama disponible (sin ejecutar reconocimiento).'
if (-not (@($models.models | ForEach-Object { $_.name }) -contains 'qwen3-vl:4b')) {
    Write-Warning 'Falta qwen3-vl:4b. Usuarios y amigos funcionarán; para reconocer instala el modelo con: ollama pull qwen3-vl:4b'
}

$qrUrl = 'http://127.0.0.1:8444'
$existing = $null
try { $existing = Invoke-WebRequest -Uri $qrUrl -UseBasicParsing -TimeoutSec 2 } catch { }
if ($existing -and $existing.Headers['X-Pokedex-Server'] -eq '1') {
    if ($existing.Headers['X-Pokedex-Address'] -ne "https://${BindAddress}:8443") {
        throw 'El servidor ya está iniciado con otra IP. Detén esa terminal con Ctrl+C y vuelve a ejecutar este comando.'
    }
    Write-Host 'El backend ya está iniciado. Abriendo el QR…'
    Start-Process $qrUrl
    return
}
$occupied = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in 8443,8444 })
if ($occupied.Count -gt 0) { throw 'Los puertos 8443/8444 están ocupados. Detén el servidor anterior antes de iniciar este backend.' }
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    & '.\gradlew.bat' :server:installDist --console=plain --max-workers=1
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo compilar el servidor.' }
    $private = Join-Path $env:LOCALAPPDATA 'PokedexIoT'
    New-Item -ItemType Directory -Force -Path $private | Out-Null
    $properties = Join-Path $private 'connection.properties'
    $store = Join-Path $private 'server.p12'
    if (-not (Test-Path -LiteralPath $properties)) {
        $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
        $bytes = New-Object byte[] 32
        $rng.GetBytes($bytes)
        $token = ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
        $rng.GetBytes($bytes)
        $password = ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
        $rng.Dispose()
        Set-Content -LiteralPath $properties -Value "token=$token`npassword=$password" -Encoding ascii
    }
    $passwordLine = Get-Content -LiteralPath $properties | Where-Object { $_ -like 'password=*' }
    $env:POKEDEX_STORE_PASSWORD = $passwordLine.Substring(9)
    if (-not (Test-Path -LiteralPath $store)) {
        & keytool -genkeypair -alias pokedex -keyalg RSA -keysize 2048 -validity 365 -storetype PKCS12 -keystore $store -storepass:env POKEDEX_STORE_PASSWORD -dname 'CN=Pokedex Local' -ext 'SAN=dns:localhost,ip:127.0.0.1'
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo crear el certificado local.' }
    }
    Write-Host 'Iniciando servidor y SQLite. El navegador abrirá el QR cuando esté listo.'
    Write-Host 'Mantén esta terminal abierta. Ctrl+C detiene el servidor; Ollama queda disponible.'
    & '.\server\build\install\server\bin\server.bat' $store $properties $BindAddress '--open-qr'
    if ($LASTEXITCODE -ne 0) { throw "El servidor terminó con error ($LASTEXITCODE)." }
} finally {
    Remove-Item Env:POKEDEX_STORE_PASSWORD -ErrorAction SilentlyContinue
    Pop-Location
}
