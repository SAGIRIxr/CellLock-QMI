param([Parameter(Mandatory=$true)][string]$Ndk, [switch]$BuildTests)
$ErrorActionPreference = 'Stop'
$compiler = Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android26-clang.cmd'
if (-not (Test-Path -LiteralPath $compiler)) { throw 'Android NDK Clang not found at the supplied path.' }
$nativeSource = Join-Path $PSScriptRoot 'native'
$nativeOutput = Join-Path $PSScriptRoot 'app/src/main/jniLibs/arm64-v8a'
New-Item -ItemType Directory -Force -Path $nativeOutput | Out-Null
& $compiler -std=c11 -O2 -Wall -Wextra -Werror -fPIE -pie '-Wl,-z,relro,-z,now' -o (Join-Path $nativeOutput 'libcelllock_qmi.so') (Join-Path $nativeSource 'celllock_qmi.c')
if ($LASTEXITCODE -ne 0) { throw "QMI build failed: $LASTEXITCODE" }
if ($BuildTests) {
    & $compiler -std=c11 -O2 -Wall -Wextra -Werror -fPIE -pie '-Wl,-z,relro,-z,now' -o (Join-Path $nativeSource 'test_qmi') (Join-Path $nativeSource 'test_qmi.c')
    if ($LASTEXITCODE -ne 0) { throw "Protocol test build failed: $LASTEXITCODE" }
}
