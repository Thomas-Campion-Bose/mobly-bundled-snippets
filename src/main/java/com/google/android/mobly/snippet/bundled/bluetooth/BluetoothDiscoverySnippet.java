package com.google.android.mobly.snippet.bundled.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Build;
import android.annotation.TargetApi;

import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.mobly.snippet.Snippet;
import com.google.android.mobly.snippet.bundled.utils.JsonSerializer;
import com.google.android.mobly.snippet.bundled.utils.Utils;
import com.google.android.mobly.snippet.event.EventCache;
import com.google.android.mobly.snippet.event.SnippetEvent;
import com.google.android.mobly.snippet.rpc.AsyncRpc;
import com.google.android.mobly.snippet.rpc.RpcMinSdk;
import com.google.android.mobly.snippet.rpc.Rpc;
import com.google.android.mobly.snippet.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.json.JSONException;

@TargetApi(Build.VERSION_CODES.LOLLIPOP_MR1)
public class BluetoothDiscoverySnippet implements Snippet {
    private static class BluetoothDiscoverySnippetException extends Exception {

        private static final long serialVersionUID = 1;

        public BluetoothDiscoverySnippetException(String msg) {
            super(msg);
        }

    }

    // Timeout to measure consistent BT state.
    private static final int BT_MATCHING_STATE_INTERVAL_SEC = 5;
    // Default timeout in seconds.
    private static final int TIMEOUT_TOGGLE_STATE_SEC = 30;
    // Default timeout in milliseconds for UI update.
    private final Context mContext;
    private static final BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    private final JsonSerializer mJsonSerializer = new JsonSerializer();
    private static final ConcurrentHashMap<String, BluetoothDevice> mDiscoveryResults =
            new ConcurrentHashMap<String, BluetoothDevice>();
    private final Map<String, BroadcastReceiver> mReceivers;
    private final EventCache mEventCache = EventCache.getInstance();

    public BluetoothDiscoverySnippet() throws Throwable {
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        // Use a synchronized map to avoid racing problems
        mReceivers = Collections.synchronizedMap(new HashMap<String, BroadcastReceiver>());
        Utils.adaptShellPermissionIfRequired(mContext);
    }

    @AsyncRpc(
            description =
                    "Start discovery, wait for discovery to complete, and return results, which is a list of "
                            + "serialized BluetoothDevice objects.")
    public void boseDiscover(String callbackId)
            throws InterruptedException, BluetoothDiscoverySnippetException {
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        if (mBluetoothAdapter.isDiscovering()) {
            mBluetoothAdapter.cancelDiscovery();
        }
        mDiscoveryResults.clear();
        BroadcastReceiver receiver = new BluetoothScanReceiver(callbackId);
        mContext.registerReceiver(receiver, filter);
        mReceivers.put(callbackId, receiver);
        if (!mBluetoothAdapter.startDiscovery()) {
            throw new BluetoothDiscoverySnippetException(
                    "Failed to initiate Bluetooth Discovery.");
        }
    }

    @Rpc(description = "Cancel ongoing bluetooth discovery.")
    public void boseCancelDiscover(String callbackId) throws BluetoothDiscoverySnippetException {
        if (!mBluetoothAdapter.isDiscovering()) {
            Log.d("No ongoing bluetooth discovery.");
            return;
        }
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        try {
            if (!mBluetoothAdapter.cancelDiscovery()) {
                throw new BluetoothDiscoverySnippetException(
                        "Failed to initiate to cancel bluetooth discovery.");
            }
        } finally {
            mContext.unregisterReceiver(mReceivers.get(callbackId));
        }
    }
    
    @RpcMinSdk(Build.VERSION_CODES.LOLLIPOP_MR1)
    @AsyncRpc(description = "Pair with a bluetooth device.")
    public void bosePairDevice(String callbackId, String deviceAddress) throws Throwable {
        BluetoothDevice device = mDiscoveryResults.get(deviceAddress);
        if (device == null) {
            throw new NoSuchElementException(
                    "No device with address "
                            + deviceAddress
                            + " has been discovered. Cannot proceed.");
        }
        if (device.getBondState() == device.BOND_BONDED) {
            SnippetEvent event = new SnippetEvent(callbackId, "onPairingEvent");
            Bundle pairingResult = new Bundle();
            event.getData().putBundle("device", mJsonSerializer.serializeBluetoothDevice(device));
            mEventCache.postEvent(event);
            return;
        }

        BluetoothPairingReceiver receiver = new BluetoothPairingReceiver(callbackId, mContext, BluetoothDevice.BOND_BONDED);
        mContext.registerReceiver(receiver, receiver.filter);
        if (!(boolean) Utils.invokeByReflection(device, "createBond")) {
            mContext.unregisterReceiver(receiver);
            throw new BluetoothDiscoverySnippetException(
                    "Failed to initiate the pairing process to device: " + deviceAddress);
        }
        mReceivers.put(callbackId, receiver);
    }

    @AsyncRpc(description = "Un-pair a bluetooth device.")
    public void boseUnpairDevice(String callbackId, String deviceAddress) throws Throwable {
        for (BluetoothDevice device : mBluetoothAdapter.getBondedDevices()) {
            if (device.getAddress().equalsIgnoreCase(deviceAddress)) {
                BluetoothPairingReceiver receiver = new BluetoothPairingReceiver(callbackId, mContext, BluetoothDevice.BOND_BONDED);
                mContext.registerReceiver(receiver, receiver.filter);
                if (!(boolean) Utils.invokeByReflection(device, "removeBond")) {
                    mContext.unregisterReceiver(receiver);
                    throw new BluetoothDiscoverySnippetException(
                            "Failed to initiate the un-pairing process for device: "
                                    + deviceAddress);
                }
                mReceivers.put(callbackId, receiver);

                return;
            }
        }
        throw new NoSuchElementException("No device with address " + deviceAddress + " is paired.");
    }
    @Override
    public void shutdown() {
        for (Map.Entry<String, BroadcastReceiver> entry : mReceivers.entrySet()) {
            mContext.unregisterReceiver(entry.getValue());
        }
        mReceivers.clear();
    }

    private class BluetoothScanReceiver extends BroadcastReceiver {

        private final String mCallbackId;
        public BluetoothScanReceiver(String callbackId) {
            mCallbackId = callbackId;
        }
        /**
         * The receiver gets an ACTION_FOUND intent whenever a new device is found.
         * ACTION_DISCOVERY_FINISHED intent is received when the discovery process ends.
         */
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                context.unregisterReceiver(this);
            } else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device =
                        (BluetoothDevice) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                mDiscoveryResults.put(device.getAddress(), device);
                SnippetEvent event = new SnippetEvent(mCallbackId, "onDiscoveryReceive");
                event.getData().putBundle("device", mJsonSerializer.serializeBluetoothDevice(device));
                mEventCache.postEvent(event);
            }
        }
    }

    private class BluetoothPairingReceiver extends BroadcastReceiver {
        private final Context mContext;
        private final String mCallbackId;
        public IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
        public final int mExpectedBondState;

        public BluetoothPairingReceiver(String callbackId, Context context, int expectedDeviceState) throws Throwable {
            mCallbackId = callbackId;
            mContext = context;
            mExpectedBondState = expectedDeviceState;
            filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            Utils.adaptShellPermissionIfRequired(mContext);
        }

        /**
         * The receiver gets an ACTION_FOUND intent whenever a new device is found.
         * ACTION_DISCOVERY_FINISHED intent is received when the discovery process ends.
         */
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            SnippetEvent event = new SnippetEvent(mCallbackId, "onPairingEvent");
            BluetoothDevice device =
                    (BluetoothDevice) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (action.equals(BluetoothDevice.ACTION_PAIRING_REQUEST)) {
                device.setPairingConfirmation(true);
                Log.d("Confirming pairing with device: " + device.getAddress());
                Bundle pairingResult = new Bundle();
                pairingResult.putCharSequence("pairing", "confirmed");
                event.getData().putBundle("result", pairingResult);
                event.getData().putBundle("device", mJsonSerializer.serializeBluetoothDevice(device));
                mEventCache.postEvent(event);
            } else if (action.equals(BluetoothDevice.ACTION_BOND_STATE_CHANGED)) {
                Bundle pairingResult = new Bundle();
                event.getData().putBundle("device", mJsonSerializer.serializeBluetoothDevice(device));
                mEventCache.postEvent(event);
                if (device.getBondState() == mExpectedBondState) {
                    mContext.unregisterReceiver(this);
                }
            }
        }

    }

    /**
     * Waits until the bluetooth adapter state has stabilized. We consider BT state stabilized if it
     * hasn't changed within 5 sec.
     */
    private static void waitForStableBtState() throws BluetoothDiscoverySnippetException {
        long timeoutMs = System.currentTimeMillis() + TIMEOUT_TOGGLE_STATE_SEC * 1000;
        long continuousStateIntervalMs =
                System.currentTimeMillis() + BT_MATCHING_STATE_INTERVAL_SEC * 1000;
        int prevState = mBluetoothAdapter.getState();
        while (System.currentTimeMillis() < timeoutMs) {
            // Delay.
            Utils.waitUntil(() -> false, /* timeout= */ 1);

            int currentState = mBluetoothAdapter.getState();
            if (currentState != prevState) {
                continuousStateIntervalMs =
                        System.currentTimeMillis() + BT_MATCHING_STATE_INTERVAL_SEC * 1000;
            }
            if (continuousStateIntervalMs <= System.currentTimeMillis()) {
                return;
            }
            prevState = currentState;
        }
        throw new BluetoothDiscoverySnippetException(
                String.format(
                        "Failed to reach a stable Bluetooth state within %d s",
                        TIMEOUT_TOGGLE_STATE_SEC));
    }
}
