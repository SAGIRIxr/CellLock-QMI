param(
    [string]$Ndk = $env:ANDROID_NDK_HOME,
    [string]$Output = $PSScriptRoot,
    [switch]$BuildTests
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Ndk)) { throw 'Supply -Ndk with an Android NDK path, or set ANDROID_NDK_HOME.' }
if ($IsLinux) { $hostTag = 'linux-x86_64'; $suffix = '' }
elseif ($IsMacOS) { $hostTag = 'darwin-x86_64'; $suffix = '' }
else { $hostTag = 'windows-x86_64'; $suffix = '.cmd' }
$compiler = Join-Path $Ndk "toolchains/llvm/prebuilt/$hostTag/bin/aarch64-linux-android26-clang$suffix"
if (-not (Test-Path -LiteralPath $compiler)) { throw "Android NDK Clang not found: $compiler" }
New-Item -ItemType Directory -Force -Path $Output | Out-Null
$targets = @('celllock_qmi', 'celllock_read', 'celllock_diag', 'celllock_efs')
if ($BuildTests) { $targets += @('test_qmi', 'test_diag', 'test_read', 'test_efs') }
foreach ($target in $targets) {
    $destination = if ($target.StartsWith('test_')) { Join-Path $PSScriptRoot $target } else { Join-Path $Output "lib$target.so" }
    $arguments = @('-std=c11', '-O2', '-Wall', '-Wextra', '-Werror', '-fPIE', '-pie', '-Wl,-z,relro,-z,now', '-s', '-o', $destination, (Join-Path $PSScriptRoot "$target.c"))
    if ($target -in @('celllock_diag', 'celllock_efs', 'test_diag')) { $arguments += '-ldl' }
    & $compiler @arguments
    if ($LASTEXITCODE -ne 0) { throw "$target build failed: $LASTEXITCODE" }
}
