package com.binance.producer.health;

import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class HealthServer {

    private static final Logger log = LoggerFactory.getLogger(HealthServer.class);

    private final AtomicLong    produced    = new AtomicLong(0);
    private final AtomicLong    dlqCount    = new AtomicLong(0);
    private final AtomicBoolean wsConnected = new AtomicBoolean(false);

    public HealthServer(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", exchange -> {
            byte[] body = snapshot().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.setExecutor(null);
        Thread thread = new Thread(server::start, "health-server");
        thread.setDaemon(true);
        thread.start();
        log.info("health.started port={}", port);
    }

    public void incrementProduced() { produced.incrementAndGet(); }
    public void incrementDlq()      { dlqCount.incrementAndGet(); }
    public void setWsConnected(boolean connected) { wsConnected.set(connected); }

    private String snapshot() {
        return String.format(
            "{\"status\":\"ok\",\"produced\":%d,\"dlq\":%d,\"wsConnected\":%b}",
            produced.get(), dlqCount.get(), wsConnected.get()
        );
    }
}
