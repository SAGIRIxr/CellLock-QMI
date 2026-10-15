param([Parameter(Mandatory=$true)][string]$Ndk, [switch]$BuildTests)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'native/build.ps1') -Ndk $Ndk -Output (Join-Path $PSScriptRoot 'app/src/main/jniLibs/arm64-v8a') -BuildTests:$BuildTests -BuildProbe:$false
