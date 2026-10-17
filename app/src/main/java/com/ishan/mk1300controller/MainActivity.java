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
import androidx.appcompat.app.AppCompatActivity;
import java.util.HashMap;

public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION = "com.ishan.mk1300controller.USB_PERMISSION";
    private WebView webView;
    private UsbManager usbManager;
    private UsbDevice targetDevice;
    private UsbInterface targetInterface;
    private UsbEndpoint outEndpoint;
    private UsbDeviceConnection connection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

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

    private void findAndConnectKeyboard() {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        targetDevice = null;
        targetInterface = null;
        outEndpoint = null;

        for (UsbDevice device : deviceList.values()) {
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface iface = device.getInterface(i);
                for (int e = 0; e < iface.getEndpointCount(); e++) {
                    UsbEndpoint ep = iface.getEndpoint(e);
                    if (ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                        targetDevice = device;
                        targetInterface = iface;
                        outEndpoint = ep;
                        break;
                    }
                }
                if (targetDevice != null) break;
            }
            if (targetDevice != null) break;
        }

        if (targetDevice == null) {
            runOnUiThread(() -> webView.evaluateJavascript("addLog('NO USB OTG KEYBOARD DETECTED. Turn on OTG in phone Settings!', 'glitch');", null));
            return;
        }

        if (usbManager.hasPermission(targetDevice)) {
            openDeviceConnection();
        } else {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), flags);
            usbManager.requestPermission(targetDevice, pi);
        }
    }

    private void openDeviceConnection() {
        try {
            connection = usbManager.openDevice(targetDevice);
            if (connection != null && connection.claimInterface(targetInterface, true)) {
                String vid = "0x" + Integer.toHexString(targetDevice.getVendorId()).toUpperCase();
                String pid = "0x" + Integer.toHexString(targetDevice.getProductId()).toUpperCase();
                String name = targetDevice.getProductName() != null ? targetDevice.getProductName() : "Ant Esports MK1300";

                runOnUiThread(() -> {
                    webView.evaluateJavascript("addLog('CONNECTED: " + name + " (" + vid + ":" + pid + ")', 'success');", null);
                    webView.evaluateJavascript("document.getElementById('status').textContent = 'HARDWARE ONLINE';", null);
                    webView.evaluateJavascript("document.getElementById('btnConnect').style.display = 'none';", null);
                    webView.evaluateJavascript("document.getElementById('controlGrid').classList.add('active');", null);
                });
            } else {
                runOnUiThread(() -> webView.evaluateJavascript("addLog('FAILED TO CLAIM INTERFACE (Android Kernel Lock)', 'glitch');", null));
            }
        } catch (Exception e) {
            runOnUiThread(() -> webView.evaluateJavascript("addLog('CONNECTION ERROR: " + e.getMessage() + "', 'glitch');", null));
        }
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                        openDeviceConnection();
                    } else {
                        runOnUiThread(() -> webView.evaluateJavascript("addLog('USB PERMISSION DENIED BY USER', 'glitch');", null));
                    }
                }
            }
        }
    };

    public class WebAppInterface {
        @JavascriptInterface
        public void initialize() {
            findAndConnectKeyboard();
        }

        @JavascriptInterface
        public void sendPacket(int mode, int brightness, int speed, int h, int s, int v) {
            if (connection == null || outEndpoint == null) return;

            byte[] lightConfig = new byte[]{1, 0, (byte) mode, (byte) brightness, (byte) speed, 1, 1, 0, (byte) h, (byte) s, (byte) v};
            byte[] payload = new byte[15];
            payload[0] = 11;
            payload[1] = (byte) lightConfig.length;
            payload[2] = 0;
            payload[3] = 0;
            System.arraycopy(lightConfig, 0, payload, 4, lightConfig.length);

            byte[] rawBuffer = new byte[64];
            rawBuffer[0] = 6;
            System.arraycopy(payload, 0, rawBuffer, 1, payload.length);

            int result = connection.bulkTransfer(outEndpoint, rawBuffer, rawBuffer.length, 1000);
            if (result >= 0) {
                runOnUiThread(() -> webView.evaluateJavascript("addLog('SYNC OK: Mode " + mode + "', 'success');", null));
            } else {
                runOnUiThread(() -> webView.evaluateJavascript("addLog('WRITE FAILED', 'glitch');", null));
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(usbReceiver);
            if (connection != null) connection.close();
        } catch (Exception ignored) {}
    }
}
