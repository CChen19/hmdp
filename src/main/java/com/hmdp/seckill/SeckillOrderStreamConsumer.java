package com.hmdp.seckill;

import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spring-managed seckill Stream consumer: unique name, group gate, pending recover + claim.
 */
@Slf4j
@Component
public class SeckillOrderStreamConsumer implements SmartLifecycle {

    private static final Duration READ_BLOCK = Duration.ofSeconds(2);
    private static final long CLAIM_SCAN_INTERVAL_MS = 15_000L;
    private static final int CLAIM_BATCH = 10;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private SeckillConsumeGate consumeGate;
    @Resource
    private SeckillStreamMessageHandler messageHandler;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread worker;
    private volatile String consumerName;

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        consumerName = buildConsumerName();
        if (!initGroup()) {
            running.set(false);
            consumeGate.markNotReady();
            log.error("Seckill Stream group init failed — refusing new seckill accepts until restart succeeds");
            return;
        }
        consumeGate.markReady();
        worker = new Thread(this::loop, "seckill-stream-" + consumerName);
        worker.setDaemon(true);
        worker.start();
        log.info("Seckill Stream consumer started name={} group={}", consumerName, RedisConstants.SECKILL_STREAM_GROUP);
    }

    @Override
    public void stop() {
        running.set(false);
        Thread t = worker;
        if (t != null) {
            t.interrupt();
            try {
                t.join(READ_BLOCK.toMillis() + 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        consumeGate.markNotReady();
        log.info("Seckill Stream consumer stopped name={}", consumerName);
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        // Start after Redis template is up; stop early on shutdown so block reads unblock.
        return Integer.MAX_VALUE - 100;
    }

    private boolean initGroup() {
        String key = RedisConstants.SECKILL_STREAM_KEY;
        String group = RedisConstants.SECKILL_STREAM_GROUP;
        try {
            // 0-0: do NOT use $ — that skips existing stream history on first create.
            stringRedisTemplate.opsForStream().createGroup(key, ReadOffset.from("0-0"), group);
            log.info("Created consumer group {} on {} at 0-0 (MKSTREAM)", group, key);
            return true;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("BUSYGROUP")) {
                log.info("Consumer group {} already exists on {}", group, key);
                return true;
            }
            log.error("XGROUP CREATE failed for {} / {}: {}", key, group, msg);
            return false;
        }
    }

    private void loop() {
        String key = RedisConstants.SECKILL_STREAM_KEY;
        String group = RedisConstants.SECKILL_STREAM_GROUP;
        long lastClaimScan = 0L;
        // Startup: drain this consumer's pending, then claim idle from others.
        recoverOwnPending();
        claimIdleFromOthers();
        lastClaimScan = System.currentTimeMillis();

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                long now = System.currentTimeMillis();
                if (now - lastClaimScan >= CLAIM_SCAN_INTERVAL_MS) {
                    recoverOwnPending();
                    claimIdleFromOthers();
                    lastClaimScan = now;
                }

                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from(group, consumerName),
                        StreamReadOptions.empty().count(1).block(READ_BLOCK),
                        StreamOffset.create(key, ReadOffset.lastConsumed())
                );
                if (list == null || list.isEmpty()) {
                    continue;
                }
                processRecord(list.get(0), 1L);
            } catch (Exception e) {
                if (!running.get() || Thread.currentThread().isInterrupted()) {
                    break;
                }
                log.warn("seckill stream read/handle error: {}", e.getMessage());
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void recoverOwnPending() {
        String key = RedisConstants.SECKILL_STREAM_KEY;
        String group = RedisConstants.SECKILL_STREAM_GROUP;
        try {
            while (running.get()) {
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from(group, consumerName),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(key, ReadOffset.from("0"))
                );
                if (list == null || list.isEmpty()) {
                    break;
                }
                MapRecord<String, Object, Object> record = list.get(0);
                long deliveries = lookupDeliveryCount(record.getId());
                processRecord(record, deliveries);
            }
        } catch (Exception e) {
            log.warn("recoverOwnPending failed: {}", e.getMessage());
        }
    }

    /**
     * XPENDING + XCLAIM idle messages from dead consumers (XAUTOCLAIM equivalent on SDR 2.7).
     * Min-idle: {@link RedisConstants#SECKILL_CLAIM_MIN_IDLE_MS} (30s).
     */
    private void claimIdleFromOthers() {
        String key = RedisConstants.SECKILL_STREAM_KEY;
        String group = RedisConstants.SECKILL_STREAM_GROUP;
        try {
            PendingMessages pending = stringRedisTemplate.opsForStream().pending(
                    key, group, Range.unbounded(), (long) CLAIM_BATCH);
            if (pending == null || pending.isEmpty()) {
                return;
            }
            List<RecordId> toClaim = new ArrayList<RecordId>();
            for (PendingMessage pm : pending) {
                if (consumerName.equals(pm.getConsumerName())) {
                    continue;
                }
                if (pm.getElapsedTimeSinceLastDelivery().toMillis()
                        < RedisConstants.SECKILL_CLAIM_MIN_IDLE_MS) {
                    continue;
                }
                toClaim.add(pm.getId());
            }
            if (toClaim.isEmpty()) {
                return;
            }
            List<MapRecord<String, Object, Object>> claimed = claimRecords(toClaim);
            for (MapRecord<String, Object, Object> record : claimed) {
                long deliveries = lookupDeliveryCount(record.getId());
                processRecord(record, deliveries);
            }
        } catch (Exception e) {
            log.warn("claimIdleFromOthers failed: {}", e.getMessage());
        }
    }

    private List<MapRecord<String, Object, Object>> claimRecords(List<RecordId> ids) {
        final String key = RedisConstants.SECKILL_STREAM_KEY;
        final String group = RedisConstants.SECKILL_STREAM_GROUP;
        final String consumer = consumerName;
        final RecordId[] idArr = ids.toArray(new RecordId[0]);
        return stringRedisTemplate.execute((RedisConnection connection) -> {
            List<org.springframework.data.redis.connection.stream.ByteRecord> bytes =
                    connection.streamCommands().xClaim(
                            key.getBytes(StandardCharsets.UTF_8),
                            group,
                            consumer,
                            XClaimOptions.minIdle(Duration.ofMillis(RedisConstants.SECKILL_CLAIM_MIN_IDLE_MS))
                                    .ids(idArr));
            if (bytes == null || bytes.isEmpty()) {
                return Collections.emptyList();
            }
            List<MapRecord<String, Object, Object>> out = new ArrayList<MapRecord<String, Object, Object>>();
            for (org.springframework.data.redis.connection.stream.ByteRecord br : bytes) {
                out.add(MapRecord.create(key, toObjectMap(br)).withId(br.getId()));
            }
            return out;
        });
    }

    private static java.util.Map<Object, Object> toObjectMap(
            org.springframework.data.redis.connection.stream.ByteRecord br) {
        java.util.Map<Object, Object> map = new java.util.HashMap<Object, Object>();
        if (br.getValue() == null) {
            return map;
        }
        for (java.util.Map.Entry<byte[], byte[]> e : br.getValue().entrySet()) {
            String k = e.getKey() == null ? null : new String(e.getKey(), StandardCharsets.UTF_8);
            String v = e.getValue() == null ? null : new String(e.getValue(), StandardCharsets.UTF_8);
            map.put(k, v);
        }
        return map;
    }

    private long lookupDeliveryCount(RecordId id) {
        if (id == null) {
            return 1L;
        }
        try {
            PendingMessages pending = stringRedisTemplate.opsForStream().pending(
                    RedisConstants.SECKILL_STREAM_KEY,
                    Consumer.from(RedisConstants.SECKILL_STREAM_GROUP, consumerName),
                    Range.just(id.getValue()),
                    1L);
            if (pending != null && !pending.isEmpty()) {
                return pending.get(0).getTotalDeliveryCount();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return 1L;
    }

    private void processRecord(MapRecord<String, Object, Object> record, long deliveryCount) {
        ConsumeOutcome outcome = messageHandler.handle(record, deliveryCount);
        if (outcome == ConsumeOutcome.SUCCESS || outcome == ConsumeOutcome.POISON) {
            stringRedisTemplate.opsForStream().acknowledge(
                    RedisConstants.SECKILL_STREAM_KEY,
                    RedisConstants.SECKILL_STREAM_GROUP,
                    record.getId());
        }
        // RETRY: no ACK
    }

    static String buildConsumerName() {
        String jvm = ManagementFactory.getRuntimeMXBean().getName();
        if (jvm != null && !jvm.isEmpty()) {
            // pid@host — unique per process
            return jvm.replace('@', '-') + "-" + UUID.randomUUID().toString().substring(0, 8);
        }
        return "c-" + UUID.randomUUID().toString();
    }

    /** Visible for tests. */
    String getConsumerName() {
        return consumerName;
    }
}
