-- Atomic stock release for cancel outbox.
-- KEYS[1] = outbox:stock:done:{eventKey}
-- KEYS[2] = seckill:stock:{voucherId}
-- Returns 1 if this call applied stock (done-key claimed), 0 if already applied.
-- Done-key claim and stock bump are in one script so a crash cannot leave the
-- done key set without having bumped stock.

local ok = redis.call('SETNX', KEYS[1], '1')
if ok == 1 then
  redis.call('INCR', KEYS[2])
  return 1
end
return 0
