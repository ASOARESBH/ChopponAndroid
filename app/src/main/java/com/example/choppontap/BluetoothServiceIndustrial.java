package com.example.choppontap;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * BluetoothServiceIndustrial - compativel com firmware ASOARESBH/ESP32
 * Protocolo: Nordic UART Service (NUS) - Just Works (sem PIN, sem bond)
 * Scan: por prefixo de nome "CHOPP_" (nao por MAC direto)
 * UUIDs: 6E400001/2/3-B5A3-F393-E0A9-E50E24DCCA9E
 */
@SuppressLint("MissingPermission")
public class BluetoothServiceIndustrial extends Service {

    // -------------------------------------------------------------------------
    // Constantes publicas — nomes novos
    // -------------------------------------------------------------------------
    public static final String TAG = "BLE_INDUSTRIAL";

    public static final String BLE_STATUS_ACTION = "com.example.choppontap.BLE_STATUS";
    public static final String BLE_DATA_ACTION   = "com.example.choppontap.BLE_DATA";

    public static final String STATUS_SCANNING     = "scanning";
    public static final String STATUS_CONNECTED    = "connected";
    public static final String STATUS_READY        = "ready";
    public static final String STATUS_DISCONNECTED = "disconnected";

    // -------------------------------------------------------------------------
    // Aliases de compatibilidade — mantidos para nao quebrar outros arquivos
    // -------------------------------------------------------------------------
    public static final String ACTION_CONNECTION_STATUS = BLE_STATUS_ACTION;
    public static final String ACTION_DATA_AVAILABLE    = BLE_DATA_ACTION;
    public static final String ACTION_DEVICE_FOUND      = "com.example.choppontap.BLE_DEVICE_FOUND";
    public static final String EXTRA_STATUS             = "status";
    public static final String EXTRA_DATA               = "data";
    public static final String EXTRA_DEVICE             = "device";

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------
    private static volatile boolean sRunning = false;

    // -------------------------------------------------------------------------
    // UUIDs (de BleConfigUtils)
    // -------------------------------------------------------------------------
    private static final UUID UUID_SERVICE = UUID.fromString(BleConfigUtils.SERVICE_UUID);
    private static final UUID UUID_RX      = UUID.fromString(BleConfigUtils.CHARACTERISTIC_UUID_RX);
    private static final UUID UUID_TX      = UUID.fromString(BleConfigUtils.CHARACTERISTIC_UUID_TX);
    private static final UUID UUID_CCCD    = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    // -------------------------------------------------------------------------
    // Estado interno
    // -------------------------------------------------------------------------
    private enum State { IDLE, SCANNING, CONNECTING, CONNECTED, READY }
    private State mState = State.IDLE;

    private BluetoothAdapter            mAdapter;
    private BluetoothLeScanner          mScanner;
    private BluetoothGatt               mGatt;
    private BluetoothGattCharacteristic mRxChar;
    private BluetoothGattCharacteristic mTxChar;

    private String mTargetMac;
    private String mTargetWifiMac;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // Reconexao com backoff exponencial
    private int    mReconnectCount = 0;
    private static final int    MAX_RECONNECT = 10;
    private static final long[] BACKOFF_MS    = {2000,4000,8000,15000,30000,30000,30000,30000,30000,30000};

    // PING keepalive
    private static final long PING_INTERVAL_MS  = 5000L;
    private final Runnable mPingRunnable         = this::sendPing;
    private final Runnable mScanTimeoutRunnable  = this::onScanTimeout;

    // Notification
    private static final String CHANNEL_ID = "ble_industrial_channel";
    private static final int    NOTIF_ID   = 1001;

    // -------------------------------------------------------------------------
    // Binder
    // -------------------------------------------------------------------------
    private final IBinder mBinder = new LocalBinder();

    public class LocalBinder extends Binder {
        public BluetoothServiceIndustrial getService() {
            return BluetoothServiceIndustrial.this;
        }
    }

    // =========================================================================
    // Ciclo de vida
    // =========================================================================

    @Override
    public void onCreate() {
        super.onCreate();
        if (sRunning) {
            Log.w(TAG, "[SERVICE] Servico BLE ja esta rodando! Abortando onCreate duplicado");
            stopSelf();
            return;
        }
        try {
            sRunning = true;
            Log.i(TAG, "[SERVICE] BluetoothServiceIndustrial v3.0 SINGLETON iniciado");
            Log.i(TAG, "[SERVICE] Protocolo: Nordic UART Service (NUS)");
            createNotificationChannel();
            startForeground(NOTIF_ID, buildNotification("BLE inicializando..."));
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            mAdapter = bm != null ? bm.getAdapter() : null;
            if (mAdapter == null || !mAdapter.isEnabled()) {
                Log.e(TAG, "[SERVICE] Bluetooth nao disponivel");
                sRunning = false;
                stopSelf();
                return;
            }
            Log.i(TAG, "[BOND] BroadcastReceiver de pareamento registrado.");
        } catch (Exception e) {
            Log.e(TAG, "[SERVICE] Excecao em onCreate: " + e.getMessage(), e);
            sRunning = false;
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "[SERVICE] SERVICE DESTROYED");
        sRunning = false;
        disconnect(true);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public static boolean isRunning() { return sRunning; }

    // =========================================================================
    // API publica — metodos novos
    // =========================================================================

    public void connectWithMac(String mac, String wifiMac) {
        mTargetMac     = mac;
        mTargetWifiMac = wifiMac != null ? wifiMac : mac;
        Log.i(TAG, "[CONNECT] connectWithMac(" + mac + ")");
        mReconnectCount = 0;
        startScanCycle();
    }

    public void connectWithMac(String mac) { connectWithMac(mac, mac); }

    /**
     * Envia comando para o ESP32 via caracteristica RX.
     * Alias: write(String) — compatibilidade com chamadas existentes.
     */
    public boolean sendCommand(String command) {
        if (mState != State.READY && mState != State.CONNECTED) {
            Log.w(TAG, "[CMD] Ignorado (estado=" + mState + "): " + command);
            return false;
        }
        if (mRxChar == null || mGatt == null) return false;
        byte[] bytes = (command + "\n").getBytes();
        mRxChar.setValue(bytes);
        mRxChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        boolean ok = mGatt.writeCharacteristic(mRxChar);
        Log.d(TAG, "[CMD] ok=" + ok + " cmd=" + command.trim());
        return ok;
    }

    /** Alias de compatibilidade: write(String) -> sendCommand(String) */
    public boolean write(String command) { return sendCommand(command); }

    /** Alias de compatibilidade: connected() -> isReady() */
    public boolean connected() { return mState == State.READY || mState == State.CONNECTED; }

    /** Inicia ou para o scan BLE. Alias de compatibilidade: scanLeDevice(boolean) */
    public void scanLeDevice(boolean enable) {
        if (enable) {
            startScanCycle();
        } else {
            stopScan();
            mState = State.IDLE;
        }
    }

    public void disconnect(boolean stopReconnect) {
        if (stopReconnect) {
            mReconnectCount = MAX_RECONNECT;
            mHandler.removeCallbacks(mPingRunnable);
            mHandler.removeCallbacks(mScanTimeoutRunnable);
        }
        stopScan();
        if (mGatt != null) { mGatt.disconnect(); mGatt.close(); mGatt = null; }
        mState = State.IDLE; mRxChar = null; mTxChar = null;
    }

    public String getCurrentStatus() { return mState.name().toLowerCase(); }

    // =========================================================================
    // Scan BLE — por prefixo "CHOPP_" (nao por MAC direto)
    // =========================================================================

    private void startScanCycle() {
        stopScan();
        if (mAdapter == null || !mAdapter.isEnabled()) return;
        mScanner = mAdapter.getBluetoothLeScanner();
        if (mScanner == null) { scheduleReconnect("scanner_null"); return; }

        boolean fallback = mReconnectCount >= 2;
        Log.i(TAG, "[SCAN] Iniciando ciclo de conexao" + (fallback ? " [MODO FALLBACK - prefixo CHOPP_]" : ""));
        Log.i(TAG, "[SCAN] MAC alvo: " + mTargetMac);

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        List<ScanFilter> filters = new ArrayList<>();

        mState = State.SCANNING;
        broadcastStatus(STATUS_SCANNING);
        mScanner.startScan(filters, settings, mScanCallback);
        mHandler.postDelayed(mScanTimeoutRunnable, BleConfigUtils.SCAN_TIMEOUT_MS);
    }

    private void stopScan() {
        mHandler.removeCallbacks(mScanTimeoutRunnable);
        if (mScanner != null) {
            try { mScanner.stopScan(mScanCallback); } catch (Exception ignored) {}
            mScanner = null;
        }
    }

    private void onScanTimeout() {
        if (mState != State.SCANNING) return;
        Log.w(TAG, "[SCAN] Timeout — nenhum dispositivo CHOPP_ encontrado");
        stopScan();
        scheduleReconnect("scan_timeout");
    }

    private final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            ScanRecord record = result.getScanRecord();
            String deviceName = (record != null) ? record.getDeviceName() : null;
            if (deviceName == null) deviceName = device.getName();

            if (!BleConfigUtils.isChoppDevice(deviceName)) return;
            Log.d(TAG, "[SCAN] Encontrado: " + deviceName + " MAC=" + device.getAddress());

            boolean nameMatch  = BleConfigUtils.matchesBleNameForMac(deviceName, mTargetWifiMac);
            boolean macMatch   = device.getAddress().equalsIgnoreCase(mTargetMac);
            boolean fallbackOk = mReconnectCount >= 2;

            if (nameMatch || macMatch || fallbackOk) {
                if (!nameMatch && !macMatch)
                    Log.w(TAG, "[FALLBACK] Aceitando " + deviceName + " por fallback");
                // Notificar device encontrado (compatibilidade)
                Intent di = new Intent(ACTION_DEVICE_FOUND);
                di.putExtra(EXTRA_DEVICE, device.getAddress());
                sendBroadcast(di);
                stopScan();
                connectGatt(device);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "[SCAN] Falha no scan, codigo=" + errorCode);
            scheduleReconnect("scan_failed:" + errorCode);
        }
    };

    // =========================================================================
    // Conexao GATT — Just Works (sem createBond, sem PIN)
    // =========================================================================

    private void connectGatt(BluetoothDevice device) {
        Log.i(TAG, "[GATT] Conectando a " + device.getAddress());
        mState = State.CONNECTING;
        mGatt = device.connectGatt(this, false, mGattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.i(TAG, "[GATT] Conectado");
                mState = State.CONNECTED;
                broadcastStatus(STATUS_CONNECTED);
                gatt.requestMtu(BleConfigUtils.MTU_REQUESTED);
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.w(TAG, "[GATT] Desconectado (status=" + status + ")");
                mState = State.IDLE; mRxChar = null; mTxChar = null;
                mHandler.removeCallbacks(mPingRunnable);
                if (mGatt != null) { mGatt.close(); mGatt = null; }
                broadcastStatus(STATUS_DISCONNECTED + ":gatt_" + status);
                scheduleReconnect("gatt_disconnect");
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.i(TAG, "[GATT] MTU=" + mtu);
            gatt.discoverServices();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) { gatt.disconnect(); return; }
            Log.i(TAG, "[GATT] Servicos descobertos");
            BluetoothGattService service = gatt.getService(UUID_SERVICE);
            if (service == null) {
                Log.e(TAG, "[GATT] Servico NUS nao encontrado UUID=" + UUID_SERVICE);
                gatt.disconnect(); return;
            }
            mRxChar = service.getCharacteristic(UUID_RX);
            mTxChar = service.getCharacteristic(UUID_TX);
            if (mRxChar == null || mTxChar == null) { gatt.disconnect(); return; }

            gatt.setCharacteristicNotification(mTxChar, true);
            BluetoothGattDescriptor desc = mTxChar.getDescriptor(UUID_CCCD);
            if (desc != null) {
                desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(desc);
            } else {
                onReady();
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) onReady();
            else { Log.e(TAG, "[GATT] Falha descriptor: " + status); gatt.disconnect(); }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            if (data == null) return;
            String msg = new String(data).trim();
            Log.d(TAG, "[RX] " + msg);
            BleCommand.Response r = BleCommand.parse(msg);
            if (r.isPong()) Log.d(TAG, "[PING] PONG - sessao valida");
            broadcastData(msg);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "[GATT] write status=" + status);
        }
    };

    // =========================================================================
    // Estado READY + PING keepalive
    // =========================================================================

    private void onReady() {
        mState = State.READY;
        mReconnectCount = 0;
        Log.i(TAG, "[READY] Conexao pronta - PING keepalive iniciado");
        broadcastStatus(STATUS_READY);
        updateNotification("BLE conectado");
        mHandler.removeCallbacks(mPingRunnable);
        mHandler.postDelayed(mPingRunnable, PING_INTERVAL_MS);
    }

    private void sendPing() {
        if (mState != State.READY) return;
        sendCommand(BleCommand.buildPing());
        mHandler.postDelayed(mPingRunnable, PING_INTERVAL_MS);
    }

    // =========================================================================
    // Reconexao com backoff exponencial
    // =========================================================================

    private void scheduleReconnect(String reason) {
        if (mReconnectCount >= MAX_RECONNECT) {
            Log.e(TAG, "[RECONNECT] Maximo atingido - desistindo");
            broadcastStatus(STATUS_DISCONNECTED + ":max_retries");
            return;
        }
        long delay = BACKOFF_MS[Math.min(mReconnectCount, BACKOFF_MS.length - 1)];
        mReconnectCount++;
        Log.w(TAG, "[RECONNECT] Falha #" + mReconnectCount + " - proxima tentativa em " + delay + "ms");
        if (mReconnectCount == 2)
            Log.w(TAG, "[RECONNECT] " + mReconnectCount + " falhas acumuladas - proximo ciclo usara scan de fallback (CHOPP_)");
        if (mReconnectCount == 3) { Log.w(TAG, "[RECONNECT] 3 falhas - tentando refresh do cache GATT"); refreshGattCache(); }
        broadcastStatus(STATUS_DISCONNECTED + ":" + reason);
        mHandler.postDelayed(() -> { if (mState == State.IDLE || mState == State.SCANNING) startScanCycle(); }, delay);
    }

    private void refreshGattCache() {
        if (mGatt == null) return;
        try {
            java.lang.reflect.Method m = mGatt.getClass().getMethod("refresh");
            Log.d(TAG, "[GATT] refresh()=" + m.invoke(mGatt));
        } catch (Exception e) { Log.w(TAG, "[GATT] refresh() indisponivel"); }
    }

    // =========================================================================
    // Broadcasts
    // =========================================================================

    private void broadcastStatus(String status) {
        Log.d(TAG, "[STATUS] " + status);
        Intent i = new Intent(BLE_STATUS_ACTION);
        i.putExtra(EXTRA_STATUS, status);
        sendBroadcast(i);
    }

    private void broadcastData(String data) {
        Intent i = new Intent(BLE_DATA_ACTION);
        i.putExtra(EXTRA_DATA, data);
        sendBroadcast(i);
    }

    // =========================================================================
    // Notification (Foreground Service)
    // =========================================================================

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Servico BLE", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Conexao Bluetooth com a chopeira");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("ChoppOn BLE").setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setPriority(NotificationCompat.PRIORITY_LOW).build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }
}
