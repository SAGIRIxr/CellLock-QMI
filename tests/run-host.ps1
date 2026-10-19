param(
    [Parameter(Mandatory=$true)][string]$AndroidJar,
    [Parameter(Mandatory=$true)][string]$JsonJar,
    [string]$Jdk = $env:JAVA_HOME,
    [string]$Captures
)
$ErrorActionPreference = 'Stop'
foreach($file in @($AndroidJar,$JsonJar)){if(-not(Test-Path -LiteralPath $file)){throw "Missing test runtime: $file"}}
$output = Join-Path $PSScriptRoot 'host-classes'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$source = Join-Path $PSScriptRoot '../app/src/main/java/com/celllock/app'
$inputs = @('Bands','Models','InputValidator','SignalHistory','SimStatus','Snapshots','SavedRecords','SavedCells','LockPlan','BandDefaults','SimInfo','RadioCell','MeasurementDecoder','DiagnosticCells','RadioReader','RootShell','RootPhone') | ForEach-Object { Join-Path $source "$_.java" }
$tests = @('InputValidatorTest','SignalHistoryTest','SimStatusTest','SnapshotsTest','SavedCellsTest','MeasurementDecoderTest','RadioCellTest','ReadingPolicyTest','NasVerificationTest','BandDefaultsTest')
$inputs += $tests | ForEach-Object { Join-Path $PSScriptRoot "$_.java" }
$inputs += Join-Path $PSScriptRoot 'host/android/os/SystemClock.java'
& (Join-Path $Jdk 'bin/javac.exe') -encoding UTF-8 -cp "$JsonJar;$AndroidJar" -d $output @inputs
if($LASTEXITCODE -ne 0){throw 'Host tests did not compile'}
foreach($name in $tests){
    $arguments=@()
    if($name -eq 'MeasurementDecoderTest' -and $Captures){$arguments += $Captures}
    if($name -eq 'NasVerificationTest'){
        $arguments += Join-Path $PSScriptRoot 'fixtures/nas-verified.redacted.jsonl'
        $arguments += Join-Path $PSScriptRoot 'fixtures/nas-n28-conflict.redacted.json'
    }
    & (Join-Path $Jdk 'bin/java.exe') -cp "$output;$JsonJar;$AndroidJar" "com.celllock.app.$name" @arguments
    if($LASTEXITCODE -ne 0){throw "$name failed"}
}
