package com.binance.producer.kafka;

import com.binance.producer.Config;
import com.binance.producer.model.DlqMessage;
import com.binance.producer.model.Trade;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

public class KafkaProducerClient {

    private static final Logger log = LoggerFactory.getLogger(KafkaProducerClient.class);

    private final KafkaProducer<String, String> producer;
    private final String                        topic;
    private final String                        dlqTopic;
    private final ObjectMapper                  mapper   = new ObjectMapper();
    private final AtomicInteger                 produced = new AtomicInteger(0);

    public KafkaProducerClient(Config config) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,  config.bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG,               "all");
        props.put(ProducerConfig.RETRIES_CONFIG,             5);
        props.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG,    500);
        props.put(ProducerConfig.LINGER_MS_CONFIG,           5);

        this.producer  = new KafkaProducer<>(props);
        this.topic     = config.kafkaTopic;
        this.dlqTopic  = config.kafkaDlqTopic;
    }

    public void produceTrade(Trade trade) {
        try {
            String json = mapper.writeValueAsString(trade);
            producer.send(new ProducerRecord<>(topic, trade.symbol, json), (meta, err) -> {
                if (err != null) log.error("kafka.delivery_failed error={}", err.getMessage());
            });
            produced.incrementAndGet();
        } catch (Exception e) {
            log.error("kafka.produce_error error={}", e.getMessage());
        }
    }

    public void produceDlq(String raw, String error) {
        try {
            String json = mapper.writeValueAsString(new DlqMessage(raw, error));
            producer.send(new ProducerRecord<>(dlqTopic, json), (meta, err) -> {
                if (err != null) log.error("kafka.dlq_delivery_failed error={}", err.getMessage());
            });
        } catch (Exception e) {
            log.error("kafka.dlq_error error={}", e.getMessage());
        }
    }

    public void flush() {
        producer.flush();
        producer.close();
    }
}
