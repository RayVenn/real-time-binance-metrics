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

public class CoinbaseWebSocketClient extends WebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(CoinbaseWebSocketClient.class);

    private final Config                   config;
    private final Consumer<Trade>          onTrade;
    private final Consumer<String>         onRawError;
    private final ObjectMapper             mapper    = new ObjectMapper();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private       double                   currentDelay;
    private volatile boolean               closed    = false;

    public CoinbaseWebSocketClient(Config config,
                                   Consumer<Trade> onTrade,
                                   Consumer<String> onRawError) {
        super(URI.create(config.coinbaseWsUrl));
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
        List<String> symbols = config.coinbaseSymbolsList(); // e.g. ["BTC-USD"]
        String productIds = symbols.stream()
                .map(s -> "\"" + s + "\"")
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        String msg = "{\"type\":\"subscribe\",\"product_ids\":[" + productIds + "],\"channel\":\"market_trades\"}";
        send(msg);
        log.info("ws.subscribed products={}", symbols);
    }

    @Override
    public void onMessage(String raw) {
        try {
            JsonNode root    = mapper.readTree(raw);
            String   channel = root.path("channel").asText("");

            // Ignore non-trade channel messages (subscriptions, heartbeats)
            if (!channel.equals("market_trades")) return;

            JsonNode events = root.path("events");
            if (!events.isArray()) return;

            for (JsonNode event : events) {
                JsonNode trades = event.path("trades");
                if (!trades.isArray()) continue;
                for (JsonNode t : trades) {
                    Trade trade = parseTrade(t);
                    trade.enrich();
                    onTrade.accept(trade);
                }
            }
        } catch (Exception e) {
            log.warn("ws.parse_error error={}", e.getMessage());
            onRawError.accept(raw);
        }
    }

    private Trade parseTrade(JsonNode t) {
        Trade trade = new Trade();

        // Normalize product_id: "BTC-USD" → "BTCUSD"
        trade.symbol       = t.path("product_id").asText("").replace("-", "");
        trade.price        = Double.parseDouble(t.path("price").asText("0"));
        trade.quantity     = Double.parseDouble(t.path("size").asText("0"));
        trade.tradeId      = t.path("trade_id").asLong(0);
        trade.eventTimeMs  = System.currentTimeMillis();
        trade.eventType    = "trade";
        trade.source       = "COINBASE";

        // Parse ISO8601 time string to epoch millis
        String timeStr = t.path("time").asText("");
        trade.tradeTimeMs  = timeStr.isEmpty() ? trade.eventTimeMs : Instant.parse(timeStr).toEpochMilli();

        // Coinbase side: "BUY" = aggressor is buyer → isBuyerMaker = false
        trade.isBuyerMaker = t.path("side").asText("").equals("SELL");

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
