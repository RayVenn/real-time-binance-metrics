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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class BinanceWebSocketClient extends WebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(BinanceWebSocketClient.class);

    private final Config                         config;
    private final Consumer<Trade>                onTrade;
    private final Consumer<OrderBookSnapshot>    onSnapshot;
    private final Consumer<String>               onRawError;
    private final ObjectMapper                   mapper    = new ObjectMapper();
    private final ScheduledExecutorService       scheduler = Executors.newSingleThreadScheduledExecutor();
    private       double                         currentDelay;
    private volatile boolean                     closed    = false;

    public BinanceWebSocketClient(Config config,
                                  Consumer<Trade> onTrade,
                                  Consumer<OrderBookSnapshot> onSnapshot,
                                  Consumer<String> onRawError) {
        super(buildUri(config));
        this.config       = config;
        this.onTrade      = onTrade;
        this.onSnapshot   = onSnapshot;
        this.onRawError   = onRawError;
        this.currentDelay = config.reconnectDelaySec;
    }

    private static URI buildUri(Config config) {
        List<String> symbols = config.symbolsList();
        // Trade streams + depth streams on the same combined-stream connection
        String tradeStreams = symbols.stream()
                .map(s -> s.toLowerCase() + "@trade")
                .collect(Collectors.joining("/"));
        String allStreams = tradeStreams + "/" + config.binanceDepthStreams;
        return URI.create(config.binanceWsUrl + "?streams=" + allStreams);
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        log.info("ws.connected uri={}", getURI());
        currentDelay = config.reconnectDelaySec;
    }

    @Override
    public void onMessage(String raw) {
        try {
            // Combined stream: {"stream":"<name>","data":{...}}
            JsonNode root   = mapper.readTree(raw);
            String   stream = root.path("stream").asText("");
            JsonNode data   = root.has("data") ? root.get("data") : root;

            if (stream.contains("@depth")) {
                handleOrderBook(stream, data);
            } else {
                handleTrade(data);
            }
        } catch (Exception e) {
            log.warn("ws.parse_error error={}", e.getMessage());
            onRawError.accept(raw);
        }
    }

    private void handleTrade(JsonNode data) {
        Trade trade = mapper.convertValue(data, Trade.class);
        trade.enrich();
        trade.source = "BINANCE";
        onTrade.accept(trade);
    }

    private void handleOrderBook(String stream, JsonNode data) {
        // "btcusdt@depth20@100ms" → "BTCUSDT"
        String symbol = stream.substring(0, stream.indexOf('@')).toUpperCase();

        OrderBookSnapshot snapshot = new OrderBookSnapshot();
        snapshot.source      = "BINANCE";
        snapshot.symbol      = symbol;
        snapshot.timestampMs = System.currentTimeMillis();
        snapshot.bids        = parseLevels(data.path("bids"));
        snapshot.asks        = parseLevels(data.path("asks"));
        onSnapshot.accept(snapshot);
    }

    // Binance depth entry: ["62000.00", "0.5"]
    private List<OrderBookSnapshot.PriceLevel> parseLevels(JsonNode array) {
        List<OrderBookSnapshot.PriceLevel> levels = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode entry : array) {
                double price = Double.parseDouble(entry.get(0).asText());
                double qty   = Double.parseDouble(entry.get(1).asText());
                levels.add(new OrderBookSnapshot.PriceLevel(price, qty));
            }
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
