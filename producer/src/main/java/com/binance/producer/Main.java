package com.binance.producer;

import com.binance.producer.health.HealthServer;
import com.binance.producer.kafka.KafkaProducerClient;
import com.binance.producer.ws.BinanceWebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        Config config = new Config();

        log.info("producer.starting symbols={} topic={}", config.symbolsList(), config.kafkaTopic);

        HealthServer       health = new HealthServer(config.healthPort);
        KafkaProducerClient kafka  = new KafkaProducerClient(config);

        BinanceWebSocketClient ws = new BinanceWebSocketClient(
            config,
            trade -> {
                kafka.produceTrade(trade);
                health.incrementProduced();
                health.setWsConnected(true);
                log.debug("producer.trade symbol={} price={} qty={} latency_ms={}",
                          trade.symbol, trade.price, trade.quantity, trade.latencyMs);
            },
            raw -> {
                kafka.produceDlq(raw, "ParseError");
                health.incrementDlq();
            }
        );

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("producer.shutting_down");
            ws.shutdown();
            kafka.flush();
            log.info("producer.stopped");
        }, "shutdown-hook"));

        ws.connect();
        log.info("producer.health_server_started port={}", config.healthPort);

        // Block main thread — the WebSocket and health server run on their own threads
        Thread.currentThread().join();
    }
}
