$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Install JDK 17 first.' }
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path (Join-Path $sdk 'platforms\android-36\android.jar'))) {
    throw 'Install Android SDK Platform 36 and Build-Tools 35.0.0, then set ANDROID_HOME.'
}
$env:ANDROID_HOME = $sdk
$version = '8.13'
$cache = Join-Path $env:USERPROFILE '.cache\flowstream-gradle'
$gradle = Join-Path $cache "gradle-$version\bin\gradle.bat"
if (-not (Test-Path $gradle)) {
    New-Item -ItemType Directory -Force -Path $cache | Out-Null
    $url = "https://services.gradle.org/distributions/gradle-$version-bin.zip"
    $zip = Join-Path $cache 'gradle.zip'
    Invoke-WebRequest -Uri $url -OutFile $zip
    $sum = (Invoke-WebRequest -Uri "$url.sha256").Content.Trim()
    if ($sum -notmatch '^[0-9a-fA-F]{64}$' -or (Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant() -ne $sum.ToLowerInvariant()) {
        throw 'Gradle SHA-256 mismatch; refusing to execute.'
    }
    Expand-Archive -Force -Path $zip -DestinationPath $cache
    Set-Content -Path (Join-Path $cache 'gradle.zip.sha256') -Value $sum
    Remove-Item $zip
}
if (-not (Test-Path 'gradle\wrapper\gradle-wrapper.jar')) {
    & $gradle --no-daemon wrapper --gradle-version $version --distribution-type bin
    if ($LASTEXITCODE -ne 0) { throw 'Gradle Wrapper generation failed.' }
    $checksum = Join-Path $cache 'gradle.zip.sha256'
    if (Test-Path $checksum) {
        Add-Content 'gradle\wrapper\gradle-wrapper.properties' "distributionSha256Sum=$((Get-Content $checksum -Raw).Trim())"
    }
}
& $gradle --no-daemon :core:selfTest :app:lintDebug :app:assembleDebug
if ($LASTEXITCODE -ne 0) { throw 'Android build or lint failed. Read the error above.' }
Write-Host 'Debug APK: app\build\outputs\apk\debug\app-debug.apk'
