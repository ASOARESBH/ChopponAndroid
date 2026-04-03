package com.example.choppontap;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;

/**
 * CommandQueue — Fila de comandos BLE com suporte ao protocolo v2.0 FRANQ.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * CICLO DE VIDA
 * ═══════════════════════════════════════════════════════════════════════
 *
 *   enqueueServe() → processQueue() → enviarComandoAtivo()
 *        │                                    │
 *        ▼                                    ▼
 *   BleCommand.QUEUED             BleCommand.SENT
 *                                             │
 *                              ┌──────────────┴──────────────┐
 *                              ▼                             ▼
 *                         ACK recebido               ACK timeout
 *                         ACKED                      retry ou ERROR
 *                              │
 *                    ┌─────────┴─────────┐
 *                    ▼                   ▼
 *               DONE recebido       DONE timeout
 *               DONE                ERROR
 *
 * ═══════════════════════════════════════════════════════════════════════
 * NOVIDADES v2.0
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  - Tratamento de ERROR:VOLUME_EXCEEDED (novo)
 *  - Tratamento de ERROR:HMAC_INVALID (novo — alerta de segurança)
 *  - Tratamento de ERROR:DUPLICATE (novo — anti-replay)
 *  - Tratamento de ERROR:VALVE_STUCK (novo — falha mecânica)
 *  - Tratamento de WARN:FLOW_TIMEOUT (novo — barril vazio)
 *  - Tratamento de WARN:VOLUME_EXCEEDED (novo — volume excedido)
 *  - Callback onWarning() para alertas operacionais
 *
 * @version 2.0.0
 * @since   2026-03-22
 */
public class CommandQueue {

    private static final String TAG = "BLE_CMD_QUEUE";

    public static final int  MAX_QUEUE_SIZE  = 10;
    private static final long ACK_TIMEOUT_MS  = 2_000L;
    private static final long DONE_TIMEOUT_MS = 15_000L;  // aumentado de 10s para 15s (sensor de fluxo)
    public static final int  MAX_RETRIES     = 2;

    private final Queue<BleCommand> mQueue  = new LinkedList<>();
    private BleCommand              mActive = null;
    private boolean                 mPaused = false;

    private final Handler  mHandler             = new Handler(Looper.getMainLooper());
    private Runnable       mAckTimeoutRunnable  = null;
    private Runnable       mDoneTimeoutRunnable = null;

    // ── Interfaces de callback ────────────────────────────────────────────────

    public interface Callback {
        void onSend(BleCommand cmd);
        void onAck(BleCommand cmd);
        void onDone(BleCommand cmd);
        void onError(BleCommand cmd, String reason);
        void onQueueFull();
        /** NOVO v2.0 — alerta operacional (barril vazio, volume excedido, etc.) */
        default void onWarning(String warnType) {
            Log.w("BLE_CMD_QUEUE", "[WARN] " + warnType);
        }
    }

    public interface BleWriter {
        boolean write(String data);
    }

    private final Callback  mCallback;
    private final BleWriter mWriter;

    public CommandQueue(BleWriter writer, Callback callback) {
        this.mWriter   = writer;
        this.mCallback = callback;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Enfileiramento
    // ═══════════════════════════════════════════════════════════════════════

    public synchronized BleCommand enqueueServe(int volumeMl, String sessionId) {
        if (mActive != null) {
            Log.w(TAG, "[QUEUE] SERVE bloqueado — comando em execução: " + mActive.commandId);
            return null;
        }
        if (!mQueue.isEmpty()) {
            Log.w(TAG, "[QUEUE] SERVE bloqueado — fila já possui comando pendente");
            return null;
        }
        if (mQueue.size() >= MAX_QUEUE_SIZE) {
            Log.e(TAG, "[QUEUE] Fila cheia (" + MAX_QUEUE_SIZE + ") — rejeitando SERVE vol=" + volumeMl);
            if (mCallback != null) mCallback.onQueueFull();
            return null;
        }

        String cmdId = gerarCmdId();
        BleCommand cmd = new BleCommand(BleCommand.Type.SERVE, cmdId, sessionId, volumeMl);
        mQueue.add(cmd);
        Log.i(TAG, "[QUEUE] enqueue → " + cmd + " | fila=" + mQueue.size());
        processQueue();
        return cmd;
    }

    public synchronized void enqueuePing() {
        if (mActive != null && mActive.type == BleCommand.Type.PING) return;
        String cmdId = gerarCmdId().substring(0, 4);
        BleCommand cmd = new BleCommand(BleCommand.Type.PING, cmdId, "", 0);
        mQueue.add(cmd);
        processQueue();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Processamento de respostas BLE — Protocolo v2.0
    // ═══════════════════════════════════════════════════════════════════════

    public synchronized void onBleResponse(BleParser.ParsedMessage msg) {
        if (msg == null) return;

        Log.d(TAG, "[QUEUE] resposta: " + msg + " | ativo=" + mActive);

        switch (msg.type) {

            // ── Operacionais ─────────────────────────────────────────────────
            case ACK:
                handleAck(msg.commandId);
                break;
            case DONE:
                handleDone(msg.commandId, msg.mlReal, msg.sessionId);
                break;
            case DUPLICATE:
            case ERROR_DUPLICATE:
                handleDuplicate();
                break;

            // ── Erros com retry ───────────────────────────────────────────────
            case ERROR_BUSY:
                handleErrorBusy();
                break;
            case ERROR_WATCHDOG:
                handleErrorWatchdog();
                break;

            // ── Erros sem retry (v2.0) ────────────────────────────────────────
            case ERROR_VOLUME_EXCEEDED:
                Log.e(TAG, "[QUEUE] ERROR:VOLUME_EXCEEDED — volume solicitado inválido");
                if (mActive != null) falharComando(mActive, BleCommand.ErrorCode.VOLUME_EXCEEDED);
                break;

            case ERROR_HMAC_INVALID:
                Log.e(TAG, "[SECURITY] ERROR:HMAC_INVALID — token inválido! Verificar chave HMAC.");
                if (mActive != null) falharComando(mActive, BleCommand.ErrorCode.HMAC_INVALID);
                break;

            case ERROR_SESSION_MISMATCH:
                Log.e(TAG, "[QUEUE] ERROR:SESSION_MISMATCH — SESSION_ID não corresponde");
                if (mActive != null) falharComando(mActive, BleCommand.ErrorCode.SESSION_MISMATCH);
                break;

            case ERROR_NOT_AUTHENTICATED:
                Log.e(TAG, "[QUEUE] ERROR:NOT_AUTHENTICATED — aguardando AUTH:OK");
                if (mActive != null) falharComando(mActive, BleCommand.ErrorCode.NOT_AUTHENTICATED);
                break;
            case ERROR_NOT_READY:
                Log.w(TAG, "[QUEUE] ERROR:NOT_READY — ESP32 ainda não pronto para SERVE");
                handleErrorNotReady();
                break;

            case ERROR_TIMEOUT:
                Log.e(TAG, "[QUEUE] ERROR:TIMEOUT do ESP32");
                if (mActive != null) falharComando(mActive, BleCommand.ErrorCode.TIMEOUT);
                break;

            case ERROR_VALVE_STUCK:
                Log.e(TAG, "[HARDWARE] ERROR:VALVE_STUCK — válvula travada! Verificar hardware.");
                if (mActive != null) falharComando(mActive, BleCommand.ErrorCode.VALVE_STUCK);
                break;

            case ERROR_QUEUE_FULL:
                Log.e(TAG, "[QUEUE] ERROR:QUEUE_FULL — fila do ESP32 cheia");
                if (mActive != null) falharComando(mActive, BleCommand.ErrorCode.QUEUE_FULL);
                break;

            // ── Alertas operacionais (NOVO v2.0) ──────────────────────────────
            case WARN_FLOW_TIMEOUT:
                Log.w(TAG, "[WARN] FLOW_TIMEOUT — barril vazio ou sem fluxo!");
                if (mCallback != null) mCallback.onWarning(BleCommand.WarnCode.FLOW_TIMEOUT);
                // Não falha o comando — o ESP32 enviará DONE com ml_real = 0 ou o volume parcial
                break;

            case WARN_VOLUME_EXCEEDED:
                Log.w(TAG, "[WARN] VOLUME_EXCEEDED — volume real excedeu o solicitado");
                if (mCallback != null) mCallback.onWarning(BleCommand.WarnCode.VOLUME_EXCEEDED);
                break;

            // ── Heartbeat ─────────────────────────────────────────────────────
            case PONG:
                handlePong();
                break;

            // ── Estados ───────────────────────────────────────────────────────
            case STATUS_READY:
                Log.i(TAG, "[QUEUE] STATUS:READY — ESP32 pronto");
                break;
            case STATUS_BUSY:
                Log.w(TAG, "[QUEUE] STATUS:BUSY — ESP32 ocupado");
                break;

            default:
                break;
        }
    }

    public synchronized void onBleDisconnected() {
        Log.w(TAG, "[QUEUE] BLE Desconectado");

        if (mActive != null && mActive.type == BleCommand.Type.SERVE) {
            // Se já enviamos o SERVE, o ESP32 está trabalhando offline.
            // NÃO cancelamos o comando. Apenas pausamos os timeouts.
            Log.i(TAG, "[QUEUE] SERVE em andamento durante desconexão. Mantendo comando ativo para recuperar estado na reconexão.");
            cancelAllTimeouts();
            mPaused = true;
            // Não chamamos falharComando() aqui.
            return;
        } else if (mActive != null) {
            // Para outros comandos (PING, READY), podemos limpar
            Log.w(TAG, "[QUEUE] Desconexão durante comando não-SERVE (" + mActive.type + ") — limpando");
            reset();
            mPaused = true;
        } else {
            reset();
            mPaused = true;
        }
    }

    public synchronized void onBleReady() {
        Log.i(TAG, "[QUEUE] BLE READY — aguardando novos comandos | ativo="
                + mActive + " | fila=" + mQueue.size());
        mPaused = false;
        processQueue();
    }

    public synchronized BleCommand getActiveCommand() { return mActive; }
    public synchronized int size()                    { return mQueue.size(); }

    public synchronized void reset() {
        Log.w(TAG, "[QUEUE] reset() — limpando fila e cancelando ativo");
        cancelAllTimeouts();
        mQueue.clear();
        mActive = null;
        mPaused = false;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Handlers internos
    // ═══════════════════════════════════════════════════════════════════════

    private void handleAck(String ackId) {
        if (mActive == null) {
            Log.w(TAG, "[QUEUE] ACK recebido sem comando ativo");
            return;
        }
        boolean idMatch = (ackId == null || ackId.isEmpty()
                || mActive.commandId.equalsIgnoreCase(ackId));
        if (!idMatch) {
            Log.w(TAG, "[QUEUE] ACK id=" + ackId + " não bate com ativo=" + mActive.commandId);
            return;
        }
        Log.i(TAG, "[QUEUE] ACK → " + mActive.commandId);
        cancelAckTimeout();
        mActive.state = BleCommand.State.ACKED;
        if (mCallback != null) mCallback.onAck(mActive);
        iniciarDoneTimeout();
    }

    private void handleDone(String doneId, int mlReal, String sessionId) {
        if (mActive == null) {
            Log.w(TAG, "[QUEUE] DONE recebido sem comando ativo — ignorando");
            return;
        }
        if (doneId != null && !doneId.isEmpty()
                && !doneId.equalsIgnoreCase(mActive.commandId)) {
            Log.w(TAG, "[QUEUE] DONE id=" + doneId + " não bate com ativo=" + mActive.commandId);
            return;
        }
        Log.i(TAG, "[QUEUE] DONE → " + mActive.commandId
                + " | ml_real=" + mlReal + " | session=" + sessionId);
        cancelAllTimeouts();
        mActive.state  = BleCommand.State.DONE;
        mActive.mlReal = mlReal;
        if (mCallback != null) mCallback.onDone(mActive);
        mActive = null;
        processQueue();
    }

    private void handleDuplicate() {
        Log.w(TAG, "[QUEUE] DUPLICATE — firmware já executou este comando");
        if (mActive != null) {
            mActive.state = BleCommand.State.DONE;
            cancelAllTimeouts();
            if (mCallback != null) mCallback.onDone(mActive);
            mActive = null;
            processQueue();
        }
    }

    private void handleErrorBusy() {
        Log.w(TAG, "[QUEUE] ERROR:BUSY — ESP32 ocupado, aguardando 2s para reenvio");
        if (mActive != null && mActive.canRetry()) {
            mActive.retryCount++;
            mActive.state = BleCommand.State.QUEUED;
            cancelAllTimeouts();
            mHandler.postDelayed(this::processQueue, 2_000L);
        } else if (mActive != null) {
            falharComando(mActive, BleCommand.ErrorCode.BUSY + " — máximo de retries atingido");
        }
    }

    private void handleErrorWatchdog() {
        Log.e(TAG, "[QUEUE] ERROR:WATCHDOG recebido do ESP32 — placa reiniciando");
        if (mActive != null) {
            falharComando(mActive, BleCommand.ErrorCode.WATCHDOG);
        }
    }

    private void handleErrorNotReady() {
        if (mActive == null) return;
        cancelAckTimeout();
        if (mActive.canRetry()) {
            mActive.retryCount++;
            mActive.state = BleCommand.State.QUEUED;
            mHandler.postDelayed(this::processQueue, 1_000L);
        } else {
            falharComando(mActive, BleCommand.ErrorCode.BLE_NOT_READY);
        }
    }

    private void handlePong() {
        Log.d(TAG, "[QUEUE] PONG — removendo PING ativo");
        if (mActive != null && mActive.type == BleCommand.Type.PING) {
            cancelAllTimeouts();
            mActive.state = BleCommand.State.DONE;
            mActive = null;
            processQueue();
        }
    }

    private void processQueue() {
        if (mPaused) {
            Log.d(TAG, "[QUEUE] processQueue() — PAUSADO");
            return;
        }
        if (mActive != null) {
            Log.d(TAG, "[QUEUE] processQueue() — aguardando " + mActive.commandId);
            return;
        }
        if (mQueue.isEmpty()) {
            Log.d(TAG, "[QUEUE] processQueue() — fila vazia");
            return;
        }
        mActive = mQueue.poll();
        enviarComandoAtivo();
    }

    private void enviarComandoAtivo() {
        if (mActive == null) return;

        String bleStr = mActive.toBleString();
        Log.i(TAG, "[QUEUE] SEND " + mActive.type.name() + " " + mActive.commandId
                + " | cmd=[" + bleStr + "]");

        boolean ok = mWriter.write(bleStr);
        if (ok) {
            mActive.state = BleCommand.State.SENT;
            if (mCallback != null) mCallback.onSend(mActive);
            long ackTimeout = (mActive.type == BleCommand.Type.PING) ? 3_000L : ACK_TIMEOUT_MS;
            iniciarAckTimeout(ackTimeout);
        } else {
            Log.e(TAG, "[QUEUE] write() falhou para " + mActive.commandId);
            mActive.retryCount++;
            if (mActive.canRetry()) {
                mActive.state = BleCommand.State.QUEUED;
                mQueue.add(mActive);
                mActive = null;
                mHandler.postDelayed(this::processQueue, 1_000L);
            } else {
                falharComando(mActive,
                        BleCommand.ErrorCode.BLE_WRITE_FAILED
                                + " após " + MAX_RETRIES + " tentativas");
            }
        }
    }

    private void falharComando(BleCommand cmd, String reason) {
        Log.e(TAG, "[QUEUE] ERROR → " + cmd.commandId + " | motivo=" + reason);
        cancelAllTimeouts();
        cmd.state        = BleCommand.State.ERROR;
        cmd.errorMessage = reason;
        if (mCallback != null) mCallback.onError(cmd, reason);
        mActive = null;
        mQueue.clear();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Timeouts
    // ═══════════════════════════════════════════════════════════════════════

    private void iniciarAckTimeout(long ms) {
        cancelAckTimeout();
        mAckTimeoutRunnable = () -> {
            synchronized (CommandQueue.this) {
                if (mActive == null || mActive.state != BleCommand.State.SENT) return;
                Log.e(TAG, "[QUEUE] ACK TIMEOUT (" + ms + "ms) para " + mActive.commandId
                        + " | retry=" + mActive.retryCount + "/" + MAX_RETRIES);
                if (mActive.canRetry()) {
                    mActive.retryCount++;
                    mActive.state = BleCommand.State.QUEUED;
                    enviarComandoAtivo();
                } else {
                    falharComando(mActive, "ACK timeout após " + MAX_RETRIES + " tentativas");
                }
            }
        };
        mHandler.postDelayed(mAckTimeoutRunnable, ms);
    }

    private void iniciarDoneTimeout() {
        cancelDoneTimeout();
        mDoneTimeoutRunnable = () -> {
            synchronized (CommandQueue.this) {
                if (mActive == null || mActive.state != BleCommand.State.ACKED) return;
                Log.e(TAG, "[TIMEOUT] DONE não recebido em " + DONE_TIMEOUT_MS + "ms"
                        + " | cmd=" + mActive.commandId);
                falharComando(mActive, "DONE timeout após " + DONE_TIMEOUT_MS + "ms");
            }
        };
        mHandler.postDelayed(mDoneTimeoutRunnable, DONE_TIMEOUT_MS);
    }

    private void cancelAckTimeout() {
        if (mAckTimeoutRunnable != null) {
            mHandler.removeCallbacks(mAckTimeoutRunnable);
            mAckTimeoutRunnable = null;
        }
    }

    private void cancelDoneTimeout() {
        if (mDoneTimeoutRunnable != null) {
            mHandler.removeCallbacks(mDoneTimeoutRunnable);
            mDoneTimeoutRunnable = null;
        }
    }

    private void cancelAllTimeouts() {
        cancelAckTimeout();
        cancelDoneTimeout();
    }

    private static String gerarCmdId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }
}
