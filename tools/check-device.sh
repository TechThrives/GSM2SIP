#!/bin/bash
#
# Decide whether a phone can run the gateway with a fully digital audio path.
#
# Checks are grouped by what they read, not by SoC: the policy check needs no
# root and applies everywhere, the HAL and mixer checks need root.  A non-
# Qualcomm device is not disqualified by its chip - it is disqualified, or not,
# by what this prints.
#
# The policy check needs no root, so a candidate phone can be vetted before
# rooting it.  The HAL and mixer checks need root (Magisk, with Superuser
# access set to "Apps and ADB").
#
# This and check-device.ps1 are independent implementations of the same
# checks: same questions, same wording, same verdict, so Windows and Linux
# agree for a given phone.  If you change one, change the other - a check that
# exists in only one of them reports different results per platform.
#
# Usage:  tools/check-device.sh [adb-serial]

set -u
SERIAL="${1:-}"
ADB=(adb); [ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

sh_()  { "${ADB[@]}" shell "$@" 2>/dev/null | tr -d '\r'; }
su_()  { "${ADB[@]}" shell "su -c '$1'" 2>/dev/null | tr -d '\r'; }
have_root() { [ "$(su_ 'id -u')" = "0" ]; }

# adb returns one word per line for wrapped output, so multi-word phrases
# need rejoining before any substring test.
flat() { tr '\n' ' ' | tr -s ' '; }

# Colour when the terminal can show it. The .ps1 launcher re-enables this for
# the Windows console, which does not export TERM to the bash it spawns.
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ] && [ "${TERM:-dumb}" != "dumb" ]; then
    C_OK=$'\033[32m'; C_NO=$'\033[31m'; C_UNK=$'\033[33m'
    C_DIM=$'\033[2m'; C_OFF=$'\033[0m'
else
    C_OK=; C_NO=; C_UNK=; C_DIM=; C_OFF=
fi

pass=0; fail=0; unknown=0
ok()  { printf "  %s[ok]%s      %s\n"      "$C_OK"  "$C_OFF" "$1"; pass=$((pass+1)); }
no()  { printf "  %s[MISSING]%s %s\n"    "$C_NO"  "$C_OFF" "$1"; fail=$((fail+1)); }
unk() { printf "  %s[?]%s       %s\n"      "$C_UNK" "$C_OFF" "$1"; unknown=$((unknown+1)); }
# One arg: unquoted $(...) would word-split it.  Split on newlines only.
detail() { printf '%s\n' "$1" | while IFS= read -r l; do [ -n "$l" ] && echo "            $l"; done; }
has() { case "$1" in *"$2"*) return 0 ;; *) return 1 ;; esac; }

# ── preflight ────────────────────────────────────────────────────────
# Every check below reads empty when adb cannot reach the phone, and empty
# looks like "this device has nothing".  Refuse to answer until adb does.
if ! command -v adb >/dev/null 2>&1; then
    echo "adb not found on PATH. Install platform-tools, or add it to PATH." >&2
    exit 2
fi

DEVS=$(adb devices -l 2>/dev/null | awk 'NR>1 && NF>=2 {print $1, $2}')
SERS=$(echo "$DEVS" | awk 'NF{print $1}')

if [ -z "$SERS" ]; then
    echo "No device attached." >&2
    echo "  Connect the phone with USB, enable USB debugging, and confirm" >&2
    echo "  the prompt on the handset." >&2
    exit 2
fi

if [ -n "$SERIAL" ]; then
    if ! echo "$SERS" | grep -qx "$SERIAL"; then
        echo "No device with serial '$SERIAL'. Attached: $(echo "$SERS" | tr '\n' ' ')" >&2
        exit 2
    fi
    STATE=$(echo "$DEVS" | awk -v s="$SERIAL" '$1==s {print $2}')
else
    if [ "$(echo "$SERS" | wc -l)" -gt 1 ]; then
        echo "More than one device attached - pass a serial:" >&2
        echo "$DEVS" | sed 's/^/    /' >&2
        exit 2
    fi
    STATE=$(echo "$DEVS" | awk 'NF{print $2}')
fi

if [ "$STATE" != device ]; then
    echo "Device is '$STATE', not ready." >&2
    if [ "$STATE" = unauthorized ]; then
        echo "  Unlock the handset and accept 'Allow USB debugging'. If the" >&2
        echo "  prompt does not appear, revoke USB debugging authorisations in" >&2
        echo "  Developer options and replug, or run 'adb kill-server'." >&2
    fi
    exit 2
fi

echo "=== Device ==="
echo "  model    : $(sh_ getprop ro.product.model)"
echo "  board    : $(sh_ getprop ro.product.board)   (Build.BOARD, what detect() matches)"
echo "  platform : $(sh_ getprop ro.board.platform)"
echo "  hardware : $(sh_ getprop ro.hardware)"
echo "  android  : $(sh_ getprop ro.build.version.release)"
echo "  vendor   : $(sh_ getprop ro.vendor.build.fingerprint)"
echo

# Lowercased once and reused: DeviceProfile.detect() lowercases Build.HARDWARE.
hw=$(sh_ getprop ro.hardware | tr '[:upper:]' '[:lower:]')
platform=$(sh_ getprop ro.board.platform | tr '[:upper:]' '[:lower:]')
board=$(sh_ getprop ro.product.board | tr '[:upper:]' '[:lower:]')
model=$(sh_ getprop ro.product.model | tr '[:upper:]' '[:lower:]')
socmodel=$(sh_ getprop ro.soc.model | tr '[:upper:]' '[:lower:]')
socmaker=$(sh_ getprop ro.soc.manufacturer | tr '[:upper:]' '[:lower:]')

# ro.hardware is the only reliable signal and is not always a vendor name
# (Unisoc says "ums", HiSilicon "hi3660").  Check platform too, and fall back
# to a name guess rather than a whitelist.
if [ "$hw" = "qcom" ] || has "$hw" qualcomm || has "$platform" "msm" || has "$platform" "sdm"; then
    soc=qcom
elif has "$hw" exynos || has "$hw" samsung || has "$board" universal || has "$platform" exynos; then
    soc=exynos
elif has "$hw" "mt" || has "$platform" "mt" || has "$board" "mt" || has "$model" mediatek; then
    soc=mtk
else
    soc=unknown
fi

echo "=== 0. SoC family ==="
case "$soc" in
    qcom)    echo "  Qualcomm - the profile the gateway is tuned for." ;;
    exynos)  echo "  Samsung Exynos - ABOX mixer, no incall_music." ;;
    mtk)     echo "  MediaTek - no tuned DeviceProfile; own HAL and mixer vocabulary." ;;
    unknown) echo "  Unrecognised SoC - generic probes below." ;;
esac
echo

if sh_ 'pm list features' | grep -q 'feature:android.hardware.telephony'; then
    ok "telephony hardware present"
else
    no "no telephony - this device cannot place GSM calls (WiFi-only?)"
fi

# ── 1. audio policy (no root needed) - the decisive one ──────────────
echo
echo "=== 1. Audio policy: is there a route to the modem uplink? ==="
# Not every ROM keeps the policy in /vendor/etc.  Search the usual homes, and
# fall back to audio_policy.conf for pre-11 builds that predate the XML.
POL=""
for c in /vendor/etc/audio_policy_configuration.xml \
         /odm/etc/audio_policy_configuration.xml \
         /system/etc/audio_policy_configuration.xml; do
    sh_ "[ -f $c ] && echo y" | grep -q y && { POL=$c; break; }
done
LEGACY=""
if [ -z "$POL" ]; then
    for c in /vendor/etc/audio_policy.conf /system/etc/audio_policy.conf; do
        sh_ "[ -f $c ] && echo y" | grep -q y && { LEGACY=$c; break; }
    done
fi

if [ -z "$POL" ] && [ -z "$LEGACY" ]; then
    no "no audio policy found - cannot tell whether a route exists"
    detail "looked in /vendor/etc, /odm/etc, /system/etc"
elif [ -n "$LEGACY" ]; then
    no "only the legacy $LEGACY found - no incall_music concept to check"
    detail "pre-11 policy format; injection is not expressible in it"
else
    # "voice call in" is the downlink capture source on MediaTek; Qualcomm
    # spells it telephony_rx.  flat() rejoins adb's per-word output.
    NAMES_RE='(telephony_tx|telephony_rx|incall_music_uplink|incall_music_downlink|voice_tx|voice_rx|voice_call|voice call in|incall_music)'
    names=$(sh_ "grep -oiE '$NAMES_RE' $POL | sort -u" | flat)
    lnames=$(printf '%s' "$names" | tr 'A-Z' 'a-z')  # has() is case-sensitive
    detail "$(sh_ "grep -oiE '$NAMES_RE' $POL | sort -u")"

    if has "$lnames" incall_music; then
        ok "incall_music mixPort declared"
        detail "$(sh_ "grep -A6 'mixPort name=.incall_music' $POL | grep -iE 'channelmasks|sink='" | flat)"
    else
        no "no incall_music mixPort - no dedicated injection port"
    fi

    # Qualcomm spells it 'Telephony Tx', MediaTek 'telephony_tx'.
    ROUTE_GREP="grep -A1 -iE 'sink=.[^ ]*telephony' $POL | head -6"
    if sh_ "$ROUTE_GREP" | grep -q .; then
        ok "a mixPort is routed into the telephony TX path"
        detail "$(sh_ "$ROUTE_GREP" | flat)"
    else
        no "no mixPort is routed into telephony TX - nothing to inject the agent into"
    fi

    if has "$lnames" telephony_rx || has "$lnames" voice_rx || has "$lnames" "voice call in"; then
        ok "downlink capture source declared - a digital capture source can address it"
    else
        no "no downlink capture source (telephony_rx / Voice Call In) in the policy"
    fi

    if has "$lnames" incall_music; then
        detail "Necessary, not sufficient: says nothing about the HAL. See section 2."
    fi
fi

# ── 2. HAL (root) ────────────────────────────────────────────────────
echo
echo "=== 2. Audio HAL ==="
if ! have_root; then
    unk "needs root to read the HAL"
    if ! sh_ "which su" | grep -q su; then
        detail "no su on this device at all - 'su: not found', not a"
        detail "permission problem. Sections 2 and 3 cannot run here, and"
        detail "no amount of Superuser access setting will change that."
        detail "Rooting the bootloader is out of scope for this check."
    else
        detail "su exists but did not return uid 0. If this device has"
        detail "Magisk, set Superuser access = 'Apps and ADB' and run"
        detail "'adb shell su -c id' once to grant the prompt."
    fi
else
    # Globs, not fixed names: the vendor names the file after its own SoC, so
    # audio.primary.msm8953.so, .sdm660.so, .mt6768.so and .universal9820.so
    # are all the same role.  libMtk* is the one vendor family that does not
    # follow the audio.primary.* pattern.
    GLOBS="/vendor/lib64/hw/audio.primary.*.so /vendor/lib/hw/audio.primary.*.so
           /vendor/lib64/hw/audio.*.so /vendor/lib/hw/audio.*.so
           /vendor/lib64/libMtkAudio.so /vendor/lib64/libMtkVoice.so
           /vendor/lib64/hw/audio.r_submix.*.so"
    # audio.primary.default.so is the passthrough stub, not the vendor HAL.
    HAL=$(su_ "ls $GLOBS 2>/dev/null" | grep '\.so$' | grep -v default | sort -u)
    if [ -z "$HAL" ]; then
        unk "no vendor audio HAL found"
    else
        detail "$HAL"
        S=$(su_ "strings $HAL")

        # Symbol names, not enum spellings: the sm6150 HAL has no
        # AUDIO_OUTPUT_FLAG_INCALL_MUSIC string.  vsid is Qualcomm-only.
        echo "$S" | grep -qiE 'incall_?music' \
            && ok "incall_music usecases     - agent audio into the uplink" \
            || no "incall_music usecases     - agent audio into the uplink"
        echo "$S" | grep -qiE 'incall_?rec' \
            && ok "in-call record usecases    - caller into the agent" \
            || no "in-call record usecases    - caller into the agent"
        if [ "$soc" = qcom ]; then
            echo "$S" | grep -qx "vsid" \
                && ok "voice_extn vsid/call_state - needed to start that session" \
                || no "voice_extn vsid/call_state - needed to start that session"
        fi
    fi
fi

# ── 3. mixer controls (root + tinymix) ───────────────────────────────
echo
echo "=== 3. Mixer controls ==="
if ! have_root; then
    unk "needs root for mixer access"
    if ! sh_ "which su" | grep -q su; then
        detail "no su on this device at all - this section cannot run here."
    fi
else
    TM=""
    for p in /data/local/tmp/tinymix /vendor/bin/tinymix /system/bin/tinymix; do
        su_ "[ -x $p ] && echo yes" | grep -q yes && { TM=$p; break; }
    done
    if [ -z "$TM" ]; then
        unk "tinymix not present - push magisk/tinymix to /data/local/tmp/ (chmod 755)"
    else
        M=$(su_ "$TM")
        mixer_probe() {
            n=$(echo "$M" | grep -ciE "$1")
            [ "$n" -gt 0 ] && ok "$2 ($n match)" || no "$2"
        }

        # Control names differ per SoC and per codec, so ask the same three
        # questions in each vocabulary.  A miss means "not under these names",
        # not "absent": only the count is certain.
        if [ "$soc" = qcom ]; then
            mixer_probe 'incall_music'              'incall music mixer'
            mixer_probe 'voc_rec|voice.*rec'        'voice record routing'
            mixer_probe 'voice.*(rx|tx).*mute'      'voice device mutes'
        elif [ "$soc" = exynos ]; then
            mixer_probe 'abox.*nsrc'                'ABOX NSRC bridge'
            mixer_probe 'spus'                      'ABOX SPUS'
            mixer_probe 'swp|spk'                   'speaker amp controls'
        else
            mixer_probe 'incall.*music'             'incall music mixer'
            mixer_probe 'voice.*(rec|uplink|downlink)' 'voice rec/uplink mixer'
            mixer_probe 'MMAudio.*Mix'              'MMAudio playback mixer'
            mixer_probe 'AHB_Input'                 'AHB input mixer'
            mixer_probe 'APO.*Mix'                  'APO mixer'
        fi

        MP=$(sh_ "grep -ciE 'voice|incall' /vendor/etc/mixer_paths.xml")
        [ -n "$MP" ] && detail "mixer_paths.xml voice/incall lines: $MP"
    fi
fi

# Mirrors DeviceProfile.detect(): ro.board.platform first — the only SoC key
# present on every ROM (ro.soc.* is Android 12+ and was empty on an Android 10
# Redmi reporting the same sm6150 chip) — then ro.soc.model, then BOARD.
detect_profile() {
    chip="$platform $socmodel $board"
    if has "$chip" exynos9820 || { has "$hw" exynos && has "$model" sm-g970; }; then
        echo "exynos9820()"
    elif has "$chip" sm6150 || has "$chip" sm7150; then
        echo "sm6150()"
    elif has "$hw" qcom || has "$hw" qualcomm || has "$socmaker" qualcomm || [ "$socmaker" = qti ]; then
        echo "genericQualcomm()"
    elif has "$hw" exynos || has "$hw" samsung || has "$socmaker" samsung; then
        echo "genericExynos()"
    else
        echo "generic()"
    fi
}

# ── verdict ──────────────────────────────────────────────────────────
profile=$(detect_profile)
echo
echo "=== Verdict ==="
if [ "$fail" -eq 0 ] && [ "$unknown" -eq 0 ]; then
    echo "  Fully supported on paper: $pass/$pass checks passed."
elif [ "$fail" -eq 0 ]; then
    echo "  Inconclusive: $pass checks passed, $unknown could not be checked."
    echo "  The unchecked ones are what this verdict turns on."
elif [ "$soc" = unknown ] && [ "$pass" -le 1 ]; then
    echo "  Not supported: nothing required is present."
else
    echo "  Partial: $pass present, $fail missing, $unknown unchecked."
fi

case "$soc" in
    exynos)
        echo
        echo "  Exynos: capture is acoustic unless the ABOX bridge can be"
        echo "  rerouted - see exynos9820() in DeviceProfile.kt."
        ;;
    mtk)
        echo
        echo "  MediaTek: no DeviceProfile is tuned for this SoC. Nothing here"
        echo "  is established beyond the lines above."
        ;;
    unknown)
        echo
        echo "  Unrecognised SoC. The mixer names in section 3 are a guess, so"
        echo "  treat that section as unproven; section 1 still stands."
        ;;
esac

echo
echo "  DeviceProfile: detect() returns $profile."
case "$profile" in
    "sm6150()"|"exynos9820()")
        echo "  A tuned entry exists for this board."
        ;;
    "generic()")
        echo "  That is the no-op profile: it runs no mixer commands, so a usable"
        echo "  route would not be driven. A new detect() entry is the next step."
        ;;
    *)
        echo "  Generic: the control names are right for the SoC, but which"
        echo "  front-end the playback track lands on is not. The 'Mixer"
        echo "  BEFORE/AFTER' lines logged around each call show it."
        ;;
esac

[ "$fail" -gt 0 ] && [ "$pass" -le 1 ] && exit 1
exit 0
