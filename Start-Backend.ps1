param([string]$BindAddress)
# Desde la carpeta del proyecto: .\Start-Backend.ps1
& (Join-Path $PSScriptRoot 'tools\Start-PokedexPC.ps1') @PSBoundParameters
