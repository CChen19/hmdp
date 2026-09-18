package com.hmdp.metrics;

import com.hmdp.seckill.SeckillConsumeGate;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Seckill accept is refused while the consume gate is closed — surface that on /actuator/health.
 * Default bean name ("seckillConsumeGateHealthIndicator") is required: Boot strips the
 * "HealthIndicator" suffix for the /health component key. An explicit "seckillConsumeGate"
 * name collides with {@link SeckillConsumeGate} and breaks context startup.
 */
@Component
public class SeckillConsumeGateHealthIndicator implements HealthIndicator {

    private final SeckillConsumeGate gate;

    public SeckillConsumeGateHealthIndicator(SeckillConsumeGate gate) {
        this.gate = gate;
    }

    @Override
    public Health health() {
        if (gate.isReady()) {
            return Health.up().withDetail("ready", true).build();
        }
        return Health.down()
                .withDetail("ready", false)
                .withDetail("reason", "stream group not ready; seckill accept refused")
                .build();
    }
}
