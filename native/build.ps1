param([string]$Ndk = $env:ANDROID_NDK_HOME)
if ([string]::IsNullOrWhiteSpace($Ndk)) { throw 'Supply -Ndk with an Android NDK path, or set ANDROID_NDK_HOME.' }
$ErrorActionPreference = 'Stop'
$compiler = Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android26-clang.cmd'
& $compiler -std=c11 -O2 -Wall -Wextra -Werror -fPIE -pie '-Wl,-z,relro,-z,now' -o (Join-Path $PSScriptRoot 'libcelllock_probe.so') (Join-Path $PSScriptRoot 'celllock_probe.c') -ldl
if ($LASTEXITCODE -ne 0) { throw "Native probe compile failed: $LASTEXITCODE" }
& $compiler -std=c11 -O2 -Wall -Wextra -Werror -fPIE -pie '-Wl,-z,relro,-z,now' -o (Join-Path $PSScriptRoot 'libcelllock_qmi.so') (Join-Path $PSScriptRoot 'celllock_qmi.c')
if ($LASTEXITCODE -ne 0) { throw "QMI CLI compile failed: $LASTEXITCODE" }
& $compiler -std=c11 -O2 -Wall -Wextra -Werror -fPIE -pie '-Wl,-z,relro,-z,now' -o (Join-Path $PSScriptRoot 'test_qmi') (Join-Path $PSScriptRoot 'test_qmi.c')
if ($LASTEXITCODE -ne 0) { throw "Protocol tests compile failed: $LASTEXITCODE" }
