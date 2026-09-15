package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    /** 1 send / phone / minute */
    public static final String LOGIN_CODE_SEND_LIMIT_KEY = "login:code:limit:";
    public static final Long LOGIN_CODE_SEND_LIMIT_TTL = 60L;
    /** failed verify attempts / phone */
    public static final String LOGIN_CODE_FAIL_KEY = "login:code:fail:";
    public static final Long LOGIN_CODE_FAIL_TTL = 10L;
    public static final int LOGIN_CODE_FAIL_MAX = 5;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 30L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String CACHE_TYPE_KEY = "cache:type";

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    /** Seckill activity begin time (epoch seconds) next to stock. */
    public static final String SECKILL_BEGIN_TIME_KEY = "seckill:begin:";
    /** Seckill activity end time (epoch seconds) next to stock. */
    public static final String SECKILL_END_TIME_KEY = "seckill:end:";
    /** Redis set of userIds who passed Lua for a voucher. */
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    /** Hash userId -> orderId for retry returning the original id. */
    public static final String SECKILL_ORDER_ID_MAP_KEY = "seckill:order:id:";
    /** Set of order ids still PROCESSING for a user. */
    public static final String SECKILL_PROCESSING_KEY = "seckill:processing:";
    /** String value = userId; marks Redis-accepted order before MySQL row. */
    public static final String SECKILL_ACCEPT_KEY = "seckill:accept:";
    /** Redis Stream for async seckill orders. */
    public static final String SECKILL_STREAM_KEY = "stream.orders";
    /** Consumer group name (shared across instances). */
    public static final String SECKILL_STREAM_GROUP = "g1";
    /**
     * Min idle for XPENDING+XCLAIM takeover from dead consumers (30s).
     * Raise toward 60s if consumers routinely pause longer than a brief GC.
     */
    public static final long SECKILL_CLAIM_MIN_IDLE_MS = 30_000L;
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";

    /**
     * SETNX marker so Redis stock INCR for a cancel outbox event runs at most once.
     * Value = "1"; no TTL (event_key is unique forever per cancelled order).
     */
    public static final String STOCK_RELEASE_DONE_KEY = "outbox:stock:done:";
}
