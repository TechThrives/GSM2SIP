#
# Decide whether a phone can run the gateway with a fully digital audio path.
#
# Usage:  .\tools\check-device.ps1 [adb-serial]
#
param([string]$Serial = "")

$ErrorActionPreference = "SilentlyContinue"

$script:ADBPath = "adb"
$script:ADBExtra = if ($Serial) { @("-s", $Serial) } else { @() }

function Sh {
    param([string]$cmd)
    $out = & $script:ADBPath @script:ADBExtra shell $cmd 2>$null
    if ($out -is [array]) { $out = $out -join "`n" }
    return ($out -replace "`r$", "").Trim()
}

function Su {
    param([string]$cmd)
    $out = & $script:ADBPath @script:ADBExtra shell su -c $cmd 2>$null
    if ($out -is [array]) { $out = $out -join "`n" }
    return ($out -replace "`r$", "").Trim()
}

function Have-Root {
    $result = Su "id -u"
    return ($result -eq "0")
}

$script:pass = 0
$script:fail = 0
$script:unknown = 0

function Ok {
    param([string]$msg)
    Write-Host "  [ok]      $msg" -ForegroundColor Green
    $script:pass++
}

function No {
    param([string]$msg)
    Write-Host "  [MISSING] $msg" -ForegroundColor Red
    $script:fail++
}

function Unk {
    param([string]$msg)
    Write-Host "  [?]       $msg" -ForegroundColor Yellow
    $script:unknown++
}

Write-Host "=== Device ==="
Write-Host "  model    : $(Sh 'getprop ro.product.model')"
Write-Host "  board    : $(Sh 'getprop ro.product.board')   (Build.BOARD, what detect() matches)"
Write-Host "  platform : $(Sh 'getprop ro.board.platform')"
Write-Host "  hardware : $(Sh 'getprop ro.hardware')"
Write-Host "  android  : $(Sh 'getprop ro.build.version.release')"
Write-Host "  vendor   : $(Sh 'getprop ro.vendor.build.fingerprint')"
Write-Host ""

$hw = Sh 'getprop ro.hardware'
if ($hw -ne "qcom") {
    Write-Host "Not a Qualcomm device - stop here."
    Write-Host "Injection needs incall_music and capture needs the in-call record"
    Write-Host "session; neither exists outside the Qualcomm audio HAL."
    exit 1
}

# Mirrors DeviceProfile.detect(), including the prop it matches on. A device
# whose board is already a named profile needs no new entry.
function Get-DetectedProfile {
    $b = (Sh 'getprop ro.product.board').ToLower()
    $m = (Sh 'getprop ro.product.model').ToLower()
    if ($b -like "*exynos9820*" -or ($script:hw -like "*exynos*" -and $m -like "*sm-g970*")) { return "exynos9820()" }
    if ($b -like "*sm6150*" -or $b -like "*sm7150*") { return "sm6150()" }
    if ($script:hw -like "*qcom*" -or $script:hw -like "*qualcomm*") { return "genericQualcomm()" }
    if ($script:hw -like "*exynos*" -or $script:hw -like "*samsung*") { return "genericExynos()" }
    return "generic()"
}

$features = Sh 'pm list features'
if ($features -match 'feature:android\.hardware\.telephony') {
    Ok "telephony hardware present"
} else {
    No "no telephony - this device cannot place GSM calls (WiFi-only?)"
}

# 1. audio policy (no root needed)
Write-Host ""
Write-Host "=== 1. Audio policy: is there a route to the modem uplink? ==="
$POL = "/vendor/etc/audio_policy_configuration.xml"
$polCheck = Sh "grep -q incall_music_uplink $POL && echo y"
if ($polCheck -eq "y") {
    Ok "incall_music_uplink mixPort declared"
    $channels = Sh "grep -A4 'mixPort name=\"incall_music_uplink\"' $POL"
    $channels | Select-String -Pattern "channelmasks" | ForEach-Object { Write-Host "            $_" }
    $sink = Sh "grep -i 'sink=\"Telephony Tx\"' $POL"
    $sink | Select-Object -First 1 | ForEach-Object { Write-Host "            $_" }
} else {
    No "no incall_music_uplink mixPort - nothing to inject the agent into"
}

# 2. HAL (root)
Write-Host ""
Write-Host "=== 2. Audio HAL ==="
if (-not (Have-Root)) {
    Unk "needs root - enable Magisk Superuser access = 'Apps and ADB', then"
    Write-Host "            run 'adb shell su -c id' once and grant the prompt"
} else {
    $HAL = (Su 'ls /vendor/lib64/hw/audio.primary.*.so /vendor/lib/hw/audio.primary.*.so 2>/dev/null') -split "`n"
    $HAL = $HAL | Where-Object { $_ -notmatch "default" } | Select-Object -First 1
    if (-not $HAL) {
        Unk "no vendor audio HAL found"
    } else {
        Write-Host "            $HAL"
        $S = Su "strings $HAL"
        if ($S -match "AUDIO_OUTPUT_FLAG_INCALL_MUSIC") {
            Ok "incall_music output flag   - agent audio into the uplink"
        } else {
            No "incall_music output flag   - no digital path to the caller"
        }
        if ($S -match "USECASE_INCALL_REC") {
            Ok "in-call record usecases    - caller into the agent"
        } else {
            No "in-call record usecases    - capture would be acoustic only"
        }
        if ([regex]::IsMatch($S, '(?m)^vsid$')) {
            Ok "voice_extn vsid/call_state - needed to start that session"
        } else {
            No "voice_extn vsid/call_state - cannot mark the call active"
        }
    }
}

# 3. mixer controls (root + tinymix)
Write-Host ""
Write-Host "=== 3. Mixer controls ==="
if (-not (Have-Root)) {
    Unk "needs root"
} else {
    $TM = ""
    $paths = @("/data/local/tmp/tinymix", "/vendor/bin/tinymix", "/system/bin/tinymix")
    foreach ($p in $paths) {
        $check = Su "[ -x $p ] && echo yes"
        if ($check -eq "yes") {
            $TM = $p
            break
        }
    }
    if (-not $TM) {
        Unk "tinymix not present - push magisk\tinymix to /data/local/tmp/ (chmod 755)"
    } else {
        $M = Su $TM
        $n = ($M | Select-String "Incall_Music Audio Mixer" | Measure-Object).Count
        if ($n -gt 0) {
            $msg = "Incall_Music Audio Mixer ($n ports)"
            Ok $msg
        } else {
            No "Incall_Music Audio Mixer"
        }
        if ($M -match "VOC_REC_DL") {
            Ok "VOC_REC_DL / VOC_REC_UL"
        } else {
            No "VOC_REC_* capture routing"
        }
        if ($M -match "Voc Rec Config") {
            Ok "Voc Rec Config"
        } else {
            No "Voc Rec Config"
        }
    }
}

# verdict
Write-Host ""
Write-Host "=== Verdict ==="
if ($fail -eq 0 -and $unknown -eq 0) {
    Write-Host "  Fully supported on paper: $pass/$pass checks passed."
    $profile = Get-DetectedProfile
    if ($profile -eq "sm6150()" -or $profile -eq "exynos9820()") {
        Write-Host "  DeviceProfile: already covered - detect() returns $profile."
    } else {
        Write-Host "  DeviceProfile: no tuned entry; detect() returns $profile."
        Write-Host "  The mixer names are generic, but which front-end the playback"
        Write-Host "  track lands on is not - the 'Mixer BEFORE/AFTER' lines logged"
        Write-Host "  around each call show it."
    }
} elseif ($fail -eq 0) {
    Write-Host "  Promising: $pass checks passed, $unknown could not be checked."
    Write-Host "  Root the device and re-run for a definite answer."
} elseif ($pass -eq 0) {
    Write-Host "  Not supported: nothing required is present."
} else {
    Write-Host "  Partial: $pass present, $fail missing, $unknown unchecked."
    Write-Host "  Without incall_music there is no digital path to the caller."
}
