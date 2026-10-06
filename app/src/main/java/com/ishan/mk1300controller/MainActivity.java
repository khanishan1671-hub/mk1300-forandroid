package com.ishan.mk1300controller;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.HashMap;

public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION = "com.ishan.mk1300controller.USB_PERMISSION";
    private UsbManager usbManager;
    private UsbDevice targetDevice;
    private UsbDeviceConnection connection;
    private UsbEndpoint outEndpoint;
    private WebView webView;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            setupUsbEndpoint(device);
                        }
                    } else {
                        Toast.makeText(context, "USB Permission Denied", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);

        webView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");
        webView.loadUrl("file:///android_asset/index.html");

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
    }

    private void setupUsbEndpoint(UsbDevice device) {
        targetDevice = device;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface usbInterface = device.getInterface(i);
            connection = usbManager.openDevice(device);
            if (connection != null && connection.claimInterface(usbInterface, true)) {
                for (int j = 0; j < usbInterface.getEndpointCount(); j++) {
                    UsbEndpoint ep = usbInterface.getEndpoint(j);
                    if (ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                        outEndpoint = ep;
                        runOnUiThread(() -> webView.evaluateJavascript("if(window.onUsbConnected) window.onUsbConnected();", null));
                        Toast.makeText(this, "MK1300 Uplink Established!", Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
            }
        }
    }

    public class WebAppInterface {
        @JavascriptInterface
        public void requestDevice() {
            HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
            for (UsbDevice device : deviceList.values()) {
                // Universal match: vendor 0x36ae or 0x3151
                if (device.getVendorId() == 0x36ae || device.getVendorId() == 0x3151) {
                    int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
                    PendingIntent permissionIntent = PendingIntent.getBroadcast(MainActivity.this, 0, new Intent(ACTION_USB_PERMISSION), flags);
                    usbManager.requestPermission(device, permissionIntent);
                    return;
                }
            }
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Keyboard not detected via OTG", Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void sendRawReport(String hexString) {
            if (connection == null) return;

            // Parse hex string to bytes
            int len = hexString.length();
            byte[] data = new byte[len / 2];
            for (int i = 0; i < len; i += 2) {
                data[i / 2] = (byte) ((Character.digit(hexString.charAt(i), 16) << 4)
                                     + Character.digit(hexString.charAt(i+1), 16));
            }

            // 1. Try Interrupt endpoint transfer
            if (outEndpoint != null) {
                connection.bulkTransfer(outEndpoint, data, data.length, 100);
            } else {
                // 2. Fallback to Control Transfer (Standard HID set_report)
                connection.controlTransfer(0x21, 0x09, 0x0300, 0, data, data.length, 100);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(usbReceiver);
        if (connection != null) connection.close();
    }
}
