#
# Setup script for building the SIP-GSM Gateway APK on Windows.
# Checks Java, Android SDK, and ensures gradle-wrapper.jar exists.
#
# Usage:
#   .\setup.ps1
#
# After setup, build with:
#   .\build.ps1
#
$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# Default Android SDK location on Windows
$AndroidSdkDir = "$env:LOCALAPPDATA\Android\Sdk"
$propsFile = Join-Path $ScriptDir "gradle\wrapper\gradle-wrapper.properties"
$propLine  = Get-Content $propsFile | Select-String -Pattern 'distributionUrl=.*gradle-([0-9.]+)-bin\.zip'
if (-not $propLine) { Write-Error "[ERROR] Could not read Gradle version from $propsFile"; exit 1 }
$gradleVer = $propLine.Matches[0].Groups[1].Value
$WrapperJarUrl = "https://raw.githubusercontent.com/gradle/gradle/v${gradleVer}/gradle/wrapper/gradle-wrapper.jar"

function Log($msg)  { Write-Host "[+] $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host "[!] $msg" -ForegroundColor Yellow }
function Err($msg)  { Write-Error "[ERROR] $msg"; exit 1 }

# ── 1. Check Java ────────────────────────────────────

function Test-JavaInstall {
    $java = Get-Command java -ErrorAction SilentlyContinue
    if (-not $java) {
        Err "Java not found. Install JDK 25+ from https://adoptium.net/ and ensure 'java' is on your PATH."
    }
    $verStr = (cmd /c "java -version 2>&1") | Select-Object -First 1
    $match = [regex]::Match($verStr, '"(\d+)')
    if ($match.Success) {
        $javaVer = [int]$match.Groups[1].Value
        if ($javaVer -lt 25) {
            Err "JDK 25+ required but found version $javaVer. Install from https://adoptium.net/"
        }
    }
    Log "Java: $verStr"
}

# ── 2. Android SDK ───────────────────────────────────

function Test-AndroidSdk {
    if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
        $candidates = @(
            "$env:LOCALAPPDATA\Android\Sdk",
            "C:\Android\Sdk",
            "C:\android-sdk",
            "$env:USERPROFILE\Android\Sdk"
        )
        foreach ($dir in $candidates) {
            if (Test-Path $dir) {
                $env:ANDROID_HOME = $dir
                break
            }
        }
    }
    if (-not $env:ANDROID_HOME -and $env:ANDROID_SDK_ROOT) {
        $env:ANDROID_HOME = $env:ANDROID_SDK_ROOT
    }

    if (-not $env:ANDROID_HOME -or -not (Test-Path $env:ANDROID_HOME)) {
        Err "Android SDK not found at $AndroidSdkDir. Install Android Studio or download command-line tools."
    }

    # Ensure local.properties exists
    $sdkPath = $env:ANDROID_HOME -replace '\\', '/'
    "sdk.dir=$sdkPath" | Set-Content -Path (Join-Path $ScriptDir "local.properties") -Encoding UTF8
    Log "Android SDK: $env:ANDROID_HOME"

    # Check for required SDK components
    $buildTools = Join-Path $env:ANDROID_HOME "build-tools\34.0.0\aapt2.exe"
    $platform = Join-Path $env:ANDROID_HOME "platforms\android-34\android.jar"
    if (-not (Test-Path $buildTools) -or -not (Test-Path $platform)) {
        Warn "Missing SDK components. Installing..."
        $sdkmanager = Join-Path $env:ANDROID_HOME "cmdline-tools\latest\bin\sdkmanager.bat"
        if (Test-Path $sdkmanager) {
            [string]::new([char]'y', 10) -split '' | Where-Object { $_ } | & "$sdkmanager" --licenses 2>$null | Out-Null
            cmd /c "`"$sdkmanager`" --install `"platform-tools`" `"platforms;android-34`" `"build-tools;34.0.0`" 2>nul" | Out-Null
            Log "SDK components installed"
        } else {
            Warn "sdkmanager not found. Install 'platform-tools', 'platforms;android-34', 'build-tools;34.0.0' manually."
        }
    } else {
        Log "SDK components OK"
    }
}

# ── 3. Gradle wrapper JAR ────────────────────────────

function Test-GradleWrapper {
    $jar = Join-Path $ScriptDir "gradle\wrapper\gradle-wrapper.jar"

    if (Test-Path $jar) {
        try {
            $zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
            $zip.Dispose()
            Log "Gradle wrapper JAR OK"
            return
        } catch {
            Warn "gradle-wrapper.jar is corrupt, re-downloading..."
            Remove-Item $jar -Force
        }
    }

    Log "Downloading gradle-wrapper.jar..."
    New-Item -ItemType Directory -Path (Join-Path $ScriptDir "gradle\wrapper") -Force | Out-Null
    [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12
    try {
        Invoke-WebRequest -Uri $WrapperJarUrl -OutFile $jar -UseBasicParsing
    } catch {
        Err "Failed to download gradle-wrapper.jar. Check your internet connection."
    }

    if (-not (Test-Path $jar) -or (Get-Item $jar).Length -lt 1000) {
        Err "Downloaded gradle-wrapper.jar is invalid."
    }

    Log "Gradle wrapper JAR downloaded"
}

# ── Main ─────────────────────────────────────────────

Write-Host ""
Write-Host "========================================="
Write-Host "  SIP-GSM Gateway - Build Environment"
Write-Host "  (Windows)"
Write-Host "========================================="
Write-Host ""

Test-JavaInstall
Test-AndroidSdk
Test-GradleWrapper

Write-Host ""
Write-Host "========================================="
Write-Host "  Setup complete!"
Write-Host "========================================="
Write-Host ""
Write-Host "  To build: .\build.ps1"
Write-Host ""
