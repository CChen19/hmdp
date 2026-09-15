package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.time.ZoneId;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_BEGIN_TIME_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_END_TIME_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);

        // Publish stock + activity window to Redis only after DB commit
        final Long voucherId = voucher.getId();
        final String stock = voucher.getStock().toString();
        final String beginEpoch = String.valueOf(
                voucher.getBeginTime().atZone(ZoneId.systemDefault()).toEpochSecond());
        final String endEpoch = String.valueOf(
                voucher.getEndTime().atZone(ZoneId.systemDefault()).toEpochSecond());

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishSeckillToRedis(voucherId, stock, beginEpoch, endEpoch);
            }
        });
    }

    /**
     * Writes seckill:stock / seckill:begin / seckill:end. On failure after commit,
     * DB is published but Redis is empty — rebuild manually (see docs/SECKILL-RULES.md).
     */
    private void publishSeckillToRedis(Long voucherId, String stock, String beginEpoch, String endEpoch) {
        try {
            stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucherId, stock);
            stringRedisTemplate.opsForValue().set(SECKILL_BEGIN_TIME_KEY + voucherId, beginEpoch);
            stringRedisTemplate.opsForValue().set(SECKILL_END_TIME_KEY + voucherId, endEpoch);
        } catch (Exception e) {
            log.error("Redis seckill publish FAILED after DB commit for voucherId={}. "
                            + "DB row exists but Redis stock/window may be missing. "
                            + "Manual rebuild: SET {}{} {}; SET {}{} {}; SET {}{} {}. "
                            + "Or re-run a rebuild job. Do not rely on silent empty Redis.",
                    voucherId,
                    SECKILL_STOCK_KEY, voucherId, stock,
                    SECKILL_BEGIN_TIME_KEY, voucherId, beginEpoch,
                    SECKILL_END_TIME_KEY, voucherId, endEpoch,
                    e);
        }
    }
}
