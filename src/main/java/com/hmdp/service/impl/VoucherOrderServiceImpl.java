package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.OrderQueryVO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillDeadLetter;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.metrics.BusinessMeters;
import com.hmdp.seckill.SeckillConsumeGate;
import com.hmdp.seckill.SeckillDeadLetterService;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Seckill accept + DB insert. Stream consume lives in {@link com.hmdp.seckill.SeckillOrderStreamConsumer}.
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private SeckillConsumeGate seckillConsumeGate;
    @Resource
    private SeckillDeadLetterService seckillDeadLetterService;
    @Resource
    private BusinessMeters businessMeters;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<Long>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 秒杀优惠券(消息队列)
     *
     * @param voucherId 券id
     * @return {@link Result}
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        businessMeters.seckillRequest();
        if (!seckillConsumeGate.isReady()) {
            return reject("秒杀服务未就绪，请稍后重试");
        }
        // Defense in depth: reject outside activity window before Lua
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null || voucher.getBeginTime() == null || voucher.getEndTime() == null) {
            return reject("秒杀活动未开放");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(voucher.getBeginTime())) {
            return reject("秒杀尚未开始");
        }
        if (now.isAfter(voucher.getEndTime())) {
            return reject("秒杀已经结束");
        }
        UserDTO user = UserHolder.getUser();
        Long orderId = redisIdWorker.nextId("order");
        long nowEpoch = now.atZone(ZoneId.systemDefault()).toEpochSecond();
        Long res = stringRedisTemplate.execute(
                SECKILL_SCRIPT
                , Collections.emptyList()
                , voucherId.toString()
                , user.getId().toString()
                , orderId.toString()
                , String.valueOf(nowEpoch));
        int r = res == null ? -1 : res.intValue();
        if (r != 0) {
            if (r == 1) {
                return reject("库存不足");
            }
            if (r == 2) {
                return resolveDuplicateOrder(voucherId, user.getId());
            }
            return reject("不在活动时间");
        }
        return accept(orderId);
    }

    /**
     * Retry of same user+voucher: return original order id (Redis map or DB), not only「禁止重复下单」.
     */
    private Result resolveDuplicateOrder(Long voucherId, Long userId) {
        Object mapped = stringRedisTemplate.opsForHash().get(
                RedisConstants.SECKILL_ORDER_ID_MAP_KEY + voucherId, userId.toString());
        if (mapped != null) {
            return accept(Long.valueOf(String.valueOf(mapped)));
        }
        VoucherOrder existing = lambdaQuery()
                .eq(VoucherOrder::getVoucherId, voucherId)
                .eq(VoucherOrder::getUserId, userId)
                .one();
        if (existing != null) {
            return accept(existing.getId());
        }
        return reject("禁止重复下单");
    }

    private Result accept(Long orderId) {
        businessMeters.seckillAccept(orderId);
        return Result.ok(orderId);
    }

    private Result reject(String msg) {
        businessMeters.seckillReject();
        return Result.fail(msg);
    }

    @Override
    public Result queryOrderById(Long orderId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("未登录");
        }
        VoucherOrder row = getById(orderId);
        if (row != null) {
            if (!user.getId().equals(row.getUserId())) {
                return Result.fail("无权查看该订单");
            }
            return Result.ok(new OrderQueryVO("SUCCESS", row.getId(), row.getVoucherId(), row.getStatus()));
        }
        SeckillDeadLetter dead = seckillDeadLetterService.lambdaQuery()
                .eq(SeckillDeadLetter::getOrderId, orderId)
                .orderByDesc(SeckillDeadLetter::getId)
                .last("LIMIT 1")
                .one();
        if (dead != null) {
            if (dead.getUserId() != null && !user.getId().equals(dead.getUserId())) {
                return Result.fail("无权查看该订单");
            }
            return Result.ok(new OrderQueryVO("FAILED", orderId, dead.getVoucherId(), null));
        }
        String owner = stringRedisTemplate.opsForValue().get(RedisConstants.SECKILL_ACCEPT_KEY + orderId);
        if (owner != null) {
            if (!user.getId().toString().equals(owner)) {
                return Result.fail("无权查看该订单");
            }
            return Result.ok(new OrderQueryVO("PROCESSING", orderId, null, null));
        }
        return Result.fail("订单不存在");
    }

    @Override
    public Result listMyOrders() {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("未登录");
        }
        List<OrderQueryVO> list = new ArrayList<OrderQueryVO>();
        Set<Long> seen = new HashSet<Long>();
        List<VoucherOrder> dbOrders = lambdaQuery()
                .eq(VoucherOrder::getUserId, user.getId())
                .orderByDesc(VoucherOrder::getCreateTime)
                .list();
        for (VoucherOrder o : dbOrders) {
            seen.add(o.getId());
            list.add(new OrderQueryVO("SUCCESS", o.getId(), o.getVoucherId(), o.getStatus()));
        }
        Set<String> processing = stringRedisTemplate.opsForSet().members(
                RedisConstants.SECKILL_PROCESSING_KEY + user.getId());
        if (processing != null) {
            for (String idStr : processing) {
                try {
                    Long oid = Long.valueOf(idStr);
                    if (seen.contains(oid)) {
                        continue;
                    }
                    list.add(new OrderQueryVO("PROCESSING", oid, null, null));
                } catch (NumberFormatException ignored) {
                    // skip bad member
                }
            }
        }
        List<SeckillDeadLetter> fails = seckillDeadLetterService.lambdaQuery()
                .eq(SeckillDeadLetter::getUserId, user.getId())
                .orderByDesc(SeckillDeadLetter::getId)
                .list();
        for (SeckillDeadLetter d : fails) {
            if (d.getOrderId() == null || seen.contains(d.getOrderId())) {
                continue;
            }
            seen.add(d.getOrderId());
            list.add(new OrderQueryVO("FAILED", d.getOrderId(), d.getVoucherId(), null));
        }
        return Result.ok(list);
    }

    @Override
    @NotNull
    @Transactional(rollbackFor = Exception.class)
    public Result getResult(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long count = lambdaQuery()
                .eq(VoucherOrder::getVoucherId, voucherId)
                .eq(VoucherOrder::getUserId, userId)
                .count();
        if (count > 0) {
            return Result.fail("禁止重复购买");
        }
        boolean isSuccess = seckillVoucherService.update(
                new LambdaUpdateWrapper<SeckillVoucher>()
                        .eq(SeckillVoucher::getVoucherId, voucherId)
                        .gt(SeckillVoucher::getStock, 0)
                        .setSql("stock=stock-1"));
        if (!isSuccess) {
            return Result.fail("库存不足");
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        Long orderId = redisIdWorker.nextId("order");
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setId(orderId);
        this.save(voucherOrder);
        return Result.ok(orderId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // Idempotent: same order id already persisted → success, do not deduct again
        VoucherOrder existing = getById(voucherOrder.getId());
        if (existing != null) {
            return;
        }
        // Same user+voucher already has a row (unique key) → success, do not deduct again
        Long dup = lambdaQuery()
                .eq(VoucherOrder::getUserId, voucherOrder.getUserId())
                .eq(VoucherOrder::getVoucherId, voucherOrder.getVoucherId())
                .count();
        if (dup != null && dup > 0) {
            return;
        }
        boolean isSuccess = seckillVoucherService.update(
                new LambdaUpdateWrapper<SeckillVoucher>()
                        .eq(SeckillVoucher::getVoucherId, voucherOrder.getVoucherId())
                        .gt(SeckillVoucher::getStock, 0)
                        .setSql("stock=stock-1"));
        if (!isSuccess) {
            throw new RuntimeException("库存不足，扣减失败");
        }
        if (voucherOrder.getStatus() == null) {
            voucherOrder.setStatus(1); // 待支付
        }
        this.save(voucherOrder);
        businessMeters.seckillFinalSuccess(voucherOrder.getId());
    }
}
