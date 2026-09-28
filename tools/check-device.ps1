<#
    Decide whether a phone can run the gateway with a fully digital audio path.

    Independent implementation of the same checks as check-device.sh: same
    questions, same wording, same verdict, so Windows and Linux agree for a
    given phone.  If you change one, change the other - a check that exists in
    only one of them reports different results per platform.

    Usage:  .\tools\check-device.ps1 [adb-serial]
#>
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

# `Su "cmd" -split "`n"` would bind -split as a parameter of Su.
function ShLines { param([string]$cmd) @((Sh $cmd) -split "`n") }
function SuLines { param([string]$cmd) @((Su $cmd) -split "`n") }

# adb returns one word per line for wrapped output, so a multi-word phrase like
# "Voice Call In" has to be rejoined before a substring test can see it.
function Flat {
    param([string]$text)
    return ($text -replace "\s+", " ").Trim()
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

function Detail {
    param([string]$lines)
    $lines -split "`n" | Where-Object { $_ -ne "" } | ForEach-Object { Write-Host "            $_" }
}

# ── preflight ────────────────────────────────────────────────────────
# Every check below reads empty when adb cannot reach the phone, and empty
# looks like "this device has nothing".  Refuse to answer until adb does.
if (-not (Get-Command $script:ADBPath -ErrorAction SilentlyContinue)) {
    Write-Host "adb not found on PATH. Install platform-tools, or add it to PATH." -ForegroundColor Red
    exit 2
}

$raw = & $script:ADBPath devices -l 2>&1
# Data lines start in column 0; the "List of devices attached" header is indented.
$devs = @($raw | Select-Object -Skip 1 | Where-Object { $_ -match "^(\S+)\s+(\S+)" } |
          ForEach-Object {
              [pscustomobject]@{
                  Serial = ([regex]::Match($_, "^(\S+)")).Groups[1].Value
                  State  = ([regex]::Match($_, "^\S+\s+(\S+)")).Groups[1].Value
              }
          })
$sers = @($devs | ForEach-Object { $_.Serial })

if ($sers.Count -eq 0) {
    Write-Host "No device attached." -ForegroundColor Red
    Write-Host "  Connect the phone with USB, enable USB debugging, and confirm" -ForegroundColor Red
    Write-Host "  the prompt on the handset." -ForegroundColor Red
    exit 2
}

if ($Serial) {
    if ($sers -notcontains $Serial) {
        Write-Host "No device with serial '$Serial'. Attached: $($sers -join ' ')" -ForegroundColor Red
        exit 2
    }
    $dev = $devs | Where-Object { $_.Serial -eq $Serial } | Select-Object -First 1
} else {
    if ($sers.Count -gt 1) {
        Write-Host "More than one device attached - pass a serial:" -ForegroundColor Red
        foreach ($d in $devs) { Write-Host "    $($d.Serial) $($d.State)" -ForegroundColor Red }
        exit 2
    }
    $dev = $devs | Select-Object -First 1
}

if ($dev.State -ne "device") {
    Write-Host "Device is '$($dev.State)', not ready." -ForegroundColor Red
    if ($dev.State -eq "unauthorized") {
        Write-Host "  Unlock the handset and accept 'Allow USB debugging'. If the" -ForegroundColor Red
        Write-Host "  prompt does not appear, revoke USB debugging authorisations in" -ForegroundColor Red
        Write-Host "  Developer options and replug, or run 'adb kill-server'." -ForegroundColor Red
    }
    exit 2
}

Write-Host "=== Device ==="
Write-Host "  model    : $(Sh 'getprop ro.product.model')"
Write-Host "  board    : $(Sh 'getprop ro.product.board')   (Build.BOARD, what detect() matches)"
Write-Host "  platform : $(Sh 'getprop ro.board.platform')"
Write-Host "  hardware : $(Sh 'getprop ro.hardware')"
Write-Host "  android  : $(Sh 'getprop ro.build.version.release')"
Write-Host "  vendor   : $(Sh 'getprop ro.vendor.build.fingerprint')"
Write-Host ""

# Lowercased once and reused: DeviceProfile.detect() lowercases Build.HARDWARE.
$script:hw = (Sh 'getprop ro.hardware').ToLower()
$platform = (Sh 'getprop ro.board.platform').ToLower()
$board = (Sh 'getprop ro.product.board').ToLower()
$model = (Sh 'getprop ro.product.model').ToLower()
$socModel = (Sh 'getprop ro.soc.model').ToLower()
$socMaker = (Sh 'getprop ro.soc.manufacturer').ToLower()

# ro.hardware is the only reliable signal and is not always a vendor name
# (Unisoc says "ums", HiSilicon "hi3660").  Check platform too, and fall back
# to a name guess rather than a whitelist.
$script:soc = if ($script:hw -eq "qcom" -or $script:hw -like "*qualcomm*" -or $platform -like "*msm*" -or $platform -like "*sdm*") { "qcom" }
             elseif ($script:hw -like "*exynos*" -or $script:hw -like "*samsung*" -or $board -like "*universal*" -or $platform -like "*exynos*") { "exynos" }
             elseif ($script:hw -like "mt*" -or $platform -like "mt*" -or $board -like "*mt*" -or $model -like "*mediatek*") { "mtk" }
             else { "unknown" }

Write-Host "=== 0. SoC family ==="
switch ($script:soc) {
    "qcom"   { Write-Host "  Qualcomm - the profile the gateway is tuned for." }
    "exynos" { Write-Host "  Samsung Exynos - ABOX mixer, no incall_music." }
    "mtk"    { Write-Host "  MediaTek - no tuned DeviceProfile; own HAL and mixer vocabulary." }
    default  { Write-Host "  Unrecognised SoC - generic probes below." }
}
Write-Host ""

if ((Sh 'pm list features') -match 'feature:android\.hardware\.telephony') {
    Ok "telephony hardware present"
} else {
    No "no telephony - this device cannot place GSM calls (WiFi-only?)"
}

# ── 1. audio policy (no root needed) - the decisive one ──────────────
Write-Host ""
Write-Host "=== 1. Audio policy: is there a route to the modem uplink? ==="
# Not every ROM keeps the policy in /vendor/etc.  Search the usual homes, and
# fall back to audio_policy.conf for pre-11 builds that predate the XML.
$POL = ""
foreach ($cand in @("/vendor/etc/audio_policy_configuration.xml",
                    "/odm/etc/audio_policy_configuration.xml",
                    "/system/etc/audio_policy_configuration.xml")) {
    if (Sh "[ -f $cand ] && echo y") { $POL = $cand; break }
}
$LEGACY = ""
if (-not $POL) {
    foreach ($cand in @("/vendor/etc/audio_policy.conf", "/system/etc/audio_policy.conf")) {
        if (Sh "[ -f $cand ] && echo y") { $LEGACY = $cand; break }
    }
}

if (-not $POL -and -not $LEGACY) {
    No "no audio policy found - cannot tell whether a route exists"
    Detail "looked in /vendor/etc, /odm/etc, /system/etc"
} elseif ($LEGACY) {
    No "only the legacy $LEGACY found - no incall_music concept to check"
    Detail "pre-11 policy format; injection is not expressible in it"
} else {
    # No embedded double quotes: \" ends a PowerShell argument early.
    # "voice call in" is the downlink capture source on MediaTek; Qualcomm
    # spells it telephony_rx.
    $NAMES_RE = '(telephony_tx|telephony_rx|incall_music_uplink|incall_music_downlink|voice_tx|voice_rx|voice_call|voice call in|incall_music)'
    $namesGrep = "grep -oiE '$NAMES_RE' $POL | sort -u"
    $lnames = (Flat (Sh $namesGrep)).ToLower()
    Detail (ShLines $namesGrep)

    if ($lnames -match 'incall_music') {
        Ok "incall_music mixPort declared"
        Detail (ShLines "grep -A6 'mixPort name=.incall_music' $POL | grep -iE 'channelmasks|sink='")
    } else {
        No "no incall_music mixPort - no dedicated injection port"
    }

    # Qualcomm spells it 'Telephony Tx', MediaTek 'telephony_tx'.
    $routeGrep = "grep -A1 -iE 'sink=.[^ ]*telephony' $POL | head -6"
    if (Sh $routeGrep) {
        Ok "a mixPort is routed into the telephony TX path"
        Detail (ShLines $routeGrep)
    } else {
        No "no mixPort is routed into telephony TX - nothing to inject the agent into"
    }

    if ($lnames -match 'telephony_rx' -or $lnames -match 'voice_rx' -or $lnames -match 'voice call in') {
        Ok "downlink capture source declared - a digital capture source can address it"
    } else {
        No "no downlink capture source (telephony_rx / Voice Call In) in the policy"
    }

    if ($lnames -match 'incall_music') {
        Write-Host "            Necessary, not sufficient: says nothing about the HAL. See section 2."
    }
}

# ── 2. HAL (root) ────────────────────────────────────────────────────
Write-Host ""
Write-Host "=== 2. Audio HAL ==="
if (-not (Have-Root)) {
    Unk "needs root to read the HAL"
    if (-not (Sh "which su")) {
        Write-Host "            no su on this device at all - 'su: not found', not a"
        Write-Host "            permission problem. Sections 2 and 3 cannot run here, and"
        Write-Host "            no amount of Superuser access setting will change that."
        Write-Host "            Rooting the bootloader is out of scope for this check."
    } else {
        Write-Host "            su exists but did not return uid 0. If this device has"
        Write-Host "            Magisk, set Superuser access = 'Apps and ADB' and run"
        Write-Host "            'adb shell su -c id' once to grant the prompt."
    }
} else {
    # Globs, not fixed names: the vendor names the file after its own SoC, so
    # audio.primary.msm8953.so, .sdm660.so, .mt6768.so and .universal9820.so
    # are all the same role.  libMtk* is the one vendor family that does not
    # follow the audio.primary.* pattern.
    $GLOBS = @("/vendor/lib64/hw/audio.primary.*.so /vendor/lib/hw/audio.primary.*.so
                /vendor/lib64/hw/audio.*.so /vendor/lib/hw/audio.*.so
                /vendor/lib64/libMtkAudio.so /vendor/lib64/libMtkVoice.so
                /vendor/lib64/hw/audio.r_submix.*.so") -join " "
    # audio.primary.default.so is the passthrough stub, not the vendor HAL.
    $HAL = @((Su "ls $GLOBS 2>/dev/null") -split "`n" |
             Where-Object { $_ -match '\.so$' -and $_ -notmatch 'default' } |
             Sort-Object -Unique)

    if ($HAL.Count -eq 0) {
        Unk "no vendor audio HAL found"
    } else {
        Detail ($HAL -join "`n")
        $S = (($HAL | ForEach-Object { Su "strings $_" }) -join "`n")

        # Symbol names, not enum spellings: the sm6150 HAL has no
        # AUDIO_OUTPUT_FLAG_INCALL_MUSIC string.  vsid is Qualcomm-only.
        $probes = @(
            @("(?i)incall_?music", "incall_music usecases     - agent audio into the uplink"),
            @("(?i)incall_?rec", "in-call record usecases    - caller into the agent")
        )
        if ($script:soc -eq "qcom") {
            $probes += , @("(?m)^vsid\s*$", "voice_extn vsid/call_state - needed to start that session")
        }
        foreach ($p in $probes) {
            if ($S -match $p[0]) { Ok $p[1] } else { No $p[1] }
        }
    }
}

# ── 3. mixer controls (root + tinymix) ───────────────────────────────
Write-Host ""
Write-Host "=== 3. Mixer controls ==="
if (-not (Have-Root)) {
    Unk "needs root for mixer access"
    if (-not (Sh "which su")) {
        Write-Host "            no su on this device at all - this section cannot run here."
    }
} else {
    $TM = ""
    foreach ($p in @("/data/local/tmp/tinymix", "/vendor/bin/tinymix", "/system/bin/tinymix")) {
        if ((Su "[ -x $p ] && echo yes") -eq "yes") { $TM = $p; break }
    }
    if (-not $TM) {
        Unk "tinymix not present - push magisk/tinymix to /data/local/tmp/ (chmod 755)"
    } else {
        $M = Su $TM

        # Control names differ per SoC and per codec, so ask the same three
        # questions in each vocabulary.  A miss means "not under these names",
        # not "absent": only the count is certain.
        $mixerProbes = if ($script:soc -eq "qcom") {
            @(
                @("(?i)incall_music", "incall music mixer"),
                @("(?i)voc_rec|voice.*rec", "voice record routing"),
                @("(?i)voice.*(rx|tx).*mute", "voice device mutes")
            )
        } elseif ($script:soc -eq "exynos") {
            @(
                @("(?i)abox.*nsrc", "ABOX NSRC bridge"),
                @("(?i)spus", "ABOX SPUS"),
                @("(?i)swp|spk", "speaker amp controls")
            )
        } else {
            @(
                @("(?i)incall.*music", "incall music mixer"),
                @("(?i)voice.*(rec|uplink|downlink)", "voice rec/uplink mixer"),
                @("(?i)MMAudio.*Mix", "MMAudio playback mixer"),
                @("(?i)AHB_Input", "AHB input mixer"),
                @("(?i)APO.*Mix", "APO mixer")
            )
        }
        foreach ($p in $mixerProbes) {
            # Count matching lines, the way grep -c does, so both scripts
            # report the same number for the same device.
            $n = @($M -split "`n" | Where-Object { $_ -match $p[0] }).Count
            if ($n -gt 0) { Ok "$($p[1]) ($n match)" } else { No $p[1] }
        }

        $mp = Sh "grep -ciE 'voice|incall' /vendor/etc/mixer_paths.xml"
        if ($mp) { Write-Host "            mixer_paths.xml voice/incall lines: $mp" }
    }
}

# Mirrors DeviceProfile.detect(): ro.board.platform first — it is the only SoC
# key present on every ROM (ro.soc.* is Android 12+ and was empty on an
# Android 10 Redmi that reports the same sm6150 chip) — then SOC_MODEL, then
# BOARD.
function Get-DetectedProfile {
    $chip = "$platform $socModel $board"
    if ($chip -like "*exynos9820*" -or ($script:hw -like "*exynos*" -and $model -like "*sm-g970*")) { return "exynos9820()" }
    if ($chip -like "*sm6150*" -or $chip -like "*sm7150*") { return "sm6150()" }
    if ($script:hw -like "*qcom*" -or $script:hw -like "*qualcomm*" -or $socMaker -like "*qualcomm*" -or $socMaker -eq "qti") { return "genericQualcomm()" }
    if ($script:hw -like "*exynos*" -or $script:hw -like "*samsung*" -or $socMaker -like "*samsung*") { return "genericExynos()" }
    return "generic()"
}

# ── verdict ──────────────────────────────────────────────────────────
$profile = Get-DetectedProfile
Write-Host ""
Write-Host "=== Verdict ==="
if ($fail -eq 0 -and $unknown -eq 0) {
    Write-Host "  Fully supported on paper: $pass/$pass checks passed."
} elseif ($fail -eq 0) {
    Write-Host "  Inconclusive: $pass checks passed, $unknown could not be checked."
    Write-Host "  The unchecked ones are what this verdict turns on."
} elseif ($script:soc -eq "unknown" -and $pass -le 1) {
    Write-Host "  Not supported: nothing required is present."
} else {
    Write-Host "  Partial: $pass present, $fail missing, $unknown unchecked."
}

if ($script:soc -eq "exynos") {
    Write-Host ""
    Write-Host "  Exynos: capture is acoustic unless the ABOX bridge can be"
    Write-Host "  rerouted - see exynos9820() in DeviceProfile.kt."
} elseif ($script:soc -eq "mtk") {
    Write-Host ""
    Write-Host "  MediaTek: no DeviceProfile is tuned for this SoC. Nothing here"
    Write-Host "  is established beyond the lines above."
} elseif ($script:soc -eq "unknown") {
    Write-Host ""
    Write-Host "  Unrecognised SoC. The mixer names in section 3 are a guess, so"
    Write-Host "  treat that section as unproven; section 1 still stands."
}

Write-Host ""
Write-Host "  DeviceProfile: detect() returns $profile."
if ($profile -eq "sm6150()" -or $profile -eq "exynos9820()") {
    Write-Host "  A tuned entry exists for this board."
} elseif ($profile -eq "generic()") {
    Write-Host "  That is the no-op profile: it runs no mixer commands, so a usable"
    Write-Host "  route would not be driven. A new detect() entry is the next step."
} else {
    Write-Host "  Generic: the control names are right for the SoC, but which"
    Write-Host "  front-end the playback track lands on is not. The 'Mixer"
    Write-Host "  BEFORE/AFTER' lines logged around each call show it."
}

if ($fail -gt 0 -and $pass -le 1) { exit 1 }
exit 0
