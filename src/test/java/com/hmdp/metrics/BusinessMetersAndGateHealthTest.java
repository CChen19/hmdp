package com.hmdp.metrics;

import com.hmdp.seckill.SeckillConsumeGate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests: meters increment; gate health DOWN when closed. No live DB/Redis.
 */
class BusinessMetersAndGateHealthTest {

    @Test
    void seckillMeters_distinguishAcceptFromFinalSuccess() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMeters meters = new BusinessMeters(registry);

        meters.seckillRequest();
        meters.seckillAccept(42L);
        meters.seckillReject();
        meters.seckillFinalSuccess(42L);

        assertEquals(1.0, registry.get(BusinessMeters.SECKILL_REQUEST).counter().count());
        assertEquals(1.0, registry.get(BusinessMeters.SECKILL_ACCEPT).counter().count());
        assertEquals(1.0, registry.get(BusinessMeters.SECKILL_REJECT).counter().count());
        assertEquals(1.0, registry.get(BusinessMeters.SECKILL_FINAL_SUCCESS).counter().count());
        assertTrue(registry.get(BusinessMeters.SECKILL_ACCEPT_TO_DB).timer().count() >= 1);
        assertEquals(0, meters.acceptLagMapSize());
    }

    @Test
    void acceptLagMap_duplicateKnownDoesNotGrow_andCapDoesNotThrow() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMeters meters = new BusinessMeters(registry);

        meters.seckillAccept(100L);
        assertEquals(1, meters.acceptLagMapSize());
        meters.seckillFinalSuccess(100L);
        assertEquals(0, meters.acceptLagMapSize());
        assertTrue(registry.get(BusinessMeters.SECKILL_ACCEPT_TO_DB).timer().count() >= 1);

        meters.seckillAccept(200L);
        assertEquals(1, meters.acceptLagMapSize());
        meters.seckillAcceptExisting(200L);
        meters.seckillAccept(200L, false);
        assertEquals(1, meters.acceptLagMapSize());
        // first-accept(100) + first-accept(200) + existing + false-track = 4 accepts
        assertEquals(4.0, registry.get(BusinessMeters.SECKILL_ACCEPT).counter().count());

        for (long i = 0; i < BusinessMeters.ACCEPT_NANOS_CAP + 50; i++) {
            meters.seckillAccept(1000L + i);
        }
        assertTrue(meters.acceptLagMapSize() <= BusinessMeters.ACCEPT_NANOS_CAP);
    }

    @Test
    void shopCacheMeters_increment() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMeters meters = new BusinessMeters(registry);
        meters.shopHit();
        meters.shopMiss();
        meters.shopOrigin();
        meters.shopUnavailable();
        assertEquals(1.0, registry.get(BusinessMeters.SHOP_CACHE_HIT).counter().count());
        assertEquals(1.0, registry.get(BusinessMeters.SHOP_CACHE_MISS).counter().count());
        assertEquals(1.0, registry.get(BusinessMeters.SHOP_CACHE_ORIGIN).counter().count());
        assertEquals(1.0, registry.get(BusinessMeters.SHOP_CACHE_UNAVAILABLE).counter().count());
    }

    @Test
    void gateHealth_downWhenClosed() {
        SeckillConsumeGate gate = new SeckillConsumeGate();
        SeckillConsumeGateHealthIndicator indicator = new SeckillConsumeGateHealthIndicator(gate);

        Health down = indicator.health();
        assertEquals(Status.DOWN, down.getStatus());
        assertEquals(false, down.getDetails().get("ready"));

        gate.markReady();
        Health up = indicator.health();
        assertEquals(Status.UP, up.getStatus());
        assertEquals(true, up.getDetails().get("ready"));
    }

    @Test
    void gauges_updateForOpsSnapshot() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMeters meters = new BusinessMeters(registry);
        meters.updateGateReady(true);
        meters.updateStreamPending(3L, 45000L);
        meters.updateOutboxPending(2L);
        assertEquals(1.0, registry.get(BusinessMeters.GATE_READY).gauge().value());
        assertEquals(3.0, registry.get(BusinessMeters.STREAM_PENDING).gauge().value());
        assertEquals(45000.0, registry.get(BusinessMeters.STREAM_OLDEST_IDLE_MS).gauge().value());
        assertEquals(2.0, registry.get(BusinessMeters.OUTBOX_PENDING).gauge().value());
    }
}
