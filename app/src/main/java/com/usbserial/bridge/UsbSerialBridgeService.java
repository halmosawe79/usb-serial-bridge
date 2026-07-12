package com.usbserial.bridge;

import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Foreground service that bridges USB serial devices to a local Unix socket.
 * <p>
 * Uses the usb-serial-for-android library (mik3y) to detect and communicate
 * with USB serial adapters (CDC/ACM, FTDI, CP210x, CH340/CH341, PL2303, etc.)
 * and exposes them over a LocalServerSocket so that processes running under
 * Termux/Proot can access the serial port via the abstract local socket.
 * <p>
 * Connection from Termux:
 *   socat UNIX-CONNECT:@usb_serial_bridge STDIO
 */
public class UsbSerialBridgeService extends Service {

    private static final String TAG = "UsbSerialBridge";
    private static final String ACTION_USB_PERMISSION = "com.usbserial.bridge.USB_PERMISSION";

    public static final String ACTION_START = "com.usbserial.bridge.START";
    public static final String ACTION_STOP = "com.usbserial.bridge.STOP";
    public static final String ACTION_STATUS = "com.usbserial.bridge.STATUS";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_BAUD = "baud_rate";
    public static final String LOCAL_SOCKET_NAME = "usb_serial_bridge";

    // Default serial config
    private static final int DEFAULT_BAUD_RATE = 115200;
    private static final int DEFAULT_DATA_BITS = 8;
    private static final int DEFAULT_STOP_BITS = UsbSerialPort.STOPBITS_1;
    private static final int DEFAULT_PARITY = UsbSerialPort.PARITY_NONE;
    private static final int READ_TIMEOUT_MS = 1000;

    private UsbManager usbManager;

    // Thread-safe: use AtomicReference so client handlers can atomically
    // read the current port and stopBridge() can swap to null before closing.
    private final AtomicReference<UsbSerialPort> serialPortRef = new AtomicReference<>(null);
    private UsbSerialDriver serialDriver;
    private UsbDeviceConnection usbConnection;
    private UsbDevice usbDevice;
    private int baudRate = DEFAULT_BAUD_RATE;

    private LocalServerSocket localSocket;
    private final ExecutorService ioPool = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(false);

    // ── Broadcast Receivers ────────────────────────────────────────────

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            Log.i(TAG, "USB permission " + (granted ? "granted" : "denied")
                    + " for " + (device != null ? device.getDeviceName() : "null"));

            if (granted && device != null) {
                openSerialDevice(device);
            } else {
                broadcastStatus("permission_denied", "USB permission denied");
                stopSelf();
            }
        }
    };

    /** Handles hotplug while the service is already running. */
    private final BroadcastReceiver usbHotplugReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device == null) return;
                Log.i(TAG, "USB device attached: " + formatVidPid(device));

                // Only handle if no device is currently open
                if (serialPortRef.get() != null) {
                    Log.d(TAG, "Already connected, ignoring attach event");
                    return;
                }

                UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
                if (driver != null) {
                    serialDriver = driver;
                    usbDevice = device;
                    requestPermission(device);
                } else {
                    Log.w(TAG, "No USB serial driver for attached device");
                    broadcastStatus("unsupported_device", "No driver for " + formatVidPid(device));
                }

            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device == null) return;

                // Only act if this is the device we're currently bridged to
                if (usbDevice != null && usbDevice.getDeviceId() == device.getDeviceId()) {
                    Log.w(TAG, "USB device detached: " + formatVidPid(device));
                    broadcastStatus("disconnected", "Device unplugged");
                    closeSerialPort();
                    updateNotification("Disconnected - device removed");
                }
            }
        }
    };

    // ── Service Lifecycle ─────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        Log.i(TAG, "Bridge service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getAction();
        Log.i(TAG, "onStartCommand: " + action);

        if (intent.hasExtra(EXTRA_BAUD)) {
            baudRate = intent.getIntExtra(EXTRA_BAUD, DEFAULT_BAUD_RATE);
        }

        if (ACTION_STOP.equals(action)) {
            stopBridge();
            return START_NOT_STICKY;
        }

        startForegroundService();
        startBridge();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopBridge();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ── Bridge Start / Stop ───────────────────────────────────────────

    private void startForegroundService() {
        String channelId = "usb_serial_bridge";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    channelId, "USB Serial Bridge",
                    android.app.NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Notification for USB Serial Bridge foreground service");
            ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("USB Serial Bridge")
                .setContentText("Starting...")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true);
        startForeground(1, builder.build());
    }

    private void startBridge() {
        if (running.get()) {
            Log.w(TAG, "Bridge already running");
            return;
        }

        // Register receivers for permission and hotplug events
        registerReceiver(usbPermissionReceiver, new IntentFilter(ACTION_USB_PERMISSION));

        IntentFilter hotplugFilter = new IntentFilter();
        hotplugFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        hotplugFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbHotplugReceiver, hotplugFilter);

        // Probe for existing USB serial devices
        List<UsbSerialDriver> availableDrivers =
                UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        Log.i(TAG, "Found " + availableDrivers.size() + " USB serial driver(s)");

        if (availableDrivers.isEmpty()) {
            broadcastStatus("no_device", "No USB serial device found. Plug one in.");
            updateNotification("Waiting for USB device...");

            // Log any USB devices present that didn't match a driver
            for (UsbDevice device : usbManager.getDeviceList().values()) {
                Log.d(TAG, "Non-serial USB present: " + formatVidPid(device));
            }

            running.set(true);
            return;
        }

        // Use the first available driver
        serialDriver = availableDrivers.get(0);
        usbDevice = serialDriver.getDevice();
        Log.i(TAG, "Selected driver: " + serialDriver.getClass().getSimpleName()
                + " for " + formatVidPid(usbDevice));

        if (usbManager.hasPermission(usbDevice)) {
            openSerialDevice(usbDevice);
        } else {
            requestPermission(usbDevice);
        }
    }

    private void stopBridge() {
        Log.i(TAG, "Stopping bridge...");
        running.set(false);

        try { unregisterReceiver(usbPermissionReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(usbHotplugReceiver); } catch (Exception ignored) {}

        closeSerialPort();
        closeLocalSockets();
        ioPool.shutdownNow();

        updateNotification("Stopped");
        broadcastStatus("stopped", "Bridge stopped");
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    // ── USB Permission & Device Opening ───────────────────────────────

    private void requestPermission(UsbDevice device) {
        Log.i(TAG, "Requesting USB permission for " + device.getDeviceName());
        PendingIntent pi = PendingIntent.getBroadcast(
                this, 0, new Intent(ACTION_USB_PERMISSION),
                Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        usbManager.requestPermission(device, pi);
        broadcastStatus("requesting_permission", "Requesting USB access...");
    }

    private void openSerialDevice(UsbDevice device) {
        Log.i(TAG, "Opening USB serial device...");

        usbConnection = usbManager.openDevice(device);
        if (usbConnection == null) {
            Log.e(TAG, "Failed to open USB device");
            broadcastStatus("open_failed", "Failed to open USB device");
            return;
        }

        try {
            UsbSerialPort port = serialDriver.getPorts().get(0);
            port.open(usbConnection);
            port.setParameters(baudRate, DEFAULT_DATA_BITS, DEFAULT_STOP_BITS, DEFAULT_PARITY);

            serialPortRef.set(port);
            usbDevice = device;

            String deviceInfo = String.format("%s @ %d baud | %s",
                    serialDriver.getClass().getSimpleName().replace("SerialDriver", ""),
                    baudRate, formatVidPid(device));

            Log.i(TAG, "Serial port opened: " + deviceInfo);
            updateNotification(deviceInfo);
            broadcastStatus("connected", deviceInfo);

            running.set(true);
            startLocalSocketServer();

        } catch (Exception e) {
            Log.e(TAG, "Failed to open serial port", e);
            broadcastStatus("error", "Failed to open serial port: " + e.getMessage());
            closeSerialPort();
        }
    }

    private void closeSerialPort() {
        UsbSerialPort port = serialPortRef.getAndSet(null);
        try {
            if (port != null) port.close();
        } catch (Exception e) {
            Log.w(TAG, "Error closing serial port", e);
        }
        try {
            if (usbConnection != null) {
                usbConnection.close();
                usbConnection = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error closing USB connection", e);
        }
        usbDevice = null;
        serialDriver = null;
    }

    // ── Local Socket Server ───────────────────────────────────────────

    private void startLocalSocketServer() {
        try {
            localSocket = new LocalServerSocket(LOCAL_SOCKET_NAME);
            Log.i(TAG, "Local socket listening on: @" + LOCAL_SOCKET_NAME);
            broadcastStatus("listening", "Bridge ready on @" + LOCAL_SOCKET_NAME);
            ioPool.submit(this::acceptLoop);
        } catch (IOException e) {
            Log.e(TAG, "Failed to create local socket server", e);
            broadcastStatus("error", "Failed to create bridge socket: " + e.getMessage());
        }
    }

    private void closeLocalSockets() {
        try {
            if (localSocket != null) {
                localSocket.close();
                localSocket = null;
            }
        } catch (IOException e) {
            Log.w(TAG, "Error closing local socket", e);
        }
    }

    private void acceptLoop() {
        Log.i(TAG, "Accept loop started");
        while (running.get() && localSocket != null) {
            try {
                LocalSocket client = localSocket.accept();
                Log.d(TAG, "Client connected");
                ioPool.submit(() -> handleClient(client));
            } catch (IOException e) {
                if (running.get()) {
                    Log.e(TAG, "Accept error", e);
                }
            }
        }
    }

    // ── Client Handler ────────────────────────────────────────────────

    private void handleClient(LocalSocket client) {
        String clientLabel = "client@" + Integer.toHexString(System.identityHashCode(client));
        Log.d(TAG, "Handling " + clientLabel);

        try {
            client.setSoTimeout(0);
            InputStream clientIn = client.getInputStream();
            OutputStream clientOut = client.getOutputStream();

            // Welcome banner
            String banner = String.format(
                    "USB Serial Bridge v1.0\n" +
                    "Driver: %s\nDevice: %s\nConfig: %d %dN1\nREADY\n",
                    serialDriver != null ? serialDriver.getClass().getSimpleName().replace("SerialDriver", "") : "?",
                    usbDevice != null ? formatVidPid(usbDevice) : "?",
                    baudRate);
            clientOut.write(banner.getBytes());
            clientOut.flush();

            // Client → USB writer (daemon thread)
            Thread clientToUsb = new Thread(() -> {
                byte[] buf = new byte[4096];
                try {
                    int n;
                    while (running.get() && !client.isClosed()
                            && (n = clientIn.read(buf)) != -1) {
                        if (n > 0) {
                            UsbSerialPort port = serialPortRef.get();
                            if (port != null) {
                                port.write(buf, n, 5000);
                            }
                        }
                    }
                } catch (IOException e) {
                    if (running.get()) Log.d(TAG, clientLabel + " client->usb ended: " + e.getMessage());
                }
            }, "c2u-" + clientLabel);
            clientToUsb.setDaemon(true);
            clientToUsb.start();

            // USB → Client (main loop on IO pool thread)
            byte[] readBuf = new byte[4096];
            while (running.get() && !client.isClosed()) {
                UsbSerialPort port = serialPortRef.get();
                if (port == null) break; // device disconnected

                try {
                    int n = port.read(readBuf, READ_TIMEOUT_MS);
                    if (n > 0) {
                        clientOut.write(readBuf, 0, n);
                        clientOut.flush();
                    }
                } catch (IOException e) {
                    if (running.get()) Log.e(TAG, clientLabel + " USB read error", e);
                    break;
                }
            }

            client.close();
            if (clientToUsb.isAlive()) clientToUsb.interrupt();
            Log.d(TAG, clientLabel + " disconnected");

        } catch (Exception e) {
            Log.e(TAG, clientLabel + " handler error: " + e.getMessage());
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private static String formatVidPid(UsbDevice d) {
        return String.format("0x%04X:0x%04X", d.getVendorId(), d.getProductId());
    }

    private void updateNotification(String text) {
        String channelId = "usb_serial_bridge";
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("USB Serial Bridge")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true);
        startForeground(1, builder.build());
    }

    private void broadcastStatus(String status, String message) {
        Intent intent = new Intent(ACTION_STATUS);
        intent.putExtra(EXTRA_STATUS, status);
        intent.putExtra(EXTRA_MESSAGE, message);
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
                .sendBroadcast(intent);
        Log.i(TAG, "Status: " + status + " - " + message);
    }
}
