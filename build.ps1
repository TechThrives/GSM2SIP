#
# Build script for SIP-GSM Gateway APK + Magisk module (Windows PowerShell).
#
# Prerequisites:
#   .\setup.ps1    # run once to install Android SDK, etc.
#
# Usage:
#   .\build.ps1          # Build debug APK + Magisk module
#   .\build.ps1 release  # Build release APK + Magisk module
#
$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

# ── Source environment if available ──────────────────

$envFile = Join-Path $ScriptDir ".env.build.ps1"
if (Test-Path $envFile) {
    . $envFile
}

# ── Check prerequisites ──────────────────────────────

function Test-Java {
    $java = Get-Command java -ErrorAction SilentlyContinue
    if (-not $java) {
        Write-Error "Java not found. Run: .\setup.ps1"
        exit 1
    }
    $verStr = (cmd /c "java -version 2>&1") | Select-Object -First 1
    $match = [regex]::Match($verStr, '"(\d+)')
    if ($match.Success) {
        $javaVer = [int]$match.Groups[1].Value
        if ($javaVer -lt 25) {
            Write-Error "JDK 25+ required (found: $javaVer). Run: .\setup.ps1"
            exit 1
        }
    }
    Write-Host "Java: $verStr"
}

function Test-AndroidSdk {
    if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
        # Common Windows locations
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
        Write-Error "Android SDK not found. Run: .\setup.ps1"
        exit 1
    }

    # Ensure local.properties exists
    "sdk.dir=$($env:ANDROID_HOME -replace '\\','/')" | Set-Content -Path (Join-Path $ScriptDir "local.properties") -Encoding UTF8
    Write-Host "Android SDK: $env:ANDROID_HOME"
}

function Test-GradleWrapper {
    $jar = Join-Path $ScriptDir "gradle\wrapper\gradle-wrapper.jar"
    $needDownload = $false

    if (-not (Test-Path $jar)) {
        $needDownload = $true
    } else {
        try {
            # Validate jar by checking it's a valid zip
            $zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
            $zip.Dispose()
        } catch {
            Write-Host "WARNING: gradle-wrapper.jar is corrupt, re-downloading..."
            Remove-Item $jar -Force -ErrorAction SilentlyContinue
            $needDownload = $true
        }
    }

    if ($needDownload) {
        Write-Host "Downloading gradle-wrapper.jar..."
        New-Item -ItemType Directory -Path (Join-Path $ScriptDir "gradle\wrapper") -Force | Out-Null

        $propsFile = Join-Path $ScriptDir "gradle\wrapper\gradle-wrapper.properties"
        $propLine  = Get-Content $propsFile | Select-String -Pattern 'distributionUrl=.*gradle-([0-9.]+)-bin\.zip'
        if (-not $propLine) { Write-Error "Could not read Gradle version from $propsFile"; exit 1 }
        $gradleVer = $propLine.Matches[0].Groups[1].Value
        $url = "https://raw.githubusercontent.com/gradle/gradle/v${gradleVer}/gradle/wrapper/gradle-wrapper.jar"
        try {
            Invoke-WebRequest -Uri $url -OutFile $jar -UseBasicParsing
        } catch {
            Write-Error "Failed to download gradle-wrapper.jar: $_"
            exit 1
        }
    }

    if (-not (Test-Path $jar) -or (Get-Item $jar).Length -lt 1000) {
        Write-Error "Valid gradle-wrapper.jar not found. Run: .\setup.ps1"
        exit 1
    }
}

# ── Build APK ────────────────────────────────────────

function Build-Apk {
    param([string]$BuildType = "debug")

    Write-Host ""
    Write-Host "=== Building $BuildType APK ==="
    Write-Host ""

    $gradlew = Join-Path $ScriptDir "gradlew.bat"

    if ($BuildType -eq "release") {
        & $gradlew assembleRelease --no-daemon
        # Native exit codes bypass $ErrorActionPreference, and the Test-Path
        # below would happily pass on the previous build's APK.
        if ($LASTEXITCODE -ne 0) {
            Write-Error "Gradle failed (exit code $LASTEXITCODE) - see output above. Not packaging a possibly-stale APK."
            exit 1
        }
        $apkPath = Join-Path $ScriptDir "app\build\outputs\apk\release\app-release.apk"
        if (-not (Test-Path $apkPath)) {
            $apkPath = Join-Path $ScriptDir "app\build\outputs\apk\release\app-release-unsigned.apk"
        }
    } else {
        & $gradlew assembleDebug --no-daemon
        # Same guard as the release branch above.
        if ($LASTEXITCODE -ne 0) {
            Write-Error "Gradle failed (exit code $LASTEXITCODE) - see output above. Not packaging a possibly-stale APK."
            exit 1
        }
        $apkPath = Join-Path $ScriptDir "app\build\outputs\apk\debug\app-debug.apk"
    }

    if (Test-Path $apkPath) {
        Write-Host ""
        Write-Host "APK built: $apkPath"
        Copy-Item $apkPath (Join-Path $ScriptDir "gateway.apk") -Force
        Write-Host "Copied to: $(Join-Path $ScriptDir 'gateway.apk')"
    } else {
        Write-Error "APK not found at $apkPath"
        exit 1
    }
}

# ── Build Magisk module ─────────────────────────────

function Build-TinymixArch {
    param(
        [string]$GoArch,
        [string]$OutPath
    )
    $tinymixSrc = Join-Path $ScriptDir "tools\tinymix"

    # Check if pre-built binary of the right architecture exists
    if (Test-Path $OutPath) {
        $bytes = [System.IO.File]::ReadAllBytes($OutPath)
        # ELF header: byte 4 = class (1=32-bit, 2=64-bit)
        if ($bytes.Length -gt 4 -and $bytes[0] -eq 0x7f -and $bytes[1] -eq 0x45) {
            $elfClass = $bytes[4]
            if (($GoArch -eq "arm64" -and $elfClass -eq 2) -or
                ($GoArch -eq "arm" -and $elfClass -eq 1)) {
                Write-Host "Using existing tinymix: $OutPath"
                return
            }
        }
    }

    $go = Get-Command go -ErrorAction SilentlyContinue
    if ($go -and (Test-Path (Join-Path $tinymixSrc "main.go"))) {
        Write-Host "Building tinymix for $GoArch..."
        Push-Location $tinymixSrc
        try {
            $env:GOOS = "linux"
            $env:GOARCH = $GoArch
            $env:GOARM = "7"
            $env:CGO_ENABLED = "0"
            & go build -ldflags="-s -w" -o $OutPath .
            if (Test-Path $OutPath) {
                Write-Host "tinymix built: $OutPath"
                return
            }
        } finally {
            Pop-Location
            Remove-Item Env:GOOS -ErrorAction SilentlyContinue
            Remove-Item Env:GOARCH -ErrorAction SilentlyContinue
            Remove-Item Env:GOARM -ErrorAction SilentlyContinue
            Remove-Item Env:CGO_ENABLED -ErrorAction SilentlyContinue
        }
    }

    Write-Host "WARNING: could not build tinymix for $GoArch - mixer controls will"
    Write-Host "         not work on $GoArch devices.  Install Go, or drop a"
    Write-Host "         pre-built binary at: $OutPath"
}

function Build-Tinymix {
    Write-Host ""
    Write-Host '=== Building tinymix (static, one per ABI) ==='
    Write-Host ""

    Build-TinymixArch -GoArch "arm64" -OutPath (Join-Path $ScriptDir "magisk\tinymix")
    Build-TinymixArch -GoArch "arm" -OutPath (Join-Path $ScriptDir "magisk\tinymix32")
}

function Build-Magisk {
    Write-Host ""
    Write-Host "=== Building Magisk module ==="
    Write-Host ""

    Build-Tinymix

    # Copy the APK into the Magisk module as a system priv-app
    $privAppDir = Join-Path $ScriptDir "magisk\system\priv-app\Gateway"
    New-Item -ItemType Directory -Path $privAppDir -Force | Out-Null
    Copy-Item (Join-Path $ScriptDir "gateway.apk") (Join-Path $privAppDir "Gateway.apk") -Force
    Write-Host "Included APK as priv-app in Magisk module"

    $zipPath = Join-Path $ScriptDir "gateway-magisk.zip"
    if (Test-Path $zipPath) { Remove-Item $zipPath -Force }

    # Ensure all shell scripts have Unix line endings (LF) and no BOM before zipping
    $magiskDir = Join-Path $ScriptDir "magisk"
    Get-ChildItem -Path $magiskDir -Filter "*.sh" -File | ForEach-Object {
        $bytes = [System.IO.File]::ReadAllBytes($_.FullName)
        # Strip UTF-8 BOM if present
        if ($bytes.Length -gt 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
            $bytes = $bytes[3..($bytes.Length - 1)]
        }
        $content = [System.Text.Encoding]::UTF8.GetString($bytes) -replace "`r`n", "`n"
        [System.IO.File]::WriteAllText($_.FullName, $content, (New-Object System.Text.UTF8Encoding $false))
    }

    # Create zip with forward-slash paths (Android unzip treats backslashes as literals)
    Add-Type -Assembly System.IO.Compression
    $zipStream = [System.IO.File]::Create($zipPath)
    $archive = New-Object System.IO.Compression.ZipArchive($zipStream, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        Get-ChildItem -Path $magiskDir -Recurse -File | Where-Object {
            $_.Name -ne ".DS_Store" -and $_.FullName -notmatch "__MACOSX"
        } | ForEach-Object {
            $entryName = $_.FullName.Substring($magiskDir.Length + 1).Replace("\", "/")
            $entry = $archive.CreateEntry($entryName, [System.IO.Compression.CompressionLevel]::Optimal)
            $entryStream = $entry.Open()
            $fileStream = [System.IO.File]::OpenRead($_.FullName)
            $fileStream.CopyTo($entryStream)
            $fileStream.Dispose()
            $entryStream.Dispose()
        }
    } finally {
        $archive.Dispose()
        $zipStream.Dispose()
    }
    Write-Host "Magisk module: $zipPath"
}

# ── Install to device (if connected via ADB) ────────

function Install-ToDevice {
    if ($env:SKIP_INSTALL) {
        Write-Host ""
        Write-Host "SKIP_INSTALL set - built artifacts only, nothing installed."
        return
    }

    $devices = (cmd /c "adb devices 2>nul") | Select-String 'device$' | Measure-Object
    if ($devices.Count -gt 1) {
        Write-Host ""
        Write-Host "$($devices.Count) devices connected - refusing to guess which one to install to."
        Write-Host 'Install explicitly:  adb -s <serial> install -r gateway.apk'
        return
    }

    $adb = Get-Command adb -ErrorAction SilentlyContinue
    if ($adb) {
        $deviceList = (cmd /c "adb devices 2>nul") | Select-String 'device$'
        if ($deviceList) {
            Write-Host ""
            Write-Host "=== Device detected - installing ==="
            & adb install -r (Join-Path $ScriptDir "gateway.apk")
            Write-Host "APK installed."
            Write-Host ""
            Write-Host "To install Magisk module:"
            Write-Host "  adb push gateway-magisk.zip /sdcard/"
            Write-Host "  Then install via Magisk Manager on the device."
        } else {
            Write-Host ""
            Write-Host "No ADB device connected. To install manually:"
            Write-Host "  adb install gateway.apk"
            Write-Host "  adb push gateway-magisk.zip /sdcard/"
        }
    }
}

# ── Main ─────────────────────────────────────────────

Write-Host "=== SIP-GSM Gateway Build ==="
Write-Host ""

Test-Java
Test-AndroidSdk
Test-GradleWrapper

$buildType = if ($args.Count -gt 0) { $args[0] } else { "debug" }
Build-Apk -BuildType $buildType
Build-Magisk
Install-ToDevice

Write-Host ""
Write-Host "=== Build complete ==="
Write-Host "  APK:    $(Join-Path $ScriptDir 'gateway.apk')"
Write-Host "  Magisk: $(Join-Path $ScriptDir 'gateway-magisk.zip')"
Write-Host ""
Write-Host "Deploy to device:"
Write-Host "  1. adb push gateway-magisk.zip /sdcard/"
Write-Host "     Install via Magisk Manager -> Modules, then reboot"
Write-Host "     (APK is included in the module as a priv-app)"
Write-Host "  2. After reboot: open app, grant permissions, set as default phone app"
Write-Host "  3. Enter SIP credentials, tap START"
Write-Host ""
Write-Host "NOTE: Do NOT also 'adb install' - the Magisk module installs the APK"
Write-Host "      as a privileged system app with CAPTURE_AUDIO_OUTPUT permission."
