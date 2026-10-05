<p align="center">
  <img src="icon.png" width="128" alt="GSM2SIP">
</p>

<h1 align="center">GSM2SIP</h1>

<p align="center">
Bridges Android phone to any SIP server as GSM Gateway
</p>

| Calls and messages | Link detail | Settings |
|--------|-----------------|----------|
| <img src="screen1.png" width="300"> | <img src="screen2.png" width="300"> | <img src="screen3.png" width="300"> |

## How It Works

A dedicated rooted Android phone with a local SIM card acts as a SIP-to-GSM gateway:

- **Inbound**: Someone calls the SIM's number → the phone answers → the call is bridged to the SIP server, which routes it wherever the dialplan says (an AI agent, a queue, an extension)
- **Outbound**: the SIP server sends an INVITE with `X-GSM-From: +<SIM-number>` and `X-GSM-To: +<destination>` → the phone dials the destination over GSM → audio is bridged back to SIP
- **SMS**: messages arriving on any SIM are forwarded to the server as SIP MESSAGE, and the server can ask the gateway to send one and be told what became of it — see [SMS over SIP](#sms-over-sip)

Audio is **digital in both directions** on a supported device: the agent's voice is
injected into the GSM uplink through the `incall_music` mixer, and the caller is
captured off the modem downlink. The phone's own microphone and speaker are muted
for the duration of the call, so there is no acoustic echo path. A Magisk module
disables Android's audio concurrency restrictions so capture and injection can run
at the same time. Devices without that path (see [Supported Devices](#supported-devices))
degrade to shared speaker/mic, which is lossy and prone to echo.

## Audio Codec

Selectable in Settings, defaulting to **G.722 only** (wideband, 16 kHz):

| Setting | Offered in SDP |
|---|---|
| G.722 only *(default)* | `9 101` |
| G.722 preferred, G.711 allowed | `9 8 0 101` |
| G.711 only | `8 0 101` |

Payload `101` is `telephone-event` (DTMF), offered in every mode. The app does
not consume in-band DTMF; it is advertised so the offer is complete.

The default is G.722 alone because offering G.711 alongside it means servers
routinely pick G.711 and every call ends up narrowband regardless of what both
ends support.  Whatever is configured, the app still answers with a codec the
remote actually offered rather than failing a call over a preference.

## Supported Devices

| Device | SoC | Agent → caller | Caller → agent | Status |
|---|---|---|---|---|
| Redmi Note 7 Pro (`violet`) | Qualcomm SM6150, WCD9375 | digital, via `incall_music` → `Telephony Tx` | digital, via `VOICE_DOWNLINK` | **fully working** |
| Xiaomi Poco X3 NFC (`surya`) | Qualcomm SM6150/SM7150, WCD9375 | digital | digital | **fully working** |
| Samsung Galaxy S10e | Exynos 9820, CS47L93 | no path | no path | not usable |
| Nokia 6.1 Plus (`sdm636`) | Qualcomm Snapdragon 636/660 | no path | digital | not usable |

**The Redmi Note 7 Pro is the reference device and works fully**: G.722 wideband
in both directions, entirely through the modem, with the handset's own microphone
and speaker muted for the whole call.

The Nokia 6.1 Plus is the useful negative case: its audio policy routes
`Telephony Tx` but declares no `incall_music` mixPort to feed it, so there is
nothing to inject into. A Qualcomm chip is necessary but not sufficient — the
policy check in `tools/check-device` settles this without root.

### Choosing a device

Support is a property of the **vendor image, not the chip**.  Everything the
SM6150 profile relies on — the `incall_music` mixer, the `VOC_REC_*` capture
routing, the `voice_extn` `vsid`/`call_state` interface — is generic Qualcomm
audio, confirmed working on two Snapdragon 600/700-series parts (MSM8953 and
SM6150). The Nokia 6.1 Plus is Snapdragon 636/660 and shares the family, yet has
no injection path at all, which is the point: check the policy, do not infer
support from the model number.

Anything below Android 12 is out of scope — the APK requires API 31. The
Galaxy S4 Mini (MSM8930) profile was removed on that basis; a handset it had
matched now selects `Generic Qualcomm`, which does **not** silence the handset
microphone. If one somehow runs this build, treat the echo as "unsupported",
not "misconfigured".

Check a candidate:

```bash
tools/check-device.sh [adb-serial]     # Linux
tools\check-device.ps1 [adb-serial]    # Windows
```

The two are independent implementations of the same checks and agree on the
verdict, though the wording and colour differ by platform. Pass a serial
whenever more than one device is attached; without one the script refuses
rather than guessing. Exit `0` supported, `1` checked and not usable, `2`
could not check — no device, wrong serial, or `unauthorized`, which is worth
distinguishing from a real negative result.

The audio-policy check needs no root, so a phone can be vetted before rooting
it.  The HAL and mixer checks need Magisk with Superuser access set to
"Apps and ADB". A device with no `su` at all cannot run those two sections;
that is a property of the handset, not a permission problem.

What has actually been checked so far:

| Device | SoC | Vendor | Result |
|---|---|---|---|
| Redmi Note 7 Pro | Snapdragon 660 (MSM8953) | Xiaomi (LineageOS 16) | fully working, verified on live calls |
| Redmi Note 7 Pro | Snapdragon 660 (MSM8953) | Xiaomi (Android 10) | policy checks pass; unrooted, so untested end to end |
| Poco X3 NFC | SM6150/SM7150 | Xiaomi (MIUI) | fully working, verified on live calls |
| Nokia 6.1 Plus | Snapdragon 636/660 | HMD (LineageOS 14) | no `incall_music` mixPort — no injection path |
| Galaxy S10e | Exynos 9820 | — | no path in either direction |
| POCO C65 | MediaTek Helio G99 (mt6768) | Xiaomi (Android 15) | policy exposes an `incall_music` port; unrooted, so undetermined |

Everything the working profile depends on is generic Qualcomm audio, so other
Qualcomm phones are plausible candidates — but the deciding factors live in the
vendor image, which is exactly what the script above inspects.  A new device
still needs a `DeviceProfile` entry: the mixer names are generic, the front-end
the playback track lands on is not, and the `Mixer BEFORE/AFTER` lines logged
around each call show which one it is.  An unrecognised Qualcomm device falls
back to `genericQualcomm()`.

### Which profile gets selected

`DeviceProfile.detect()` keys on the **SoC**, not the model, and reads it in
this order: `ro.board.platform`, then `ro.soc.model`, then `ro.product.board`.
`ro.board.platform` is first deliberately. `ro.product.board` is a build
property the ROM vendor chooses, and the same Redmi Note 7 Pro reports `sm6150`
on one ROM and `violet` — the product codename — on another. Matching on board
alone silently demoted it from `sm6150()` to `genericQualcomm()`, which costs
MultiMedia5, the `voice_extn` call_state announcement and the mic mute, while
logging nothing: every mixer command ends in `2>/dev/null`, so a control that
does not exist looks like one that worked. `ro.soc.*` is no substitute for
`ro.board.platform` either — it only exists from Android 12, and was empty on
the Android 10 build of the same phone.

Selections are logged at start-up as `Detecting device: …` and
`Selected profile: …`.

Getting digital capture on a Qualcomm device depends on one thing that is easy
to miss.  The HAL gates in-call recording — and the per-session voice mutes —
on `voice_is_call_state_active()`, and on LineageOS that flag is never set:

```
voice_extn: update_call_states is_call_active:0 in_call:1, mode:2
```

`MODE_IN_CALL` is set and the modem's voice session is running, yet every VSID
stays `CALL_INACTIVE`, so `VOICE_CALL`, `VOICE_DOWNLINK` and the `VOC_REC_*`
mixers all return silence.  The app announces the call itself
(`vsid=<hex>;call_state=2`) before opening `AudioRecord`.

The Exynos S10e has no equivalent, and this was confirmed on the hardware
rather than inferred: `audio_policy_configuration.xml` declares three mixPorts
(`deep`, `fast`, `primary`) and no telephony device, and
`audio.primary.universal9820.so` contains no `TELEPHONY_TX`, no `incall_rec`
usecases and no `voice_extn` `call_state`/`vsid` handling.  There is nothing to
inject into and nothing to capture from, so neither direction can be digital.
That is why the gateway moved to a Qualcomm device.

## Requirements

- **Device**: Qualcomm-based Android phone with Magisk root. The
  `voice_extn` `call_state` behaviour described under
  [Choosing a device](#choosing-a-device) was measured on LineageOS; another
  ROM's HAL may not need the workaround, or may need more. Verified end to end
  on a Redmi Note 7 Pro (LineageOS 16) and a Poco X3 NFC.
- **SIM**: SIM card with voice plan
- **Network**: Stable WiFi connection
- **Power**: Always connected to charger
- **Build host**: JDK 17+ (the minimum Android Gradle plugin 9.0 accepts), plus
  the Android SDK with platform `android-34` and build-tools `36.0.0` (AGP 9.0's
  default — it is not declared in the build file). Gradle 9.7.1 and AGP 9.0.0
  are resolved by the wrapper, so no local Gradle install is needed.

## Download

Prebuilt APK and Magisk module:
**[github.com/TechThrives/GSM2SIP/releases](https://github.com/TechThrives/GSM2SIP/releases)**

Install the Magisk module — it carries the APK as a privileged system app.
Building from source is only needed to change something; see below.

## Build

Linux:

```bash
chmod +x build.sh
./build.sh          # debug build
./build.sh release  # release build
```

Windows (PowerShell):

```powershell
.\build.ps1          # debug build
.\build.ps1 release  # release build
```

Both scripts locate the Android SDK, write `local.properties`, build the APK and
package the module. That file must have **no UTF-8 BOM** — with one, AGP reads
the key as `﻿sdk.dir` and fails with `SDK location not found` even
though the file looks right. The bundled scripts write it correctly; `ANDROID_HOME`
also works and takes precedence.

Outputs:
- `gateway-magisk.zip` — Magisk module containing the APK, permissions, and audio tools (tinymix, tinycap). This is the only file you need to install.

The APK itself is architecture-independent, but `tinymix` is not: the ALSA
control ioctls encode the size of structs containing `long`, so an arm64 build
and an armeabi-v7a build speak different ioctl ABIs and neither works on the
other's kernel.  The module ships both and `install.sh` picks the matching one
at flash time.  Getting this wrong fails quietly rather than loudly — the wrong
binary is still marked executable, so it looks present while every mixer
command dies with `not executable: 64-bit ELF file`.

## Device Setup

Only the Magisk module needs to be installed — it includes the APK and handles all permissions automatically.

1. **Install Magisk module**: Copy `gateway-magisk.zip` to device, install via Magisk Manager → Modules
2. **Reboot** the device — the module installs the APK as a privileged system app and grants all permissions on boot
3. **Set as default phone app**: Settings → Apps → Default apps → Phone app → GSM2SIP
4. **Configure SIP**: open Settings in the app (the gear, top right) and enter
   your SIP server address, port, username and password
5. **Own Number**: Enter the SIM's own number in strict international E.164
   format, e.g. `+4915112345678`. It is used as the `X-GSM-To` value for
   incoming calls and as the source identity for SMS. The gateway rejects a
   call when no valid own number is available.
6. **Start**: the gateway registers and begins bridging calls; the header pill
   shows **Online** once registration succeeds (tap it to retry)

### What the SIP leg carries

- **Request-URI** — SIP peer/account addressing only.
- **X-GSM-From** — the human caller, normalized to strict `+E.164`.
- **X-GSM-To** — the receiving SIM's number, normalized to strict `+E.164`.

The server must route from the mandatory `X-GSM-*` headers. The Request-URI is
not a routing fallback.

## Quick start with callagent.pro

Once installed, you can use GSM2SIP instantly with a free
[callagent.pro](https://callagent.pro) registration. The account is a SIP
server that already knows how this gateway addresses calls and messages, so
there is nothing to write on the server side:

1. Register at [callagent.pro](https://callagent.pro) and create an extension.
2. Open the gateway's **Settings** and fill in the server, extension and
   password, plus the SIM's own number under **Own Number**.
3. Save. The gateway registers, and calls to the SIM reach your agent.

## The longer way: your own Asterisk

The gateway is server-agnostic and registers like a SIP client. This is the
minimum a server has to provide:

| Requirement | Value |
|---|---|
| Registration | digest auth; the gateway registers as a single contact |
| Codec | `G.722` (payload `9`); `101` telephone-event is always offered |
| Inbound call routing | `X-GSM-From` and `X-GSM-To`, both strict `+E.164` |
| SMS routing | `X-SMS-From` and `X-SMS-To`, both strict `+E.164` |

The Request-URI only addresses the peer and is **never** used as a routing
fallback — an INVITE missing either header is refused with `488`.

Outbound, the server supplies the same pair through ARI/PJSIP variables; the
gateway's own reports add `X-SMS-Id` and `X-SMS-Event` alongside the two
routing headers.

### Outgoing calls through the gateway

Both routing headers are mandatory. `X-GSM-From` identifies the SIM that must
place the GSM call, and `X-GSM-To` is the human destination. The Request-URI
only addresses the SIP account and is not used as a fallback.

```ini
exten => _X.,1,NoOp(Outbound via GSM gateway: ${EXTEN})
 same => n,Set(PJSIP_HEADER(add,X-GSM-From)=${SIM_NUMBER})
 same => n,Set(PJSIP_HEADER(add,X-GSM-To)=${EXTEN})
 same => n,Dial(PJSIP/1001,60)
 same => n,Hangup()
```

With ARI, pass the same PJSIP variables when originating the channel:

```text
PJSIP_HEADER(add,X-GSM-From)=+4915112345678
PJSIP_HEADER(add,X-GSM-To)=+4917098765432
```

Allow enough time in `Dial()` for GSM setup — 60s is comfortable, 20s is not.

## Call status codes

What the gateway answers with is a property of the gateway, not of any one
server, so this holds whichever server you point it at. It reports progress
and failure the way a provider does — on Asterisk that means the dialplan can
branch on `${DIALSTATUS}` and `${HANGUPCAUSE}` instead of guessing.

### Outbound — the server asks the gateway to dial

| Situation | Response |
| --- | --- |
| INVITE received | `100 Trying` |
| Dialling the SIM | `180 Ringing` |
| The mobile answered | `200 OK`, then RTP |
| Callee busy | `486 Busy Here` |
| Callee declined | `603 Decline` |
| No answer, or unreachable | `480 Temporarily Unavailable` |
| Cancelled at the handset | `487 Request Terminated` |
| Number barred | `403 Forbidden` |
| Gateway already on a call | `486 Busy Here` |
| No destination in the INVITE | `488 Not Acceptable Here` |
| Anything failing after the answer | `BYE` |

The `180` carries no SDP, deliberately. Without an SDP answer the server
cannot open an early-media path, so an agent cannot be bridged into a call the
mobile has not picked up yet — if you ever hear the agent start talking before
you answer, something other than this gateway put it there. `183 Session
Progress` is never sent for that reason.

A `488` means the INVITE did not contain both mandatory GSM routing headers,
or `X-GSM-From` did not resolve to an active SIM.

Only a call the gateway answered is ended with `BYE`. One that never connected
is turned down with the final response above, which is the only place the
server learns why the GSM leg did not come up — a `BYE` for an unanswered
INVITE is not valid, and a server that gets one replies `481` and then sits
out its own timer, which makes every failure look alike and look like a
timeout.

### Inbound — the SIM rings and the gateway calls the server

Here the gateway is the caller, so these are the responses it acts on. It
places the INVITE while the GSM leg is still ringing and answers the GSM call
only once the server sends `200 OK`, so the caller hears normal ringing until
the agent is actually on the line, with no dead air at the join.

| Server sends | Gateway does |
| --- | --- |
| `100` / `180` / `183` | Keeps the GSM leg ringing |
| `200 OK` | Answers the GSM call and starts the bridge |
| `486` / `603` / any 4xx-6xx | Ends the GSM call |
| No response | Retries, then gives up and ends the GSM call |

## SMS over SIP

Messages travel as page-mode SIP MESSAGE (RFC 3428) over the registration that
is already up for calls — no second connection, no extra port, nothing for the
server to reach through the NAT.

The gateway does **not** take the default-SMS-app role. Receiving works through
`SMS_RECEIVED`, which reaches any app holding `RECEIVE_SMS`, and sending needs
only `SEND_SMS`; the role exists to *store* messages, which a gateway has no
reason to do. Both permissions are granted by the Magisk module, and the app
re-grants them over root at bring-up if they are missing.

### Received SMS → server

One MESSAGE per message, already reassembled from its parts:

```
MESSAGE sip:1001@example.com SIP/2.0
From: <sip:+4917098765432@example.com>;tag=gw123456789
To: <sip:+4915112345678@example.com>
X-SMS-Id: 550e8400-e29b-41d4-a716-446655440000
X-SMS-From: +4917098765432
X-SMS-To: +4915112345678
X-SMS-Received: 2026-09-08T16:20:31Z
X-SMS-Parts: 1
Content-Type: text/plain;charset=UTF-8

Hello from a mobile
```

The Request-URI addresses the registered SIP account (`1001`); `X-SMS-From`
and `X-SMS-To` are the authoritative routing pair. `X-SMS-Id` is the idempotency key: answer
`200` or `202` and the message is done, answer anything else — or nothing — and
the same id is retried every 30s until it lands. A message that arrives while
SIP is down is written to disk and sent when registration returns.

### Server → SMS

```
MESSAGE sip:1001@example.com SIP/2.0
X-SMS-Id: 0ca1c8ad-27e1-4c9e-b6b4-41abca9805d3
X-SMS-From: +4915112345678
X-SMS-To: +4917098765432
Content-Type: text/plain;charset=UTF-8

Reply from the agent
```

`X-SMS-From` and `X-SMS-To` are both mandatory. `X-SMS-From` is the SIM number
that must send the message and must resolve to exactly one active SIM;
`X-SMS-To` is the human recipient. The Request-URI only addresses the SIP
account and is never used as a substitute for either header. Device-local
subscription IDs and SIM slots are intentionally not exposed on the wire.

The response says what the message will cost before you commit to the text:

```
SIP/2.0 202 Accepted
X-SMS-Parts: 1
X-SMS-Encoding: GSM7
```

**Only `202` is a promise.** `400` missing `X-SMS-From`/`X-SMS-To`, an unknown
source SIM, or an empty body; `415` body is not
`text/plain`, `503` `SEND_SMS` not granted (retry later), `405` SMS handling
unavailable — none of those queued anything. A repeat carrying an id already
held is answered `202` and not sent again.

One character outside GSM-7 forces the whole message to UCS-2, which cuts a
part from 160 characters to 70: an 88-character reply is one part in plain
ASCII and two with a single em dash in it. `X-SMS-Encoding` is how a sender
finds that out in time to do something about it.

### Delivery reports

Two reports come back as SIP MESSAGE requests addressed to the registered
account (`1001`). `X-SMS-Event` tells reports apart from inbound SMS, while
`X-SMS-From` and `X-SMS-To` retain the original source-SIM and human-recipient
routing pair.

```
X-SMS-Id: 0ca1c8ad-27e1-4c9e-b6b4-41abca9805d3
X-SMS-Event: submitted
X-SMS-From: +4915112345678
X-SMS-To: +4917098765432
X-SMS-Parts: 1
X-SMS-At: 2026-09-08T16:56:09Z
Content-Type: text/plain;charset=UTF-8

{"id":"0ca1c8ad-…","event":"submitted","to":"+4917098765432","parts":1,
 "sentOk":1,"sentFailed":0,"deliveredOk":0,"deliveredFailed":0,
 "at":"2026-09-08T16:56:09Z"}
```

| `X-SMS-Event` | meaning | more follows? |
|---|---|---|
| `submitted` | the network accepted it | yes — a delivery result |
| `failed` | the network refused it; see `X-SMS-Reason` | no, terminal |
| `delivered` | the SMSC confirmed it reached the handset | no, terminal |
| `undelivered` | the SMSC reported permanent failure | no, terminal |

`X-SMS-Reason` names the failure rather than numbering it — `no_service`,
`radio_off`, `limit_exceeded`, or the RIL code paired with the network's own
cause, e.g. `modem_err/facility_rejected` when the carrier refuses the
submission. `X-SMS-Status` carries the SMSC's status value, or `unknown` when
the report arrived without a readable PDU, so an inferred result never looks
like a stated one.

A report also carries `X-SMS-Smsc` (the handling service centre, from the
delivery-report PDU) and `X-SMS-Encoding` (`GSM7`, `UCS2`, `8BIT`) — both only
known once the message has gone out, so omitted when unknown. `X-SMS-At` is when
the report was produced, not when the message was sent.

A re-sent inbound message carries `X-SMS-Attempt`, counting from 2. It means the
gateway never saw the response last time; the `X-SMS-Id` is unchanged, so it is
the same message.

Reports are retried like anything else: answer `200`/`202`, or the gateway
sends them again, including after the next registration.

### Asterisk (chan_pjsip)

Everything below is chan_pjsip / res_pjsip. Three properties of that driver
govern the whole SMS path and are worth stating once, because each one is a
mistake if assumed otherwise:

- Out-of-call MESSAGEs are enabled per **endpoint** with `message_context`.
  That is the only switch; nothing is needed in a general section, and inbound
  MESSAGE is authenticated by the registration the calls already use.
- The channel's **exten is the Request-URI's user part**.
  `rx_data_to_ast_msg` in res_pjsip_messaging.c copies it out of the R-URI, so
  a MESSAGE addressed to `sip:1001@example.com` lands on exten `1001` in the
  message context — not on a literal `s`.

```ini
; pjsip.conf — on the endpoint the gateway registers as
[1001]
type=endpoint
...
message_context=sms-from-gsm
```

The gateway is registered, so it also needs no identify rule for inbound
MESSAGEs: res_pjsip resolves the endpoint from the authenticated request, and
MESSAGE is accepted on the same registration the calls use.

#### Receiving: SMS, reports and gateway announcements

Everything arrives at the registered account, so one context handles it. The
mandatory `X-SMS-From`/`X-SMS-To` pair carries SMS routing, `X-SMS-Event`
separates an inbound message from a report on something we sent, and
`X-GW-Event` marks a gateway lifecycle announcement — which is not an SMS and
must not be handled as one.

Note `${MESSAGE(body)}` for the text; the `X-SMS-*` and `X-GW-*` values are SIP
headers, so they are read with `PJSIP_HEADER(read,...)`.

```ini
; extensions.conf — exten is the registered account user (1001).
; Log-only: SMS, reports and announcements are consumed over ARI by the API,
; which is subscribed to every endpoint Asterisk knows. One message context
; per device — copy the block for each new gateway.
[sms-from-gsm-1001]
exten => _X.,1,NoOp(MESSAGE ${MESSAGE(from)} -> ${MESSAGE(to)})
 same => n,Set(GW_EVENT=${PJSIP_HEADER(read,X-GW-Event)})
 same => n,GotoIf($["${GW_EVENT}" != ""]?gateway,sms)

; A registration announcement: device identity, audio profile, own numbers.
; Not an SMS — hand it to whatever tracks devices, or just log it.
 same => n(gateway),NoOp(GW ${GW_EVENT} id=${PJSIP_HEADER(read,X-GW-Id)} profile=${PJSIP_HEADER(read,X-GW-Profile)} numbers=${PJSIP_HEADER(read,X-GW-Numbers)})
 same => n,Hangup()

; A received SMS. X-SMS-From is the human sender and X-SMS-To is the
; receiving SIM. MESSAGE(body) is the reassembled text.
 same => n(sms),Set(ID=${PJSIP_HEADER(read,X-SMS-Id)})
 same => n,Set(EVENT=${PJSIP_HEADER(read,X-SMS-Event)})
 same => n,Set(FROM=${PJSIP_HEADER(read,X-SMS-From)})
 same => n,Set(TO=${PJSIP_HEADER(read,X-SMS-To)})
 same => n,NoOp(SMS id=${ID} event=${EVENT} From=${FROM} To=${TO})
 same => n,Hangup()
```

`submitted` is not the end of the story — a `delivered` or `undelivered`
follows it — so a handler that closes the message out on the first report
closes it too early. `failed` and `delivered`/`undelivered` are terminal.

#### Content-Type

An out-of-call MESSAGE must be `text/plain`. `check_content_type()` in
res_pjsip_messaging.c accepts only that and answers `415` for anything else,
so an `application/json` body is refused outright. (`check_content_type_in_dialog`
is laxer and takes `text/*` and `application/*`, but that is the in-dialog
path and does not apply to an out-of-call MESSAGE.) Put the JSON in the body
with `text/plain` and mirror every field into a header — which is what the
gateway does for both SMS and announcements, and what makes `PJSIP_HEADER()`
sufficient without parsing anything.

#### Sending

Outbound SMS never touches the dialplan: the API sends it over ARI
(`PUT /endpoints/{tech}/{resource}/sendMessage`) with `?from=` naming the
endpoint that owns the sending SIM. The Request-URI carries the account name
and both routing values travel as mandatory headers: `X-SMS-From` selects the
sending SIM and `X-SMS-To` names the human recipient.

`X-SMS-Id` is mandatory. The sender reuses the same stable id for retries so
the gateway can deduplicate the request and match delivery reports.

Nothing above is dialplan-only — AMI's `MessageSend` action and ARI's
`PUT /endpoints/{tech}/{resource}/sendMessage` take the same body and variables.

#### SMS delivery notes

The gateway requires both strict E.164 routing headers:

```text
X-SMS-From
X-SMS-To
```

`X-SMS-From` must resolve to exactly one active SIM. Device-local subscription
IDs and SIM slots are not wire fields and are never used as fallbacks. The SIP
account in the Request-URI only addresses the registered peer.

Carriers also rate-limit SMS independently of anything here: a run of sends can
end in `modem_err/facility_rejected` for every destination, including the SIM's
own number, until the allowance resets.

## Architecture

```
┌─────────────────┐     GSM      ┌──────────────────┐
│  Remote Caller   │◄───────────►│  Android Phone    │
│  (local #)       │   voice     │  (Redmi + SIM)    │
└─────────────────┘              │                    │
                                 │  ┌──────────────┐ │
                                 │  │ InCallService │ │  GSM call control
                                 │  └──────┬───────┘ │
                                 │         │         │
                                 │  ┌──────▼───────┐ │
                                 │  │ Orchestrator  │ │  Bridges GSM ↔ SIP
                                 │  └──────┬───────┘ │
                                 │         │         │
                                 │  ┌──────▼───────┐ │
                                 │  │  SIP Client   │ │  Registration + calls
                                 │  │  RTP Session  │ │  G.722 audio stream
                                 │  └──────┬───────┘ │
                                 └─────────┼─────────┘
                                           │ SIP/RTP
                                           │ (WiFi)
                                 ┌─────────▼─────────┐
                                 │    SIP Server      │
                                 │  (Asterisk, etc.)  │
                                 └─────────┬─────────┘
                                           │
                                 ┌─────────▼─────────┐
                                 │ Agent / queue /   │
                                 │ extension         │
                                 └───────────────────┘
```

## Magisk Module

The `gateway-magisk.zip` module carries the APK and prepares the device for it:

1. **Installs the app as a privileged system app** (`system/priv-app/Gateway`)
   and grants it the telephony privileges it needs
   (`privapp-permissions-gateway.xml`):
   - `CAPTURE_AUDIO_OUTPUT` — capture audio from other sources
   - `MODIFY_PHONE_STATE` — control telephony
   - `READ_PRIVILEGED_PHONE_STATE` — detailed call state info
   - `CALL_PRIVILEGED` — place the outbound GSM leg

2. **Lifts Android's audio concurrency limits** (`system.prop`), so a call can
   be captured and agent audio injected at the same time:
   - `voice.voip.conc.disabled=false` — VoIP audio during GSM calls
   - `voice.record.conc.disabled=false` — audio recording during calls
   - `voice.playback.conc.disabled=false` — audio playback during calls

3. **Hides PermissionController** (`.replace` overlay). It otherwise sets the
   `RECORD_AUDIO` app op to `MODE_FOREGROUND`, which denies `AudioRecord` to a
   foreground service started at boot with no Activity in the foreground —
   silently, with `AudioRecord` returning zeros. See Troubleshooting.

4. **Prepares the runtime environment** on boot: grants the 11 runtime
   permissions, seeds the Magisk superuser policy to allow so a headless
   gateway never stalls on a prompt nobody is there to answer, lifts the
   outgoing-SMS rate limit, and silences the default SMS app's notifications.

5. **Deploys `tinymix`** matching the device ABI — see the ABI note under
   [Build](#build).

Reboot to activate.

## Troubleshooting

- **Agent hears silence**: the foreground service must be started while an
  Activity is visible. Android 12+ withholds `PROCESS_CAPABILITY_FOREGROUND_MICROPHONE`
  from a service started in the background, and AudioPolicy then feeds
  `AudioRecord` zeros without any error (`rec update ... silenced` in
  `dumpsys audio`). The module launches the UI on boot for this reason.
- **Agent hears itself / heavy noise**: capture must use `VOICE_DOWNLINK`, not
  `VOICE_CALL`. The latter mixes uplink and downlink, and the uplink carries
  the injected agent audio.
- **Caller hears the room or their own echo**: the phone's mic is in the GSM
  uplink. Muting it only works through `AudioManager` — the ALSA voice mutes
  are rewritten by the HAL, and they are 3-element arrays
  (`{mute, session_vsid, ramp_ms}`), so a single-value `tinymix` write silently
  does nothing.
- **Calls loop back and never answer**: the Own Number setting is unset, so the
  gateway is INVITEing its own extension.
- **One-way audio**: Ensure the Magisk module is installed and device is rebooted
- **Echo**: On the digital path there is no acoustic echo to cancel — capture
  reads the modem downlink, not the microphone. If you are hearing echo, the
  device is running the acoustic fallback, so check which `DeviceProfile` was
  selected (logged as `Selected profile:` on start-up). Note that playback uses
  `USAGE_MEDIA`, not `USAGE_VOICE_COMMUNICATION`: the latter does not reach the
  modem uplink on Qualcomm.
- **SIP not registering**: Check WiFi connectivity, server address, and credentials
- **Calls not auto-answering**: Ensure the app is set as the default phone app
- **Audio drops**: Check WiFi stability; the app holds a WiFi lock but poor signal will cause issues
