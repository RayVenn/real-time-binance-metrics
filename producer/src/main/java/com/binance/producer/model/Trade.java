package com.binance.producer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Unified trade event for all exchanges. Uses the same @JsonProperty short-key
 * wire format so Flink can deserialize messages from any source with one schema.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Trade {

    @JsonProperty("e") public String  eventType;
    @JsonProperty("E") public long    eventTimeMs;
    @JsonProperty("s") public String  symbol;
    @JsonProperty("t") public long    tradeId;
    @JsonProperty("p") public double  price;
    @JsonProperty("q") public double  quantity;
    @JsonProperty("T") public long    tradeTimeMs;
    @JsonProperty("m") public boolean isBuyerMaker;

    // Set by the producer — not in the exchange payload
    public String source;
    public long   ingestionTimeMs;
    public long   latencyMs;

    public void enrich() {
        this.ingestionTimeMs = System.currentTimeMillis();
        this.latencyMs       = this.ingestionTimeMs - this.tradeTimeMs;
    }
}
