# Shop display cache

Logical-expire cache for shop intro / images / category display only.
Not used for seckill stock, order, price, or eligibility decisions.

## States

| State | Redis | Behavior |
|-------|--------|----------|
| **Hit** | `RedisData{data, expireTime}` and `expireTime` in the future | Return shop |
| **Stale** | Same key, `expireTime` in the past | Return stale shop; one owner rebuilds async |
| **Miss** | Key absent | Mutex + DB load: hit → write logical-expire; miss → empty marker |
| **Null / empty** | Key = `""` (short TTL + jitter) | Confirmed not found; no DB hit until TTL |
| **Unavailable** | Redis down or DB error on load | DB browse fallback if possible; never map to「店铺不存在」; otherwise temporary failure |

## Update path

`ShopServiceImpl.update` writes DB first, then **invalidates `cache:shop:{id}` after commit** (`TransactionSynchronization.afterCommit`). If delete fails, log and rely on logical expire / TTL — do not pretend the cache is consistent without a fallback story.

## Locks & rebuild

- Miss/rebuild lock value is an **owner token**; unlock uses `unlock.lua` (compare-and-del).
- Rebuild runs on a **bounded** pool: core 2 / max 10 / queue 200; rejects log a warning.

## Geo list (`GET /shop/of/type?typeId&x&y`, no cache involved)

5 km GEOSEARCH page from `shop:geo:{typeId}` (loaded out of band, not by app code). Page results beyond the window return success with no data; an **empty result page returns success with `[]`** — never an empty `IN ()` / `field(id,)` SQL round-trip (that used to 500 the public endpoint when the geo key is missing or all shops are outside the radius).
