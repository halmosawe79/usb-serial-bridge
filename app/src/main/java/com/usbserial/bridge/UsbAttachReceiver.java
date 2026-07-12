package com.usbserial.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;

/**
 * Broadcast receiver that listens for USB device attachment and
 * automatically starts the bridge service when a compatible device
 * is plugged in.
 */
public class UsbAttachReceiver extends BroadcastReceiver {

    private static final String TAG = "UsbAttachReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null) {
                Log.i(TAG, "USB device attached: VID=0x" + Integer.toHexString(device.getVendorId())
                        + " PID=0x" + Integer.toHexString(device.getProductId()));

                // Automatically start the bridge service when a device is plugged in
                Intent serviceIntent = new Intent(context, UsbSerialBridgeService.class);
                serviceIntent.setAction(UsbSerialBridgeService.ACTION_START);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            }
        }
    }
}
