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

public class KrakenWebSocketClient extends WebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(KrakenWebSocketClient.class);

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

    public KrakenWebSocketClient(Config config,
                                  Consumer<Trade> onTrade,
                                  Consumer<OrderBookSnapshot> onSnapshot,
                                  Consumer<String> onRawError) {
        super(URI.create(config.krakenWsUrl));
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
        List<String> symbols = config.krakenSymbolsList();
        String symbolArray = symbols.stream()
                .map(s -> "\"" + s + "\"")
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        // Subscribe to trades and order book on the same connection
        send("{\"method\":\"subscribe\",\"params\":{\"channel\":\"trade\",\"symbol\":[" + symbolArray + "]}}");
        send("{\"method\":\"subscribe\",\"params\":{\"channel\":\"book\",\"symbol\":[" + symbolArray + "],\"depth\":20}}");
        log.info("ws.subscribed symbols={}", symbols);
    }

    @Override
    public void onMessage(String raw) {
        try {
            JsonNode root    = mapper.readTree(raw);
            String   channel = root.path("channel").asText("");

            switch (channel) {
                case "trade" -> handleTrades(root);
                case "book"  -> handleOrderBook(root);
                // heartbeat, status, subscription acks — ignore
            }
        } catch (Exception e) {
            log.warn("ws.parse_error error={}", e.getMessage());
            onRawError.accept(raw);
        }
    }

    private void handleTrades(JsonNode root) {
        JsonNode data = root.path("data");
        if (!data.isArray()) return;
        for (JsonNode t : data) {
            Trade trade = parseTrade(t);
            trade.enrich();
            onTrade.accept(trade);
        }
    }

    private Trade parseTrade(JsonNode t) {
        Trade trade    = new Trade();
        String rawSym  = t.path("symbol").asText("").replace("/", "");
        trade.symbol       = rawSym.replace("XBT", "BTC");
        trade.price        = t.path("price").asDouble(0);
        trade.quantity     = t.path("qty").asDouble(0);
        trade.tradeId      = t.path("trade_id").asLong(0);
        trade.eventType    = "trade";
        trade.source       = "KRAKEN";
        String timeStr     = t.path("timestamp").asText("");
        trade.tradeTimeMs  = timeStr.isEmpty() ? System.currentTimeMillis() : Instant.parse(timeStr).toEpochMilli();
        trade.eventTimeMs  = trade.tradeTimeMs;
        trade.isBuyerMaker = "sell".equals(t.path("side").asText(""));
        return trade;
    }

    private void handleOrderBook(JsonNode root) {
        String type      = root.path("type").asText("");
        JsonNode dataArr = root.path("data");
        if (!dataArr.isArray()) return;

        for (JsonNode data : dataArr) {
            String rawSymbol = data.path("symbol").asText("").replace("/", "");
            String symbol    = rawSymbol.replace("XBT", "BTC");

            if (type.equals("snapshot")) {
                bids.clear();
                asks.clear();
                applyLevels(bids, data.path("bids"), false);
                applyLevels(asks, data.path("asks"), false);
            } else {
                applyLevels(bids, data.path("bids"), true);
                applyLevels(asks, data.path("asks"), true);
            }

            String timeStr     = data.path("timestamp").asText("");
            long   timestampMs = timeStr.isEmpty()
                    ? System.currentTimeMillis()
                    : Instant.parse(timeStr).toEpochMilli();

            OrderBookSnapshot snapshot = new OrderBookSnapshot();
            snapshot.source      = "KRAKEN";
            snapshot.symbol      = symbol;
            snapshot.timestampMs = timestampMs;
            snapshot.bids        = toList(bids, 20);
            snapshot.asks        = toList(asks, 20);
            onSnapshot.accept(snapshot);
        }
    }

    // Kraken entry: {"price":62000.0,"qty":0.5}
    private void applyLevels(TreeMap<Double, Double> book, JsonNode array, boolean isDelta) {
        if (!array.isArray()) return;
        for (JsonNode entry : array) {
            double price = entry.path("price").asDouble();
            double qty   = entry.path("qty").asDouble();
            if (isDelta && qty == 0.0) book.remove(price);
            else                       book.put(price, qty);
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
