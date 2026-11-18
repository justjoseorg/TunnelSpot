# TunnelSpot

An **experimental, no-root Android WireGuard hotspot** using the official
WireGuard Android interface. Save multiple profiles, import `.conf`/ZIP files or
scan a QR code, then use a profile's switch to start/stop WireGuard and Wi-Fi
sharing together.

**Status: implementation/build validation only, not verified on a physical
Android device. Do not treat this as a proven leak-proof VPN gateway.**

## What goes through WireGuard?

**Intended behavior (device verification pending):** Only devices connected to
the phone's **Wi-Fi hotspot**. Phone apps keep their
normal connection. Clients do not install an app or configure a proxy.
TunnelSpot does not create an Android `VpnService`, change app include/exclude
lists, or change the phone's default network.

This version carries **IPv4 TCP, UDP, and DNS**. It does not advertise an IPv6
upstream to clients. IPv6-only destinations are not supported. USB, Bluetooth,
and Ethernet sharing are not managed by this version; leave them disabled.
Providers must supply standard WireGuard configurations; OpenVPN and
proprietary provider apps are not implemented.

## Requirements and setup

1. Android **13 or newer**, with Wi-Fi tethering and compatible test-network
   APIs. OEM support is not guaranteed.
2. Enable Developer options (tap Build number seven times in Android settings)
   and **Wireless debugging** while the phone is connected to Wi-Fi.
   **No root, companion APK, or computer is required.**
3. Open TunnelSpot → **Hotspot setup** → **Start pairing notification** and
   allow notifications. In Android's Wireless debugging screen, choose
   **Pair device with pairing code**. Keep the dialog open; pull down
   notifications and enter its six-digit code in TunnelSpot's notification.
   Alternatively, use split screen to enter the pairing port and code manually.
   Return to TunnelSpot and tap **Start built-in helper**. If discovery fails,
   enter the connection port from the main Wireless debugging screen (not the
   temporary pairing port). After startup you can disable Wireless debugging.
   Restart the helper after reboot, app restart, or update; an unused helper
   exits after ten minutes. Pair again if Android forgets the app.
4. Configure the hotspot SSID/password in Android's hotspot settings. Leave the
   hotspot **off** before starting TunnelSpot. TunnelSpot uses the system's saved
   credentials; it does not collect or store the Wi-Fi password.
5. Import a WireGuard profile with exactly one IPv4 interface address, an IPv4
   DNS server, `AllowedIPs = 0.0.0.0/0`, and MTU 1280–1500 (default 1280).
   A dual-stack profile is accepted, but this gateway uses its IPv4 address.
6. Turn the profile on. TunnelSpot prepares its tunnel, starts Wi-Fi sharing, and
   checks Android selected the tunnel upstream. Connect clients to the saved SSID.
   The notification or the same switch stops both.

All imported private keys remain in the app's private storage, as in upstream;
Android backups are disabled. They are never sent to the shell helper.
Profiles are **not additionally encrypted at rest**.

Pairing grants this app **ADB shell privileges**, broader than normal Android
permissions. Only pair your own device. The built-in ADB client always connects
to `127.0.0.1`; discovery accepts only local services. Its RSA identity is stored
encrypted with an Android Keystore AES-GCM key in non-backed-up app storage.
Pairing uses Android's TLS/SPAKE protocol, with a 30-second transport deadline.
The ADB connection is closed after helper startup; there is no remote-command
UI or network listener accepting remote clients. To revoke access, forget
TunnelSpot under **Wireless debugging → Paired devices**. Stop the hotspot and
helper before clearing app data or uninstalling. Owner-user installs are the
target; secondary users/work profiles have not been validated.

## How no-root sharing is possible

The embedded ADB client launches an `app_process` helper as **shell UID 2000**,
never root. A shell-only, `DUMP`-protected provider accepts its Binder only
during startup using a random, single-use 256-bit challenge and matching app
version. The helper checks callers against the owning app UID and exposes
only fixed hotspot operations, not a general shell interface.

That helper uses Android's hidden `TestNetworkManager` to
create a TUN with the WireGuard profile's IPv4 address. The app attaches the
upstream `wireguard-go` engine directly to that file descriptor. The helper
uses `TetheringManager.setPreferTestNetworks(true)` to select the test network
as Wi-Fi tethering upstream. Android handles DHCP, DNS forwarding and IPv4 NAT.
WireGuard's encrypted UDP sockets use the phone's regular internet connection.

The test network is not internet-capable for ordinary app/default-network
selection. **No root, iptables rules, shell command injection, client proxy, or
phone-wide VPN is used.** The helper refuses an already-running Wi-Fi hotspot.
Only one saved profile is active at a time; switching stops the old hotspot first.

### Important limitations

These are **hidden, device-dependent APIs**, not a supported Android VPN-sharing
contract. Setup may fail, an OEM may deny access, or Android may temporarily
select cellular/Wi-Fi as upstream. TunnelSpot checks upstream callbacks and
polls every second, and attempts to stop Wi-Fi if selection changes. It stops
Wi-Fi before dismantling the test network on normal teardown and watches the
app's Binder lifetime in the helper.

**This cannot guarantee a zero-leak kill switch.** There may be a fallback
window before detection, especially on a process crash, helper loss, reboot,
phone call, or network change. Do not use this for leak-sensitive traffic.
Other tethered transports are outside this version's protection.
If shutdown cannot be confirmed, turn the system hotspot off manually.
Do not run another test-network tethering app alongside TunnelSpot: the
preferred-test-network setting is global and is reset to false on normal stop.

The approach was informed by current Android platform sources and discussion
of [test-network tethering](https://github.com/Mygod/VPNHotspot/issues/789).
That establishes feasibility, **not device verification of TunnelSpot**.

## APK and GitHub Actions

Download `TunnelSpot.apk` directly from
[Releases](https://github.com/justjoseorg/TunnelSpot/releases).
Version tags (`v*`) automatically publish an **experimental prerelease** with
the APK and SHA-256 checksum, only after the build, tests, and lint succeed.

For branch builds, open [Actions → Android APK](https://github.com/justjoseorg/TunnelSpot/actions/workflows/android.yml),
select a successful run, and download **TunnelSpot-apk**. Extract `TunnelSpot.apk`
and install it. A SHA-256 checksum is included.

Every push to `main`, `v*` tag, pull request, and manual workflow run executes
unit tests, Android lint, and a universal debug APK build (ARM64, ARMv7, x86,
x86-64). Reports are uploaded separately. The APK is debug-signed, **not a
production release**. Fresh CI runners may use different debug signing keys;
upgrades can require exporting profiles and uninstalling the previous build.
Use Android's official package installer; allow installation from the browser
or file manager when prompted.

## Build locally

```sh
git clone --recurse-submodules https://github.com/justjoseorg/TunnelSpot.git
cd TunnelSpot
# JDK 21, Android SDK 36, NDK 28.2.13676358, CMake 3.22.1
export ANDROID_HOME=/path/to/android-sdk
sdkmanager "platforms;android-36" "build-tools;36.0.0" \
  "ndk;28.2.13676358" "cmake;3.22.1"
./gradlew :tunnel:testDebugUnitTest :ui:testDebugUnitTest :ui:lintDebug :ui:assembleDebug
# ui/build/outputs/apk/debug/ui-debug.apk
```

The lint baseline records one pre-existing upstream Persian translation
formatting issue. New lint errors still fail the build.

## Device acceptance checklist (not yet executed)

If startup fails, return to **Hotspot setup**. It shows whether the helper is
still connected and the last startup failure, including whether preparation,
the WireGuard engine, or Wi-Fi tethering failed. A permission error does not
necessarily mean the helper crashed. Starting the helper is not an end-to-end
hotspot compatibility check.

On a compatible phone, pair the built-in ADB client and start its helper, import a
real provider profile, and start TunnelSpot. Verify on a hotspot client that
public IPv4 and DNS use the provider; exercise TCP and UDP. On the phone verify
its public IPv4 stays on the original connection and there is no system VPN
session. Confirm native IPv6 cannot escape on the client. Then test switching
profiles, stopping from the notification, losing upstream, an incoming phone
call, killing the app/helper, forgetting the ADB pairing, and rebooting. Verify
that disabling Wireless debugging after startup does not end the session.
Inspect Android
tethering state and packet captures for fallback; a successful APK build
does not validate these behaviors.

## Attribution

Forked from [WireGuard/wireguard-android](https://github.com/WireGuard/wireguard-android),
preserving its UI and upstream history. Independent project, not an official
WireGuard product. See [NOTICE](NOTICE), [COPYING](COPYING), and individual
source/submodule licenses.

The embedded [libadb-android 3.1.1](https://github.com/MuntashirAkon/libadb-android/tree/3.1.1)
is used under its Apache-2.0 licensing option, with Conscrypt 2.5.3 (Apache-2.0)
and Bouncy Castle 1.81 (MIT).
Its [spake2-android 2.2.1](https://github.com/MuntashirAkon/spake2-java/tree/2.2.1)
dependency is LGPL-3.0. Unmodified corresponding sources, license texts,
dependency versions, and instructions for rebuilding/replacing the embedded
library are included in `assets/licenses` and linked there. JitPack is scoped
only to these two dependency groups.
