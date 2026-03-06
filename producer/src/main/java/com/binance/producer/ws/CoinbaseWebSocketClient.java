package com.binance.producer.ws;

import com.binance.producer.Config;
import com.binance.producer.model.OrderBookSnapshot;
import com.binance.producer.model.Trade;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class CoinbaseWebSocketClient extends WebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(CoinbaseWebSocketClient.class);

    private final Config                         config;
    private final Consumer<Trade>                onTrade;
    private final Consumer<OrderBookSnapshot>    onSnapshot;
    private final Consumer<String>               onRawError;
    private final ObjectMapper                   mapper    = new ObjectMapper();
    private final ScheduledExecutorService       scheduler = Executors.newSingleThreadScheduledExecutor();
    private       double                         currentDelay;
    private volatile boolean                     closed    = false;

    // In-memory order book (descending bids, ascending asks)
    private final TreeMap<Double, Double> bids = new TreeMap<>((a, b) -> Double.compare(b, a));
    private final TreeMap<Double, Double> asks = new TreeMap<>();

    public CoinbaseWebSocketClient(Config config,
                                   Consumer<Trade> onTrade,
                                   Consumer<OrderBookSnapshot> onSnapshot,
                                   Consumer<String> onRawError) {
        super(URI.create(config.coinbaseWsUrl));
        this.config       = config;
        this.onTrade      = onTrade;
        this.onSnapshot   = onSnapshot;
        this.onRawError   = onRawError;
        this.currentDelay = config.reconnectDelaySec;
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        log.info("ws.connected uri={}", getURI());
        currentDelay = config.reconnectDelaySec;
        bids.clear();
        asks.clear();
        sendSubscribe();
    }

    private void sendSubscribe() {
        List<String> symbols = config.coinbaseSymbolsList();
        String productIds = symbols.stream()
                .map(s -> "\"" + s + "\"")
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        // Subscribe to trades and order book on the same connection
        send("{\"type\":\"subscribe\",\"product_ids\":[" + productIds + "],\"channel\":\"market_trades\"}");
        send("{\"type\":\"subscribe\",\"product_ids\":[" + productIds + "],\"channel\":\"level2\"}");
        log.info("ws.subscribed products={}", symbols);
    }

    @Override
    public void onMessage(String raw) {
        try {
            JsonNode root    = mapper.readTree(raw);
            String   channel = root.path("channel").asText("");

            switch (channel) {
                case "market_trades" -> handleTrades(root);
                case "l2_data"       -> handleOrderBook(root);
                // subscriptions, heartbeats — ignore
            }
        } catch (Exception e) {
            log.warn("ws.parse_error error={}", e.getMessage());
            onRawError.accept(raw);
        }
    }

    private void handleTrades(JsonNode root) {
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
    }

    private Trade parseTrade(JsonNode t) {
        Trade trade = new Trade();
        trade.symbol       = t.path("product_id").asText("").replace("-", "");
        trade.price        = Double.parseDouble(t.path("price").asText("0"));
        trade.quantity     = Double.parseDouble(t.path("size").asText("0"));
        trade.tradeId      = t.path("trade_id").asLong(0);
        trade.eventTimeMs  = System.currentTimeMillis();
        trade.eventType    = "trade";
        trade.source       = "COINBASE";
        String timeStr     = t.path("time").asText("");
        trade.tradeTimeMs  = timeStr.isEmpty() ? trade.eventTimeMs : Instant.parse(timeStr).toEpochMilli();
        trade.isBuyerMaker = t.path("side").asText("").equals("SELL");
        return trade;
    }

    private void handleOrderBook(JsonNode root) {
        JsonNode events = root.path("events");
        if (!events.isArray()) return;

        for (JsonNode event : events) {
            String type      = event.path("type").asText("");
            String productId = event.path("product_id").asText("");
            String symbol    = productId.replace("-", "");

            if (type.equals("snapshot")) {
                bids.clear();
                asks.clear();
            }

            JsonNode updates = event.path("updates");
            if (!updates.isArray()) continue;

            for (JsonNode entry : updates) {
                String side     = entry.path("side").asText("");
                double price    = Double.parseDouble(entry.path("price_level").asText("0"));
                double quantity = Double.parseDouble(entry.path("new_quantity").asText("0"));
                TreeMap<Double, Double> book = side.equals("bid") ? bids : asks;
                if (quantity == 0.0) book.remove(price);
                else                 book.put(price, quantity);
            }

            String timeStr     = event.path("time").asText("");
            long   timestampMs = timeStr.isEmpty()
                    ? System.currentTimeMillis()
                    : Instant.parse(timeStr).toEpochMilli();

            OrderBookSnapshot snapshot = new OrderBookSnapshot();
            snapshot.source      = "COINBASE";
            snapshot.symbol      = symbol;
            snapshot.timestampMs = timestampMs;
            snapshot.bids        = toList(bids, 20);
            snapshot.asks        = toList(asks, 20);
            onSnapshot.accept(snapshot);
        }
    }

    private List<OrderBookSnapshot.PriceLevel> toList(TreeMap<Double, Double> book, int limit) {
        List<OrderBookSnapshot.PriceLevel> levels = new ArrayList<>();
        int count = 0;
        for (var e : book.entrySet()) {
            if (count++ >= limit) break;
            levels.add(new OrderBookSnapshot.PriceLevel(e.getKey(), e.getValue()));
        }
        return levels;
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
