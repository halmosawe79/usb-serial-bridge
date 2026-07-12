# USB Serial Bridge

[![Build APK](https://github.com/tmrisdaone/usb-serial-bridge/actions/workflows/build.yml/badge.svg)](https://github.com/tmrisdaone/usb-serial-bridge/actions/workflows/build.yml)

An Android app that bridges **any USB serial device** directly into **Termux** over an AF_UNIX local socket. No root required.

Plug an Arduino, FTDI adapter, CH340 programmer, CP210x cable, or any other USB serial device into your phone via USB-OTG — the app exposes it as a local socket that Termux processes can connect to and read/write like a regular serial port.

> **No root / Shizuku required.** Uses the standard Android `UsbManager` host API and `LocalServerSocket`, available since Android 4.4 (API 19).

## Supported Hardware

Powered by [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) — supports every major USB serial adapter:

| Family | Examples |
|---|---|
| **CDC/ACM** | Arduino Uno/Mega/Zero, ESP32 (CDC mode), STM32 DFU |
| **FTDI** | FT232, FT4232, FT2232 |
| **CP210x** | CP2102, CP2104, CP2105, CP2108 |
| **CH340/CH341** | CH340G, CH341A, CH341F (QinHeng) |
| **PL2303** | PL2303HX, PL2303RA, PL2303SA |

## How it works

```
┌─────────────────────────────────────────┐
│  Android App                            │
│                                         │
│  MainActivity        UsbSerialBridgeSvc │
│  ───────────────    ─────────────────── │
│  Start/Stop UI      UsbSerialProber     │
│  Status log          find & open port   │
│                            │            │
│                            ▼            │
│                    LocalServerSocket     │
│                    (@usb_serial_bridge)   │
└───────────────────────┬─────────────────┘
                        │ AF_UNIX
                        ▼
          socat UNIX-CONNECT:@usb_serial_bridge STDIO
                        │
                        ▼
               Your Termux tools
```

The app runs as a **foreground service** so Android won't kill it when Termux is in the background. The abstract local socket namespace means only apps running under the same UID (Termux) can connect — no TCP port exposed, no extra permissions needed.

## Install

### Option 1: Download from GitHub Actions

1. Go to the [Actions tab](https://github.com/tmrisdaone/usb-serial-bridge/actions)
2. Click the latest green workflow run
3. Scroll down to **Artifacts** and download `usb-serial-bridge-debug`
4. Install the APK on your Android device:
   ```bash
   adb install usb-serial-bridge-debug.apk
   ```
   Or open the APK file directly on your phone via a file manager.

### Option 2: Build from source

```bash
git clone https://github.com/tmrisdaone/usb-serial-bridge.git
cd usb-serial-bridge
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

## Usage

### 1. Open the app

Launch **USB Serial Bridge** from your app drawer. You'll see:

- **Start Bridge** button — begins listening for USB serial devices
- **Stop Bridge** button — shuts down the bridge
- **Status** line — shows current connection state
- **Log area** — timestamped status messages

### 2. Connect your USB device

Plug your USB serial adapter into your phone using a USB-OTG cable. The app will:

1. Detect the device and try to match it with a known USB serial driver
2. Request USB permission (grant it when prompted)
3. Open the serial port (115200 8N1 by default)
4. Start the local socket server
5. Show connection info in the notification bar

### 3. Connect from Termux

```bash
# Install socat if you haven't already
pkg install socat

# Connect to the bridge
socat UNIX-CONNECT:@usb_serial_bridge STDIO
```

This gives you a bidirectional serial connection. Everything you type goes to the USB serial device, and everything from the device appears in your terminal.

#### Send a command
```bash
# Write a command to the serial device
printf 'AT\r\n' | socat UNIX-CONNECT:@usb_serial_bridge STDIO
```

#### Interactive session
```bash
# Raw mode for interactive use
stty raw -echo; socat UNIX-CONNECT:@usb_serial_bridge STDIO; stty sane
```

#### Flash firmware (example: ESP32)
```bash
# pip install esptool first
socat UNIX-CONNECT:@usb_serial_bridge STDIO &
esptool.py --port /dev/ttyUSB0 --baud 921600 write_flash ... 
```
> Note: many flashers expect a real `/dev/tty*` device. Use `socat` to create a PTY:
> ```bash
> socat PTY,link=/tmp/ttyUSB0 UNIX-CONNECT:@usb_serial_bridge &
> esptool.py --port /tmp/ttyUSB0 write_flash ...
> ```

### 4. Custom baud rate

The bridge defaults to **115200 baud, 8N1**. To use a different baud rate, send it via intent extra:

```bash
adb shell am startservice -n com.usbserial.bridge/.UsbSerialBridgeService \
  -a com.usbserial.bridge.START \
  --ei baud_rate 9600
```

## Architecture

### Core components

| Class | Role |
|---|---|
| `UsbSerialBridgeService` | Foreground service: probes USB, opens serial port, runs socket server |
| `MainActivity` | Launcher UI: start/stop buttons, status log |
| `UsbAttachReceiver` | Manifest receiver: auto-starts bridge when USB device plugged in |

### Bridge service lifecycle

```
onCreate() → onStartCommand(ACTION_START)
  → startForegroundService()    (keep alive notification)
  → startBridge()
    → UsbSerialProber.findAllDrivers()
    → requestPermission()  OR  openSerialDevice()
      → serialPort.open(connection)
      → serialPort.setParameters(baud, 8N1)
      → LocalServerSocket("usb_serial_bridge")
      → acceptLoop()     (wait for socket clients)
        → handleClient()  (bidirectional bridge)

onStartCommand(ACTION_STOP) → stopBridge()
  → closeSerialPort()
  → closeLocalSocket()
  → stopForeground()
```

## Build configuration

| Setting | Value |
|---|---|
| Android Gradle Plugin | 8.6.1 |
| compileSdk / targetSdk | 34 |
| minSdk | 21 |
| Java | 17 |
| usb-serial-for-android | 3.7.3 (via JitPack) |

## Debugging

### Enable verbose logs
```bash
adb logcat -s UsbSerialBridge:*
```

### Common issues

| Symptom | Likely cause |
|---|---|
| `No USB serial device found` | Device not supported or OTG cable not connected |
| `Permission denied` | Didn't grant USB permission in the dialog |
| Bridge socket not found | Service not started or crashed |
| `socat: no such socket` | Make sure to use `@usb_serial_bridge` (with `@` prefix) |

## License

MIT — see [LICENSE](LICENSE).

## Acknowledgments

- [mik3y/usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) — the USB serial driver library this app is built on
- [tmrisdaone/ch341pardemo-bridge](https://github.com/tmrisdaone/ch341pardemo-bridge) — the original CH341 bridge app that inspired this project
- Termux team for showing that `LocalServerSocket` is a viable IPC surface on Android
