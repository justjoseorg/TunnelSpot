# PhoneHome

An **experimental, no-root Android WireGuard hotspot** using the official
WireGuard Android interface. Save multiple profiles, import `.conf`/ZIP files or
scan a QR code, then use a profile's switch to start/stop WireGuard and Wi-Fi
sharing together.

**Status: implementation/build validation only, not verified on a physical
Android device. Do not treat this as a proven leak-proof VPN gateway.**

## What goes through WireGuard?

Only devices connected to the phone's **Wi-Fi hotspot**. Phone apps keep their
normal connection. Clients do not install an app or configure a proxy.
PhoneHome does not create an Android `VpnService`, change app include/exclude
lists, or change the phone's default network.

This version carries **IPv4 TCP, UDP, and DNS**. It does not advertise an IPv6
upstream to clients. IPv6-only destinations are not supported. USB, Bluetooth,
and Ethernet sharing are not managed by this version; leave them disabled.
Providers must supply standard WireGuard configurations; OpenVPN and
proprietary provider apps are not implemented.

## Requirements and setup

1. Android **13 or newer**, with Wi-Fi tethering and compatible test-network
   APIs. OEM support is not guaranteed.
2. Install [Shizuku](https://shizuku.rikka.app/download/) and start it using
   [Wireless debugging or ADB](https://shizuku.rikka.app/guide/setup/).
   **No root required.** Wireless debugging is needed for setup, not on clients.
   Shizuku must be restarted after each phone reboot.
3. Open PhoneHome → **Hotspot setup** → **Authorize PhoneHome**.
4. Configure the hotspot SSID/password in Android's hotspot settings. Leave the
   hotspot **off** before starting PhoneHome. PhoneHome uses the system's saved
   credentials; it does not collect or store the Wi-Fi password.
5. Import a WireGuard profile with exactly one IPv4 interface address, an IPv4
   DNS server, `AllowedIPs = 0.0.0.0/0`, and MTU 1280–1500 (default 1280).
   A dual-stack profile is accepted, but this gateway uses its IPv4 address.
6. Turn the profile on. PhoneHome prepares its tunnel, starts Wi-Fi sharing, and
   checks Android selected the tunnel upstream. Connect clients to the saved SSID.
   The notification or the same switch stops both.

All imported private keys remain in the app's private storage, as in upstream;
Android backups are disabled. They are never sent to the Shizuku helper.
Profiles are **not additionally encrypted at rest**.

## How no-root sharing is possible

A Shizuku shell-UID user service uses Android's hidden `TestNetworkManager` to
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
select cellular/Wi-Fi as upstream. PhoneHome checks upstream callbacks and
polls every second, and attempts to stop Wi-Fi if selection changes. It stops
Wi-Fi before dismantling the test network on normal teardown and watches the
app's Binder lifetime in the helper.

**This cannot guarantee a zero-leak kill switch.** There may be a fallback
window before detection, especially on a process crash, Shizuku loss, reboot,
phone call, or network change. Do not use this for leak-sensitive traffic.
Other tethered transports are outside this version's protection.
If shutdown cannot be confirmed, turn the system hotspot off manually.
Do not run another test-network tethering app alongside PhoneHome: the
preferred-test-network setting is global and is reset to false on normal stop.

The approach was informed by current Android platform sources and discussion
of [Shizuku test-network tethering](https://github.com/Mygod/VPNHotspot/issues/789).
That establishes feasibility, **not device verification of PhoneHome**.

## APK and GitHub Actions

Open [Actions → Android APK](https://github.com/justjoseorg/PhoneHome/actions/workflows/android.yml),
select a successful run, and download **PhoneHome-apk**. Extract `PhoneHome.apk`
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
git clone --recurse-submodules https://github.com/justjoseorg/PhoneHome.git
cd PhoneHome
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

On a compatible phone, authorize **ADB-started Shizuku (UID 2000)**, import a
real provider profile, and start PhoneHome. Verify on a hotspot client that
public IPv4 and DNS use the provider; exercise TCP and UDP. On the phone verify
its public IPv4 stays on the original connection and there is no system VPN
session. Confirm native IPv6 cannot escape on the client. Then test switching
profiles, stopping from the notification, losing upstream, an incoming phone
call, killing the app/helper, revoking Shizuku, and rebooting. Inspect Android
tethering state and packet captures for fallback; a successful APK build
does not validate these behaviors.

## Attribution

Forked from [WireGuard/wireguard-android](https://github.com/WireGuard/wireguard-android),
preserving its UI and upstream history. Independent project, not an official
WireGuard product. See [NOTICE](NOTICE), [COPYING](COPYING), and individual
source/submodule licenses.
