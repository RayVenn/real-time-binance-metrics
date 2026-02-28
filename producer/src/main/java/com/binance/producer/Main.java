package com.binance.producer;

import com.binance.producer.health.HealthServer;
import com.binance.producer.kafka.KafkaProducerClient;
import com.binance.producer.ws.BinanceWebSocketClient;
import com.binance.producer.ws.CoinbaseWebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        Config config = new Config();

        log.info("producer.starting binance_symbols={} coinbase_symbols={} topic={}",
                config.symbolsList(), config.coinbaseSymbolsList(), config.kafkaTopic);

        HealthServer        health = new HealthServer(config.healthPort);
        KafkaProducerClient kafka  = new KafkaProducerClient(config);

        BinanceWebSocketClient binanceWs = new BinanceWebSocketClient(
            config,
            trade -> {
                kafka.produceTrade(trade);
                health.incrementProduced();
                health.setWsConnected(true);
                log.debug("binance.trade symbol={} price={} latency_ms={}", trade.symbol, trade.price, trade.latencyMs);
            },
            raw -> {
                kafka.produceDlq(raw, "ParseError");
                health.incrementDlq();
            }
        );

        CoinbaseWebSocketClient coinbaseWs = new CoinbaseWebSocketClient(
            config,
            trade -> {
                kafka.produceTrade(trade);
                health.incrementProduced();
                health.setWsConnected(true);
                log.debug("coinbase.trade symbol={} price={} latency_ms={}", trade.symbol, trade.price, trade.latencyMs);
            },
            raw -> {
                kafka.produceDlq(raw, "ParseError");
                health.incrementDlq();
            }
        );

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("producer.shutting_down");
            binanceWs.shutdown();
            coinbaseWs.shutdown();
            kafka.flush();
            log.info("producer.stopped");
        }, "shutdown-hook"));

        binanceWs.connect();
        coinbaseWs.connect();
        log.info("producer.health_server_started port={}", config.healthPort);

        Thread.currentThread().join();
    }
}
