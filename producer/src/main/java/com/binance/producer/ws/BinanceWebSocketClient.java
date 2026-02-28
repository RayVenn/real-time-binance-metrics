package com.binance.producer.ws;

import com.binance.producer.Config;
import com.binance.producer.model.BinanceTrade;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class BinanceWebSocketClient extends WebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(BinanceWebSocketClient.class);

    private final Config                    config;
    private final Consumer<BinanceTrade>    onTrade;
    private final Consumer<String>          onRawError;
    private final ObjectMapper              mapper   = new ObjectMapper();
    private final ScheduledExecutorService  scheduler = Executors.newSingleThreadScheduledExecutor();
    private       double                    currentDelay;
    private volatile boolean                closed   = false;

    public BinanceWebSocketClient(Config config,
                                  Consumer<BinanceTrade> onTrade,
                                  Consumer<String> onRawError) {
        super(buildUri(config));
        this.config       = config;
        this.onTrade      = onTrade;
        this.onRawError   = onRawError;
        this.currentDelay = config.reconnectDelaySec;
    }

    private static URI buildUri(Config config) {
        List<String> symbols = config.symbolsList();
        String streams = symbols.stream()
                .map(s -> s.toLowerCase() + "@trade")
                .collect(Collectors.joining("/"));
        return URI.create(config.binanceWsUrl + "?streams=" + streams);
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        log.info("ws.connected uri={}", getURI());
        currentDelay = config.reconnectDelaySec; // reset backoff on success
    }

    @Override
    public void onMessage(String raw) {
        try {
            // Combined stream wraps each event: {"stream":"...", "data":{...}}
            JsonNode root = mapper.readTree(raw);
            JsonNode data = root.has("data") ? root.get("data") : root;
            BinanceTrade trade = mapper.treeToValue(data, BinanceTrade.class);
            trade.enrich();
            onTrade.accept(trade);
        } catch (Exception e) {
            log.warn("ws.parse_error error={}", e.getMessage());
            onRawError.accept(raw);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        if (!closed) {
            log.warn("ws.disconnected code={} reason={} reconnectIn={}s", code, reason, currentDelay);
            scheduleReconnect();
        }
    }

    @Override
    public void onError(Exception e) {
        log.error("ws.error error={}", e.getMessage());
    }

    public void shutdown() {
        closed = true;
        scheduler.shutdown();
        close();
    }

    private void scheduleReconnect() {
        long delayMs = (long) (currentDelay * 1000);
        scheduler.schedule(() -> {
            if (!closed) {
                log.info("ws.reconnecting uri={}", getURI());
                try {
                    reconnect();
                } catch (Exception e) {
                    log.error("ws.reconnect_failed error={}", e.getMessage());
                    currentDelay = Math.min(currentDelay * 2, config.maxReconnectDelaySec);
                    scheduleReconnect();
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS);
        currentDelay = Math.min(currentDelay * 2, config.maxReconnectDelaySec);
    }
}
