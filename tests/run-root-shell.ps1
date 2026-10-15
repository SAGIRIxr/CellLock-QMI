param([Parameter(Mandatory=$true)][string]$JsonJar, [string]$Jdk = $env:JAVA_HOME, [string]$Cc = 'gcc')
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $JsonJar)) { throw "Missing test runtime: $JsonJar" }
$JsonJar = (Resolve-Path -LiteralPath $JsonJar).Path
$testDir = Join-Path ([IO.Path]::GetTempPath()) ('celllock-root-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testDir | Out-Null
$previousPath = $env:PATH
$previousMarker = $env:CELLLOCK_ROOT_TEST
function Check-Exit { if ($LASTEXITCODE -ne 0) { throw "RootShell test failed: $LASTEXITCODE" } }
try {
    @'
#include <stdio.h>
#include <string.h>
int main(int argc, char **argv) {
    if (argc != 3 || strcmp(argv[1], "-c")) return 2;
    const char *command = argv[2];
    if (!strcmp(command, "write")) { puts("FAKE_WRITE_OK"); return 0; }
    if (!strcmp(command, "watchdog") || !strcmp(command, "signal")) {
        fprintf(stderr, "vendor warning before %s exit\n", command);
        return !strcmp(command, "watchdog") ? 124 : 137;
    }
    if (!strcmp(command, "logs")) { puts("{\"logs\":[]}"); return 0; }
    if (!strcmp(command, "incomplete")) { puts("{\"incomplete\":true}"); return 0; }
    if (!strcmp(command, "missing")) { puts("vendor warning: missing completion"); return 1; }
    printf("{\"error\":\"%s\"}\n", command);
    return 1;
}
'@ | Set-Content -LiteralPath "$testDir/su.c" -Encoding ascii
    & $Cc -std=c11 -Wall -Wextra -Werror "$testDir/su.c" -o "$testDir/su.exe"; Check-Exit
    & "$Jdk/bin/javac.exe" -encoding UTF-8 -cp $JsonJar -d $testDir "$PSScriptRoot/../app/src/main/java/com/celllock/app/RootShell.java" "$PSScriptRoot/RootShellTest.java"; Check-Exit
    $env:PATH = "$testDir;$previousPath"
    $env:CELLLOCK_ROOT_TEST = '1'
    Push-Location $testDir
    try {
        foreach ($scenario in @('logs', 'no_log', 'library', 'cleanup', 'watchdog', 'signal', 'incomplete', 'missing')) {
            & "$Jdk/bin/java.exe" -cp "$testDir;$JsonJar" com.celllock.app.RootShellTest $scenario; Check-Exit
        }
    } finally { Pop-Location }
} finally {
    $env:PATH = $previousPath
    $env:CELLLOCK_ROOT_TEST = $previousMarker
    if ([IO.Path]::GetFullPath($testDir).StartsWith([IO.Path]::GetTempPath(), [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $testDir -Recurse -Force
    }
}
