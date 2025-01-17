package com.google.android.mobly.snippet.bundled.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Build;
import android.util.Pair;

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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
public class BluetoothRFCommSnippet implements Snippet {

    private static class BluetoothRFCommSnippetException extends Exception {

        private static final long serialVersionUID = 1;

        public BluetoothRFCommSnippetException(String msg) {
            super(msg);
        }

    }
    private final Context mContext;

    private static final BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    private final EventCache mEventCache = EventCache.getInstance();
    private HashMap<String, BluetoothSocket> mRfCommChannels = new HashMap<>();
    private HashMap<String, ExecutorService> mObserverThreads = new HashMap<>();

    public BluetoothRFCommSnippet() throws Throwable {
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        // Use a synchronized map to avoid racing problems
        Utils.adaptShellPermissionIfRequired(mContext);
    }

    @AsyncRpc(description = "Connect to rfcomm channel by uuid")
    public void boseRfcommConnect(String callbackId, String deviceAddress, String uuid) throws Throwable {
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(deviceAddress);
        if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
            throw new BluetoothRFCommSnippetException("Cannot connect to rfcomm on unbonded device.");
        }
        BluetoothSocket socket = device.createRfcommSocketToServiceRecord(UUID.fromString(uuid));
        socket.connect();
        if (!socket.isConnected()) {
            throw new BluetoothRFCommSnippetException("Could not connect to rfcomm and uuid: " + uuid);
        }
        mRfCommChannels.put(callbackId, socket);
        startObserver(callbackId, deviceAddress, uuid, socket.getInputStream());
    }

    @Rpc(description = "Disconnect from rfcomm channel")
    public void boseRfcommDisconnect(String callbackId) throws Throwable {
        BluetoothSocket socket = mRfCommChannels.get(callbackId);
        socket.close();
        if (socket.isConnected()) {
            throw new BluetoothRFCommSnippetException("Could not disconnect to rfcomm: " + socket.getRemoteDevice().getAddress());
        }
        mRfCommChannels.remove(callbackId);
        stopObserver(callbackId);
    }

    @Rpc(description = "Send data via rfcomm channel")
    public void boseRfcommSend(String callbackId, String data) throws Throwable {
        BluetoothSocket socket = mRfCommChannels.get(callbackId);
        OutputStream stream = socket.getOutputStream();
        stream.write(data.getBytes(StandardCharsets.US_ASCII));
        stream.flush();
    }

    @Rpc(description = "Clears event cache containing Rfcomm receive data")
    public void clearRcvData(String callbackId) throws Throwable {
        //mEventCache.c
    }

    private void startObserver(String callbackId, String deviceAddress, String uuid, InputStream inputStream) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        mObserverThreads.put(callbackId, executor);
        executor.submit(() -> {
            try {
                byte[] buffer = new byte[1024];
                int bytes;
                while ((bytes = inputStream.read(buffer)) != 0) {
                    String data = new String(buffer, 0, bytes, StandardCharsets.US_ASCII);
                    SnippetEvent event = new SnippetEvent(callbackId, "onRfcommDataReceived");
                    event.getData().putString("Address", deviceAddress);
                    event.getData().putString("UUID", uuid);
                    event.getData().putString("Data", data);
                    mEventCache.postEvent(event);
                }
            } catch (IOException e) {
                Log.e("Error reading from InputStream", e);
            }
        });
    }

    private void stopObserver(String callbackId) {
        ExecutorService executor = mObserverThreads.remove(callbackId);
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public void shutdown() {
        for (String callbackId : mObserverThreads.keySet()) {
            stopObserver(callbackId);
        }
        mRfCommChannels.clear();
    }

}
