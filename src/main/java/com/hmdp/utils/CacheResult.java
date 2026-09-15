package com.hmdp.utils;

/**
 * Shop/cache query outcome: hit, confirmed absence, or temporary failure.
 */
public final class CacheResult<T> {

    public enum Status {
        /** Value present (may be logically stale). */
        OK,
        /** Confirmed missing in DB or empty marker. */
        NOT_FOUND,
        /** Redis/DB temporarily unavailable — not the same as not found. */
        UNAVAILABLE
    }

    private final Status status;
    private final T data;

    private CacheResult(Status status, T data) {
        this.status = status;
        this.data = data;
    }

    public static <T> CacheResult<T> ok(T data) {
        return new CacheResult<T>(Status.OK, data);
    }

    public static <T> CacheResult<T> notFound() {
        return new CacheResult<T>(Status.NOT_FOUND, null);
    }

    public static <T> CacheResult<T> unavailable() {
        return new CacheResult<T>(Status.UNAVAILABLE, null);
    }

    public Status getStatus() {
        return status;
    }

    public T getData() {
        return data;
    }

    public boolean isOk() {
        return status == Status.OK;
    }
}
