# DNSTT Android Client

Android app for dnstt DNS tunnel client.

## Prerequisites

- Go 1.21+
- Android SDK (API 24+)
- gomobile: `go install golang.org/x/mobile/cmd/gomobile@latest`
- NDK (installed via Android Studio SDK Manager)

## Building

### Option 1: Build Script

```bash
./build-mobile.sh
```

This builds both the Go library and the Android app, producing `dnstt-client.apk`.

### Option 2: Manual Build

1. Build Go mobile library:

```bash
cd ..
gomobile bind -target=android -androidapi=24 -o android-app/app/libs/mobile.aar ./dnstt-client/mobile
```

2. Build Android app:

```bash
./gradlew assembleDebug
```

APK will be at `app/build/outputs/apk/debug/app-debug.apk`

## Installing

```bash
adb install dnstt-client.apk
```

## Usage

1. Enter your server's public key (from `server.pub`)
2. Enter the tunnel domain (e.g., `t.example.com`)
3. Select transport type:
   - **DoH**: DNS over HTTPS (recommended)
   - **DoT**: DNS over TLS
   - **UDP**: Plain UDP DNS
4. Enter resolver address:
   - DoH: `https://dns.google/dns-query`
   - DoT: `dns.google:853`
   - UDP: `1.1.1.1:53`
5. Set number of parallel tunnels (8-16 recommended)
6. Tap Connect

The app creates a SOCKS5 proxy on `127.0.0.1:1080`. Configure other apps to use this proxy.

## Configuration

Settings are persisted automatically. The app remembers:
- Transport type and address
- Domain
- Public key
- Number of tunnels

## Rebuilding Native Libraries (Optional)

The pre-built `libhev-socks5-tunnel.so` files are included in `app/src/main/jniLibs/`. If you need to rebuild them (e.g., to update or modify the tun2socks library):

1. Initialize the submodule:

```bash
git submodule update --init --recursive
```

2. Build with NDK:

```bash
cd app/src/main/jni
ndk-build
```

3. Copy the built `.so` files to `jniLibs/`:

```bash
cp -r ../libs/* ../jniLibs/
```
