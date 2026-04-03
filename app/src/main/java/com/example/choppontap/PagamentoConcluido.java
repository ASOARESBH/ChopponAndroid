package com.example.choppontap;

import android.content.BroadcastReceiver;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.snackbar.Snackbar;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import okhttp3.ResponseBody;
import com.example.choppontap.BleCommand;

/**
 * PagamentoConcluido — Tela de liberação do chopp após pagamento confirmado.
 */
public class PagamentoConcluido extends AppCompatActivity {

    private static final String TAG = "PAGAMENTO_CONCLUIDO";

    // ── Timeouts e delays ─────────────────────────────────────────────────────
    private static final long ML_SEND_DELAY_MS       = 800L;
    private static final long ML_SEND_DELAY_MAX_MS   = 1000L;
    private static final long HOME_NAVIGATE_DELAY_MS = 3_000L;
    private static final long WATCHDOG_TIMEOUT_MS    = 30_000L;

    // ── Handlers ──────────────────────────────────────────────────────────────
    private final Handler mMainHandler    = new Handler(Looper.getMainLooper());
    private final Handler mWatchdogHandler = new Handler(Looper.getMainLooper());

    // ── Estado da liberação ───────────────────────────────────────────────────
    private int     qtd_ml               = 0;
    private int     liberado             = 0;
    private int     totalPulsos          = 0;
    private boolean mValvulaAberta       = false;
    private boolean mLiberacaoFinalizada = false;
    private boolean mWatchdogActive      = false;
    private boolean mComandoEnviado = false;
    private String mActiveCommandId = null;
    private String mActiveSessionId = null;

    private boolean mKeepaliveActive = false;
    private Runnable mKeepaliveRunnable = null;

    // ── READY/SERVE pending state ─────────────────────────────────────────────
    private int    mPendingVolumeMl = 0;
    private String mPendingCmdId = null;
    private String mPendingReadyCmdId = null;
    private String mPendingSessionId = null;
    private Runnable mReadyTimeoutRunnable = null;

    // ── Dados do pedido ───────────────────────────────────────────────────────
    private String checkout_id;
    private String android_id;
    private String imagemUrl;

    // ── Views ─────────────────────────────────────────────────────────────────
    private TextView    txtQtd;
    private TextView    txtMls;
    private TextView    txtStatus;
    private Button      btnLiberar;
    private ImageView   imageView;
    private ProgressBar progressBar;

    private final ExecutorService imageExecutor = Executors.newSingleThreadExecutor();
    private Future<?> currentImageTask = null;

    private BluetoothServiceIndustrial mBluetoothService;
    private boolean          mIsServiceBound = false;
    private SessionManager   mSessionManager;

    private final Runnable mWatchdogRunnable = () -> {
        Log.e(TAG, "[APP] WATCHDOG disparado!");
        mWatchdogActive  = false;
        mValvulaAberta   = false;
        atualizarStatus("⏱ Timeout: fluxo não detectado.");
        runOnUiThread(() -> {
            if (liberado < qtd_ml) {
                int restante = qtd_ml - liberado;
                btnLiberar.setText("Tentar novamente (" + restante + "ml)");
                btnLiberar.setVisibility(View.VISIBLE);
                mLiberacaoFinalizada = false;
            }
            mostrarSnackbar("Tempo esgotado.");
        });
    };

    private final BroadcastReceiver mServiceUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null) return;

            switch (action) {
                case BluetoothServiceIndustrial.ACTION_WRITE_READY:
                    long readyTimestamp = System.currentTimeMillis();
                    long guardBandDelay = ML_SEND_DELAY_MS;
                    atualizarStatus("✓ Dispositivo autenticado. Liberando...");
                    mMainHandler.postDelayed(() -> {
                        if (!mComandoEnviado) iniciarVendaEEnfileirar();
                    }, guardBandDelay);
                    break;

                case BluetoothServiceIndustrial.ACTION_CONNECTION_STATUS:
                    String status = intent.getStringExtra(BluetoothServiceIndustrial.EXTRA_STATUS);
                    if ("disconnected".equals(status)) {
                        atualizarStatus("🔄 Reconectando...");
                        cancelarWatchdog();
                        cancelarKeepalive();
                        if (mBluetoothService != null) mBluetoothService.pararHeartbeat();
                    } else if ("connected".equals(status)) {
                        atualizarStatus("⏳ Autenticando...");
                    }
                    break;

                case BluetoothServiceIndustrial.ACTION_DATA_AVAILABLE:
                    String data = intent.getStringExtra(BluetoothServiceIndustrial.EXTRA_DATA);
                    if (data != null) processarMensagem(data.trim());
                    break;
            }
        }
    };

    private void processarMensagem(String msg) {
        Log.d(TAG, "[ESP32] " + msg);

        if (msg.startsWith("READY_OK")) {
            String readyCmdId = BleCommand.parseCmdId(msg);
            if (mBluetoothService != null && mPendingSessionId != null) {
                mBluetoothService.iniciarHeartbeat(mPendingSessionId);
            }
            onReadyOk(readyCmdId);
            return;
        }

        if (msg.startsWith("QUEUE:")) {
            processarMensagemFila(msg);
            return;
        }

        if (msg.startsWith("ACK|")) {
            String ackCmdId = msg.substring(4).trim();
            Log.i(TAG, "[ACK] Recebido cmd=" + ackCmdId + ", iniciando keepalive PING e watchdog");
            iniciarKeepalive();
            iniciarWatchdog();
            return;
        }

        if (msg.startsWith("WARN:FLOW_TIMEOUT") || msg.startsWith("ERROR:FLOW_TIMEOUT")) {
            Log.w(TAG, "[KEEPALIVE] Fluxo timeout detectado pelo ESP32");
            cancelarKeepalive();
            cancelarWatchdog();
            if (mBluetoothService != null) mBluetoothService.pararHeartbeat();

            mValvulaAberta = false;
            atualizarStatus("⏱ Fluxo interrompido. Verifique a torneira.");

            runOnUiThread(() -> {
                if (liberado < qtd_ml) {
                    int restante = qtd_ml - liberado;
                    btnLiberar.setText("Continuar Servindo (" + restante + "ml)");
                    btnLiberar.setVisibility(View.VISIBLE);
                    mLiberacaoFinalizada = false;
                    mComandoEnviado = false; // Permite enviar novo SERVE
                }
                mostrarSnackbar("Tempo esgotado sem fluxo.");
            });
            return;
        }

        if (msg.startsWith("VP:")) {
            resetarWatchdog();
            try {
                double mlFloat = Double.parseDouble(msg.substring(3).trim());
                liberado = (int) Math.round(mlFloat);
                runOnUiThread(() -> {
                    txtMls.setText(liberado + " ML");
                    if (progressBar != null && qtd_ml > 0) {
                        progressBar.setProgress((int) ((liberado / (float) qtd_ml) * 100));
                    }
                });
            } catch (Exception ignored) {}
        }
    }

    private void processarMensagemFila(String msg) {
        String[] parts = msg.split(":");
        if (parts.length < 2) return;
        if ("DONE".equals(parts[1])) {
            cancelarWatchdog();
            cancelarKeepalive();
            if (mBluetoothService != null) mBluetoothService.pararHeartbeat();
            mLiberacaoFinalizada = true;
            mComandoEnviado = false;
            chamarFinishSale(liberado);
            runOnUiThread(() -> {
                atualizarStatus("✓ Dosagem completa!");
                mMainHandler.postDelayed(() -> {
                    startActivity(new Intent(PagamentoConcluido.this, Home.class));
                    finish();
                }, HOME_NAVIGATE_DELAY_MS);
            });
        }
    }

    private void iniciarKeepalive() {
        if (mKeepaliveActive || mBluetoothService == null || mActiveSessionId == null || mLiberacaoFinalizada) return;
        mKeepaliveActive = true;

        if (mKeepaliveRunnable != null) {
            mMainHandler.removeCallbacks(mKeepaliveRunnable);
        }

        mKeepaliveRunnable = new Runnable() {
            @Override
            public void run() {
                if (!mKeepaliveActive || mBluetoothService == null || mActiveSessionId == null) return;

                String pingCmdId = "HB_" + System.currentTimeMillis();
                String ping = BleCommand.buildPing(pingCmdId, mActiveSessionId);
                Log.i(TAG, "[KEEPALIVE] Enviando PING: " + ping);
                mBluetoothService.write(ping);

                mMainHandler.postDelayed(this, 2000L);
            }
        };

        mMainHandler.post(mKeepaliveRunnable);
    }

    private void cancelarKeepalive() {
        if (mKeepaliveRunnable != null) {
            mMainHandler.removeCallbacks(mKeepaliveRunnable);
            mKeepaliveRunnable = null;
        }
        mKeepaliveActive = false;
    }

    /**
     * CORREÇÃO 3: Envia READY primeiro e aguarda READY_OK antes do SERVE.
     */
    private void enfileirarSERVE(int volumeMl, String checkoutId, String sessionId) {
        String cmdId = BleCommand.generateCmdId();
        String readyCmdId = cmdId + "_RDY";

        mPendingVolumeMl = volumeMl;
        mPendingCmdId = cmdId;
        mPendingReadyCmdId = readyCmdId;
        mPendingSessionId = sessionId;

        mActiveCommandId = cmdId;
        mActiveSessionId = sessionId;
        if (mSessionManager != null) mSessionManager.setCommandId(mActiveCommandId);

        String ready = BleCommand.buildReady(readyCmdId, sessionId);
        Log.i(TAG, "[READY] Enviando: " + ready);

        if (mBluetoothService != null) {
            mBluetoothService.write(ready);
            scheduleReadyOkTimeout();
            atualizarStatus("⏳ Enviado READY, aguardando READY_OK...");
        } else {
            Log.e(TAG, "[READY] BluetoothService nulo — Não foi possível enviar READY");
        }
    }

    private void scheduleReadyOkTimeout() {
        cancelReadyOkTimeout();
        mReadyTimeoutRunnable = () -> {
            Log.e(TAG, "[READY_OK] Timeout de 3s atingido sem READY_OK");
            atualizarStatus("❌ Timeout READY_OK");
            mPendingReadyCmdId = null;
            mPendingCmdId = null;
            mPendingSessionId = null;
            mComandoEnviado = false;
        };
        mMainHandler.postDelayed(mReadyTimeoutRunnable, 3000L);
    }

    private void cancelReadyOkTimeout() {
        if (mReadyTimeoutRunnable != null) {
            mMainHandler.removeCallbacks(mReadyTimeoutRunnable);
            mReadyTimeoutRunnable = null;
        }
    }

    private void onReadyOk(String readyCmdId) {
        if (readyCmdId == null || mPendingReadyCmdId == null || !readyCmdId.equals(mPendingReadyCmdId)) {
            Log.w(TAG, "[READY_OK] Ignorado: cmd mismatch (received=" + readyCmdId
                    + ", pending=" + mPendingReadyCmdId + ")");
            return;
        }

        cancelReadyOkTimeout();
        Log.i(TAG, "[READY_OK] Recebido. Aguardando guard-band de 950ms...");

        mMainHandler.postDelayed(() -> {
            if (mPendingCmdId != null && mPendingSessionId != null) {
                String serve = "SERVE|" + mPendingVolumeMl + "|" + mPendingCmdId + "|" + mPendingSessionId;
                Log.i(TAG, "[SERVE] Enviando após READY_OK: " + serve);

                if (mBluetoothService != null) {
                    mComandoEnviado = true;
                    mBluetoothService.write(serve);
                    atualizarStatus("⏳ Aguardando ACK (válvula abrindo)...");
                } else {
                    Log.e(TAG, "[SERVE] BluetoothService nulo — não foi possível enviar SERVE");
                }
            }
            mPendingReadyCmdId = null;
            mPendingCmdId = null;
            mPendingSessionId = null;
        }, 950L);
    }

    private void iniciarVendaEEnfileirar() {
        if (!isInternetAvailable()) {
            Log.e(TAG, "[NET] Sem internet — venda bloqueada, comando BLE NÃO enviado");
            atualizarStatus("❌ Sem internet. Verifique sua rede.");
            runOnUiThread(() ->
                Toast.makeText(PagamentoConcluido.this,
                        "Sem conexão com a internet. Verifique sua rede.",
                        Toast.LENGTH_LONG).show()
            );
            return;
        }
        if (mComandoEnviado) {
            Log.w(TAG, "[PAYMENT] iniciarVendaEEnfileirar() BLOQUEADO — mComandoEnviado=true");
            return;
        }

        Log.i(TAG, "[PAYMENT] Iniciando venda v2.3 — checkout_id=" + checkout_id
                + " | qtd_ml=" + qtd_ml);

        // Usa SessionManager v2.3 se disponível
        if (mSessionManager != null) {
            Log.i(TAG, "[SESSION] Iniciando sessão via SessionManager v2.3");
            mSessionManager.startSession(checkout_id, qtd_ml, android_id);
            // O enfileiramento ocorrerá no callback onSessionStarted()
        } else {
            // Fallback: fluxo legado com start_sale.php
            Log.w(TAG, "[PAYMENT] SessionManager não disponível — usando fluxo legado");
            chamarStartSale(checkout_id, qtd_ml, android_id, () -> enfileirarSERVE(qtd_ml, checkout_id, ""));
        }
    }

    private boolean isInternetAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
        return ni != null && ni.isConnected();
    }

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBluetoothService = ((BluetoothServiceIndustrial.LocalBinder) service).getService();
            mIsServiceBound   = true;

            // Inicializa SessionManager v2.3
            if (mSessionManager == null) {
                mSessionManager = new SessionManager(PagamentoConcluido.this, new SessionManager.Callback() {
                    @Override
                    public void onSessionStarted(String sessionId, String checkoutId) {
                        Log.i(TAG, "[SESSION] Sessão iniciada | session_id=" + sessionId);
                        mActiveSessionId = sessionId;
                        if (mBluetoothService != null) {
                            mBluetoothService.setHeartbeatSessionId(sessionId);
                        }
                        // CORREÇÃO 3: Usar método direto para garantir envio de SERVE
                        enfileirarSERVE(qtd_ml, checkoutId, sessionId);
                    }
                    @Override
                    public void onSessionFinished(String sessionId, int mlReal) {
                        Log.i(TAG, "[SESSION] Sessão finalizada | session_id=" + sessionId + " | ml_real=" + mlReal);
                    }
                    @Override
                    public void onSessionFailed(String sessionId, String reason) {
                        Log.e(TAG, "[SESSION] Sessão falhou | session_id=" + sessionId + " | motivo=" + reason);
                    }
                });
            }

            if (mBluetoothService.isReady()) {
                mMainHandler.postDelayed(() -> iniciarVendaEEnfileirar(), ML_SEND_DELAY_MS);
            }
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            mIsServiceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_pagamento_concluido);
        setupFullscreen();
        android_id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        Bundle extras = getIntent().getExtras();
        if (extras == null) { finish(); return; }
        qtd_ml = Integer.parseInt(extras.get("qtd_ml").toString());
        checkout_id = extras.get("checkout_id").toString();
        imagemUrl = extras.getString("imagem_url");

        btnLiberar  = findViewById(R.id.btnLiberarRestante);
        imageView   = findViewById(R.id.imageBeer2);
        txtQtd      = findViewById(R.id.txtQtdPulsos);
        txtMls      = findViewById(R.id.txtMls);
        txtStatus   = findViewById(R.id.txtStatusLiberacao);
        progressBar = findViewById(R.id.progressLiberacao);

        txtQtd.setText(qtd_ml + " ML");
        carregarImagemComFallback();

        // Listener para o botão "Continuar Servindo"
        btnLiberar.setOnClickListener(v -> {
            btnLiberar.setVisibility(View.GONE);
            atualizarStatus("🔄 Retomando liberação...");
            int restante = qtd_ml - liberado;
            if (restante > 0) {
                Log.i(TAG, "[RETRY_SERVE] Reenviando SERVE com volume restante: " + restante + "ml");
                // Reset flag para permitir novo envio
                mComandoEnviado = false;
                iniciarWatchdog();
                // Envia um novo SERVE com o volume restante
                enfileirarSERVE(restante, checkout_id, mActiveSessionId);
            }
        });

        bindService(new Intent(this, BluetoothServiceIndustrial.class), mServiceConnection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothServiceIndustrial.ACTION_CONNECTION_STATUS);
        filter.addAction(BluetoothServiceIndustrial.ACTION_DATA_AVAILABLE);
        filter.addAction(BluetoothServiceIndustrial.ACTION_WRITE_READY);
        LocalBroadcastManager.getInstance(this).registerReceiver(mServiceUpdateReceiver, filter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mServiceUpdateReceiver);
        cancelarKeepalive();
        if (mBluetoothService != null) mBluetoothService.pararHeartbeat();
    }

    @Override
    protected void onStop() {
        super.onStop();
        cancelarKeepalive();
        if (mBluetoothService != null) mBluetoothService.pararHeartbeat();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelarWatchdog();
        cancelarKeepalive();
        mMainHandler.removeCallbacksAndMessages(null);
        if (currentImageTask != null) currentImageTask.cancel(true);
        imageExecutor.shutdown();
        if (mIsServiceBound) {
            unbindService(mServiceConnection);
            mIsServiceBound = false;
        }
    }

    private void chamarStartSale(String checkoutId, int volumeMl, String deviceId, Runnable onSuccess) {
        Map<String, String> body = new HashMap<>();
        body.put("checkout_id", checkoutId);
        body.put("volume_ml", String.valueOf(volumeMl));
        new ApiHelper(this).sendPost(body, "start_sale.php", new Callback() {
            @Override public void onFailure(Call call, IOException e) { mMainHandler.post(onSuccess); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                response.close();
                mMainHandler.post(onSuccess);
            }
        });
    }

    private void chamarFinishSale(int mlDispensado) {
        Map<String, String> body = new HashMap<>();
        body.put("checkout_id", checkout_id);
        body.put("ml_dispensado", String.valueOf(mlDispensado));
        new ApiHelper(this).sendPost(body, "finish_sale.php", new Callback() {
            @Override public void onFailure(Call call, IOException e) {}
            @Override public void onResponse(Call call, Response response) throws IOException { response.close(); }
        });
    }

    private void carregarImagemComFallback() {
        Sqlite banco = new Sqlite(getApplicationContext());
        byte[] img = banco.getActiveImageData();
        if (img != null && img.length > 0) {
            Bitmap bmp = BitmapFactory.decodeByteArray(img, 0, img.length);
            if (bmp != null && imageView != null) {
                imageView.setImageBitmap(bmp);
                return;
            }
        }
        if (imagemUrl != null && !imagemUrl.isEmpty()) {
            currentImageTask = imageExecutor.submit(() -> {
                try {
                    Tap tempTap = new Tap();
                    tempTap.image = imagemUrl;
                    Bitmap bmp = new ApiHelper(this).getImage(tempTap);
                    if (bmp != null) runOnUiThread(() -> imageView.setImageBitmap(bmp));
                } catch (Exception ignored) {}
            });
        }
    }

    private void iniciarWatchdog() {
        cancelarWatchdog();
        mWatchdogActive = true;
        mWatchdogHandler.postDelayed(mWatchdogRunnable, WATCHDOG_TIMEOUT_MS);
    }

    private void resetarWatchdog() {
        if (mWatchdogActive) {
            mWatchdogHandler.removeCallbacks(mWatchdogRunnable);
            mWatchdogHandler.postDelayed(mWatchdogRunnable, WATCHDOG_TIMEOUT_MS);
        }
    }

    private void cancelarWatchdog() {
        mWatchdogActive = false;
        mWatchdogHandler.removeCallbacks(mWatchdogRunnable);
    }

    private void atualizarStatus(String msg) {
        runOnUiThread(() -> { if (txtStatus != null) txtStatus.setText(msg); });
    }

    private void mostrarSnackbar(String msg) {
        runOnUiThread(() -> {
            View root = findViewById(android.R.id.content);
            if (root != null) Snackbar.make(root, msg, Snackbar.LENGTH_LONG).show();
        });
    }

    private void setupFullscreen() {
        WindowInsetsControllerCompat wic = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        wic.hide(WindowInsetsCompat.Type.systemBars());
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
    }
}
