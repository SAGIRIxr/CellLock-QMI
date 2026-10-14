param([Parameter(Mandatory=$true)][string]$Sdk, [string]$Jdk=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
$tools=Join-Path $Sdk 'build-tools/34.0.0'
$android=Join-Path $Sdk 'platforms/android-35/android.jar'
$classes=Join-Path $PSScriptRoot '../app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
$output=Join-Path $PSScriptRoot 'ui-test/build'
$testClasses=Join-Path $PSScriptRoot 'ui-classes'
New-Item -ItemType Directory -Force $output,$testClasses | Out-Null
function Check-Exit { if($LASTEXITCODE -ne 0){throw "UI test build/ADB failed: $LASTEXITCODE"} }
& "$Jdk/bin/javac.exe" -encoding UTF-8 -cp "$classes;$android" -d $testClasses "$PSScriptRoot/UiFlowTest.java" "$PSScriptRoot/DeviceReadingTest.java"; Check-Exit
& "$Jdk/bin/jar.exe" cf "$output/test.jar" -C $testClasses .; Check-Exit
& "$tools/d8.bat" --min-api 30 --lib $android --classpath $classes --output $output "$output/test.jar"; Check-Exit
& "$tools/aapt2.exe" link -I $android --manifest "$PSScriptRoot/ui-test/AndroidManifest.xml" -o "$output/unsigned.apk"; Check-Exit
$zip=[IO.Compression.ZipFile]::Open("$output/unsigned.apk",[IO.Compression.ZipArchiveMode]::Update)
try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$output/classes.dex",'classes.dex') | Out-Null } finally { $zip.Dispose() }
& "$tools/zipalign.exe" -f 4 "$output/unsigned.apk" "$output/aligned.apk"; Check-Exit
$key=Join-Path ([Environment]::GetFolderPath('UserProfile')) '.android/debug.keystore'
& "$tools/apksigner.bat" sign --ks $key --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$output/ui-checks.apk" "$output/aligned.apk"; Check-Exit
& adb install -r "$output/ui-checks.apk"; Check-Exit
try {
    $result=& adb shell am instrument -w com.celllock.app.tests/com.celllock.app.UiFlowTest
    Check-Exit
    $result
    if(($result -join "`n") -notmatch 'PASS: \d+ Android UI/command checks'){throw 'Android UI checks failed'}
} finally { & adb uninstall com.celllock.app.tests | Out-Null }
