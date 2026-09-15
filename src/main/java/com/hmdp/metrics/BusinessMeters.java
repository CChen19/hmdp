package com.hmdp.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Business meters: seckill accept ≠ final MySQL success; shop cache path; stream/outbox gauges.
 * Names are stable for docs/BENCH.md and /actuator/metrics.
 */
@Component
public class BusinessMeters {

    public static final String SECKILL_REQUEST = "hmdp.seckill.request";
    public static final String SECKILL_ACCEPT = "hmdp.seckill.accept";
    public static final String SECKILL_REJECT = "hmdp.seckill.reject";
    public static final String SECKILL_FINAL_SUCCESS = "hmdp.seckill.final_success";
    public static final String SECKILL_ACCEPT_TO_DB = "hmdp.seckill.accept_to_db";
    public static final String SHOP_CACHE_HIT = "hmdp.shop.cache.hit";
    public static final String SHOP_CACHE_MISS = "hmdp.shop.cache.miss";
    public static final String SHOP_CACHE_ORIGIN = "hmdp.shop.cache.origin";
    public static final String SHOP_CACHE_UNAVAILABLE = "hmdp.shop.cache.unavailable";
    public static final String STREAM_PENDING = "hmdp.seckill.stream.pending";
    public static final String STREAM_OLDEST_IDLE_MS = "hmdp.seckill.stream.oldest_pending_idle_ms";
    public static final String OUTBOX_PENDING = "hmdp.outbox.pending";
    public static final String GATE_READY = "hmdp.seckill.gate.ready";

    private final Counter seckillRequest;
    private final Counter seckillAccept;
    private final Counter seckillReject;
    private final Counter seckillFinalSuccess;
    private final Timer acceptToDb;
    private final Counter shopHit;
    private final Counter shopMiss;
    private final Counter shopOrigin;
    private final Counter shopUnavailable;

    private final AtomicLong streamPending = new AtomicLong(0L);
    private final AtomicLong oldestPendingIdleMs = new AtomicLong(0L);
    private final AtomicLong outboxPending = new AtomicLong(0L);
    private final AtomicLong gateReady = new AtomicLong(0L);

    /** Soft cap so poison / never-consumed accepts cannot grow the map without bound. */
    static final int ACCEPT_NANOS_CAP = 4096;

    /** orderId → accept nanoTime for accept-to-DB lag (best-effort, same JVM). */
    private final ConcurrentHashMap<Long, Long> acceptNanos = new ConcurrentHashMap<Long, Long>();

    public BusinessMeters(MeterRegistry registry) {
        this.seckillRequest = Counter.builder(SECKILL_REQUEST)
                .description("HTTP seckill attempts")
                .register(registry);
        this.seckillAccept = Counter.builder(SECKILL_ACCEPT)
                .description("HTTP seckill accepts (Result.ok); not MySQL final success")
                .register(registry);
        this.seckillReject = Counter.builder(SECKILL_REJECT)
                .description("HTTP seckill rejects (sold-out, duplicate fail, gate, window, …)")
                .register(registry);
        this.seckillFinalSuccess = Counter.builder(SECKILL_FINAL_SUCCESS)
                .description("MySQL voucher_order insert succeeded in consumer")
                .register(registry);
        this.acceptToDb = Timer.builder(SECKILL_ACCEPT_TO_DB)
                .description("Lag from HTTP accept to MySQL insert (same JVM)")
                .register(registry);
        this.shopHit = Counter.builder(SHOP_CACHE_HIT)
                .description("Shop served from Redis cache")
                .register(registry);
        this.shopMiss = Counter.builder(SHOP_CACHE_MISS)
                .description("Shop cache miss or empty marker")
                .register(registry);
        this.shopOrigin = Counter.builder(SHOP_CACHE_ORIGIN)
                .description("Shop loaded from MySQL origin")
                .register(registry);
        this.shopUnavailable = Counter.builder(SHOP_CACHE_UNAVAILABLE)
                .description("Shop query temporarily unavailable")
                .register(registry);

        Gauge.builder(STREAM_PENDING, streamPending, AtomicLong::get)
                .description("Redis Stream pending (PEL) count for g1")
                .register(registry);
        Gauge.builder(STREAM_OLDEST_IDLE_MS, oldestPendingIdleMs, AtomicLong::get)
                .description("Oldest pending message idle time in ms")
                .register(registry);
        Gauge.builder(OUTBOX_PENDING, outboxPending, AtomicLong::get)
                .description("stock_release_outbox rows still PENDING")
                .register(registry);
        Gauge.builder(GATE_READY, gateReady, AtomicLong::get)
                .description("1 if SeckillConsumeGate ready, else 0")
                .register(registry);
    }

    public void seckillRequest() {
        seckillRequest.increment();
    }

    /**
     * First-time Lua accept (new order id): increment counter and start accept→DB lag tracking.
     */
    public void seckillAccept(Long orderId) {
        seckillAccept(orderId, true);
    }

    /**
     * Idempotent duplicate that returns an already-known order id: count accept, do not re-put lag.
     */
    public void seckillAcceptExisting(Long orderId) {
        seckillAccept(orderId, false);
    }

    /**
     * @param trackLag true only for a newly minted order id; false for duplicate-known id
     */
    public void seckillAccept(Long orderId, boolean trackLag) {
        seckillAccept.increment();
        if (trackLag) {
            trackAcceptLag(orderId);
        }
    }

    private void trackAcceptLag(Long orderId) {
        if (orderId == null) {
            return;
        }
        if (acceptNanos.size() >= ACCEPT_NANOS_CAP) {
            // Drop an arbitrary entry so the map cannot grow without bound under poison / never-consumed.
            java.util.Iterator<Long> it = acceptNanos.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        if (acceptNanos.size() < ACCEPT_NANOS_CAP) {
            acceptNanos.put(orderId, System.nanoTime());
        }
    }

    public void seckillReject() {
        seckillReject.increment();
    }

    /**
     * Called after a new MySQL order row is inserted (not idempotent early-return).
     */
    public void seckillFinalSuccess(Long orderId) {
        seckillFinalSuccess.increment();
        if (orderId == null) {
            return;
        }
        Long start = acceptNanos.remove(orderId);
        if (start != null) {
            acceptToDb.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /** Package-visible for tests: size of the accept→DB lag map. */
    int acceptLagMapSize() {
        return acceptNanos.size();
    }

    public void shopHit() {
        shopHit.increment();
    }

    public void shopMiss() {
        shopMiss.increment();
    }

    public void shopOrigin() {
        shopOrigin.increment();
    }

    public void shopUnavailable() {
        shopUnavailable.increment();
    }

    public void updateStreamPending(long pending, long oldestIdleMs) {
        streamPending.set(pending);
        oldestPendingIdleMs.set(oldestIdleMs);
    }

    public void updateOutboxPending(long pending) {
        outboxPending.set(pending);
    }

    public void updateGateReady(boolean ready) {
        gateReady.set(ready ? 1L : 0L);
    }

    public long getStreamPending() {
        return streamPending.get();
    }

    public long getOldestPendingIdleMs() {
        return oldestPendingIdleMs.get();
    }

    public long getOutboxPending() {
        return outboxPending.get();
    }

    public boolean isGateReady() {
        return gateReady.get() == 1L;
    }

    /** Test helper: bind a gauge supplier without going through Redis. */
    public void bindGateSupplier(Supplier<Boolean> ready) {
        updateGateReady(Boolean.TRUE.equals(ready.get()));
    }
}
