package com.hmdp.trade;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Phase 3 trade beans + scheduling (timeout cancel, outbox poller).
 */
@Configuration
@EnableScheduling
public class TradeConfig {

    @Bean
    public SimulatedPayClient simulatedPayClient() {
        return new SimulatedPayClient();
    }
}
