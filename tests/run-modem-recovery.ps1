param(
    [Parameter(Mandatory=$true)][string]$AndroidJar,
    [Parameter(Mandatory=$true)][string]$JsonJar,
    [string]$Jdk=$env:JAVA_HOME
)
$ErrorActionPreference='Stop'
$classes=Join-Path $PSScriptRoot '../app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
$output=Join-Path $PSScriptRoot 'modem-recovery-classes'
foreach($path in @($AndroidJar,$JsonJar,$classes)){if(-not(Test-Path -LiteralPath $path)){throw "Missing test runtime: $path; build Debug classes first"}}
New-Item -ItemType Directory -Force $output | Out-Null
& "$Jdk/bin/javac.exe" -encoding UTF-8 -cp "$classes;$JsonJar;$AndroidJar" -d $output "$PSScriptRoot/UiFlowTest.java" "$PSScriptRoot/ModemFlowTest.java" "$PSScriptRoot/ModemRecoveryTest.java"
if($LASTEXITCODE -ne 0){throw 'Modem cleanup checks did not compile'}
& "$Jdk/bin/java.exe" -cp "$output;$classes;$JsonJar;$AndroidJar" com.celllock.app.ModemRecoveryTest
if($LASTEXITCODE -ne 0){throw 'Modem cleanup checks failed'}
