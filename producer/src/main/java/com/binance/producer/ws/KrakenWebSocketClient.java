package com.binance.producer.ws;

import com.binance.producer.Config;
import com.binance.producer.model.Trade;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class KrakenWebSocketClient extends WebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(KrakenWebSocketClient.class);

    private final Config                   config;
    private final Consumer<Trade>          onTrade;
    private final Consumer<String>         onRawError;
    private final ObjectMapper             mapper    = new ObjectMapper();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private       double                   currentDelay;
    private volatile boolean               closed    = false;

    public KrakenWebSocketClient(Config config,
                                  Consumer<Trade> onTrade,
                                  Consumer<String> onRawError) {
        super(URI.create(config.krakenWsUrl));
        this.config       = config;
        this.onTrade      = onTrade;
        this.onRawError   = onRawError;
        this.currentDelay = config.reconnectDelaySec;
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        log.info("ws.connected uri={}", getURI());
        currentDelay = config.reconnectDelaySec;
        sendSubscribe();
    }

    private void sendSubscribe() {
        List<String> symbols = config.krakenSymbolsList();
        String symbolArray = symbols.stream()
                .map(s -> "\"" + s + "\"")
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        String msg = "{\"method\":\"subscribe\",\"params\":{\"channel\":\"trade\",\"symbol\":[" + symbolArray + "]}}";
        send(msg);
        log.info("ws.subscribed symbols={}", symbols);
    }

    @Override
    public void onMessage(String raw) {
        try {
            JsonNode root    = mapper.readTree(raw);
            String   channel = root.path("channel").asText("");

            // Ignore non-trade messages (heartbeat, subscription acks, status)
            if (!channel.equals("trade")) return;

            // "type" is "snapshot" or "update" — process both
            JsonNode data = root.path("data");
            if (!data.isArray()) return;

            for (JsonNode t : data) {
                Trade trade = parseTrade(t);
                trade.enrich();
                onTrade.accept(trade);
            }
        } catch (Exception e) {
            log.warn("ws.parse_error error={}", e.getMessage());
            onRawError.accept(raw);
        }
    }

    private Trade parseTrade(JsonNode t) {
        Trade trade = new Trade();

        // Normalize: "BTC/USD" → "BTCUSD", "XBT/USD" → "XBTUSD" → then XBT→BTC
        String rawSymbol   = t.path("symbol").asText("").replace("/", "");
        trade.symbol       = rawSymbol.replace("XBT", "BTC");
        trade.price        = t.path("price").asDouble(0);
        trade.quantity     = t.path("qty").asDouble(0);
        trade.tradeId      = t.path("trade_id").asLong(0);
        trade.eventType    = "trade";
        trade.source       = "KRAKEN";

        String timeStr     = t.path("timestamp").asText("");
        trade.tradeTimeMs  = timeStr.isEmpty()
                ? System.currentTimeMillis()
                : Instant.parse(timeStr).toEpochMilli();
        trade.eventTimeMs  = trade.tradeTimeMs;

        // Kraken side "buy" = buyer is aggressor (taker) → maker is seller → isBuyerMaker = false
        trade.isBuyerMaker = "sell".equals(t.path("side").asText(""));

        return trade;
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
