param([Parameter(Mandatory=$true)][string]$JsonJar, [string]$Jdk = $env:JAVA_HOME, [string]$Cc = 'gcc')
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $JsonJar)) { throw "Missing test runtime: $JsonJar" }
$JsonJar = (Resolve-Path -LiteralPath $JsonJar).Path
$testDir = Join-Path ([IO.Path]::GetTempPath()) ('celllock-root-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testDir | Out-Null
$previousPath = $env:PATH
$previousMarker = $env:CELLLOCK_ROOT_TEST
$previousShell = $env:CELLLOCK_TEST_SHELL
function Check-Exit { if ($LASTEXITCODE -ne 0) { throw "RootShell test failed: $LASTEXITCODE" } }
try {
    @'
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <process.h>
int main(int argc, char **argv) {
    FILE *starts = fopen("su-starts.txt", "a");
    if (!starts) return 2;
    fputs("start\n", starts); fclose(starts);
    FILE *reject = fopen("su-reject-once", "r");
    if (reject) { fclose(reject); remove("su-reject-once"); fputs("su: permission denied\n", stderr); return 1; }
    reject = fopen("su-reject-always", "r");
    if (reject) { fclose(reject); fputs("su: permission denied\n", stderr); return 1; }
    const char *shell = getenv("CELLLOCK_TEST_SHELL");
    _putenv("BASH_FUNC_id%%=() { printf '0\\n'; }");
    if (argc == 1) return shell ? (int)_spawnl(_P_WAIT, shell, "sh", "-s", NULL) : 2;
    if (argc != 3 || strcmp(argv[1], "-c")) return 2;
    return shell ? (int)_spawnl(_P_WAIT, shell, "sh", "-c", argv[2], NULL) : 2;
}
'@ | Set-Content -LiteralPath "$testDir/su.c" -Encoding ascii
    & $Cc -std=c11 -Wall -Wextra -Werror "$testDir/su.c" -o "$testDir/su.exe"; Check-Exit
    & "$Jdk/bin/javac.exe" -encoding UTF-8 -cp $JsonJar -d $testDir "$PSScriptRoot/../app/src/main/java/com/celllock/app/RootShell.java" "$PSScriptRoot/RootShellTest.java"; Check-Exit
    $env:PATH = "$testDir;$previousPath"
    $env:CELLLOCK_ROOT_TEST = '1'
    $env:CELLLOCK_TEST_SHELL = (Get-Command sh.exe -ErrorAction Stop).Source
    Push-Location $testDir
    try {
        foreach ($scenario in @('read-retry', 'auth-read-retry', 'write-retry', 'write-once', 'auth-denied', 'retry-limit', 'reuse', 'reconnect', 'denied', 'overflow', 'logs', 'no_log', 'library', 'cleanup', 'watchdog', 'signal', 'incomplete', 'missing')) {
            & "$Jdk/bin/java.exe" -cp "$testDir;$JsonJar" com.celllock.app.RootShellTest $scenario; Check-Exit
        }
    } finally { Pop-Location }
} finally {
    $env:PATH = $previousPath
    $env:CELLLOCK_ROOT_TEST = $previousMarker
    $env:CELLLOCK_TEST_SHELL = $previousShell
    if ([IO.Path]::GetFullPath($testDir).StartsWith([IO.Path]::GetTempPath(), [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $testDir -Recurse -Force
    }
}
