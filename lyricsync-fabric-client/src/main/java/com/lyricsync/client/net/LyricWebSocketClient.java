package com.lyricsync.client.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lyricsync.client.model.LyricUpdateMessage;
import com.lyricsync.client.queue.LyricEventQueue;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.enums.ReadyState;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;

import java.net.URI;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class LyricWebSocketClient extends WebSocketClient {
    private static final int RECONNECT_DELAY_SECONDS = 2;

    private final Logger logger;
    private final ScheduledExecutorService reconnectExecutor;
    private volatile boolean stopped;

    public LyricWebSocketClient(URI serverUri, Logger logger) {
        super(serverUri);
        this.logger = Objects.requireNonNull(logger, "logger");
        this.reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lyricsync-ws-reconnect");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        logger.info("[LyricSync] WebSocket connected: {}", getURI());
    }

    @Override
    public void onMessage(String message) {
        try {
            JsonObject root = JsonParser.parseString(message).getAsJsonObject();
            String type = root.has("type") ? root.get("type").getAsString() : "";
            if (!"LYRIC_UPDATE".equals(type)) {
                return;
            }

            JsonObject data = root.has("data") ? root.getAsJsonObject("data") : new JsonObject();
            String text = data.has("text") ? data.get("text").getAsString() : "";
            int duration = data.has("duration") ? data.get("duration").getAsInt() : 0;
            boolean isTranslated = data.has("is_translated") && data.get("is_translated").getAsBoolean();
            LyricEventQueue.offer(new LyricUpdateMessage(text, duration, isTranslated));
        } catch (Exception ex) {
            logger.warn("[LyricSync] Failed to parse lyric JSON: {}", message, ex);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        logger.warn("[LyricSync] WebSocket closed (code={}, remote={}): {}", code, remote, reason);
        scheduleReconnect();
    }

    @Override
    public void onError(Exception ex) {
        logger.warn("[LyricSync] WebSocket error: {}", ex.getMessage(), ex);
    }

    public void startClient() {
        stopped = false;
        connect();
    }

    public void stopClient() {
        stopped = true;
        try {
            close();
        } finally {
            reconnectExecutor.shutdownNow();
        }
    }

    private void scheduleReconnect() {
        if (stopped || reconnectExecutor.isShutdown()) {
            return;
        }
        reconnectExecutor.schedule(() -> {
            if (stopped) {
                return;
            }
            try {
                ReadyState state = getReadyState();
                if (!isOpen() && state == ReadyState.CLOSED) {
                    logger.info("[LyricSync] Reconnecting to {}", getURI());
                    reconnect();
                }
            } catch (Exception ex) {
                logger.warn("[LyricSync] Reconnect attempt failed", ex);
            }
        }, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
    }
}
