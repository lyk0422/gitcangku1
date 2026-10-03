package com.example.starter.service;

import com.example.starter.domain.BottleStatus;
import com.example.starter.domain.DispenseRecord;
import com.example.starter.domain.ReagentBottle;
import com.example.starter.repo.BottleRepository;
import com.example.starter.repo.DispenseRepository;
import com.example.starter.repo.RecallRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 试剂瓶业务：接收、首次开封、按 0.1 mL 精度幂等领用、批号召回隔离与按瓶查询。
 * 并发通过行锁（SELECT ... FOR UPDATE）在同一已提交状态上串行完成。
 */
@Service
public class ReagentService {

    private final BottleRepository bottles;
    private final DispenseRepository dispenses;
    private final RecallRepository recalls;
    private final Clock clock;

    public ReagentService(BottleRepository bottles, DispenseRepository dispenses,
                          RecallRepository recalls, Clock clock) {
        this.bottles = bottles;
        this.dispenses = dispenses;
        this.recalls = recalls;
        this.clock = clock;
    }

    /**
     * 领用结果：record 为首次写入或被重放的记录；replayed 为 true 表示同参重放。
     */
    public record DispenseOutcome(DispenseRecord record, boolean replayed) {
    }

    /**
     * 召回结果：登记召回并返回被隔离瓶的ID列表。
     */
    public record RecallOutcome(long recallId, String batchNo, String reason,
                                Instant recalledAt, List<Long> quarantinedBottleIds) {
    }

    /**
     * 接收登记：记录批号、容量（0.1 mL 精度）与供应商到期 UTC 时刻。
     */
    @Transactional
    public ReagentBottle receive(String batchNo, BigDecimal capacityMl, Instant supplierExpiryAt) {
        requireAmount(capacityMl, "capacityMl");
        if (supplierExpiryAt == null) {
            throw BizException.badRequest("INVALID_EXPIRY", "supplierExpiryAt 不能为空");
        }
        long id = bottles.insert(batchNo, capacityMl, supplierExpiryAt, Instant.now(clock));
        return bottles.findById(id).orElseThrow();
    }

    /**
     * 首次开封：固定开封时刻；重复开封不移动开封时刻，直接返回当前状态。
     */
    @Transactional
    public ReagentBottle open(long bottleId) {
        ReagentBottle bottle = lockBottle(bottleId);
        if (bottle.status() == BottleStatus.QUARANTINED) {
            throw BizException.unprocessable("BOTTLE_QUARANTINED", "试剂瓶已隔离，不能开封");
        }
        if (bottle.openedAt() == null) {
            Instant now = Instant.now(clock);
            bottles.markOpened(bottleId, now);
            return bottles.findById(bottleId).orElseThrow();
        }
        return bottle;
    }

    /**
     * 领用：仅当已开封、未隔离、当前时刻早于实际截止（左闭右开）且余量充足时扣减。
     * requestId 同参重放返回首次记录，异参返回 409；失败请求不写入也不占键。
     */
    @Transactional
    public DispenseOutcome dispense(long bottleId, String requestId, BigDecimal amountMl) {
        requireAmount(amountMl, "amountMl");
        ReagentBottle bottle = lockBottle(bottleId);
        var existing = dispenses.findByRequestId(requestId);
        if (existing.isPresent()) {
            DispenseRecord first = existing.get();
            if (first.bottleId() == bottleId && first.amountMl().compareTo(amountMl) == 0) {
                return new DispenseOutcome(first, true);
            }
            throw BizException.conflict("IDEMPOTENCY_CONFLICT",
                    "requestId 已被不同参数的领用占用");
        }
        if (bottle.openedAt() == null) {
            throw BizException.unprocessable("BOTTLE_UNOPENED", "试剂瓶尚未开封，不能领用");
        }
        if (bottle.status() == BottleStatus.QUARANTINED) {
            throw BizException.unprocessable("BOTTLE_QUARANTINED", "试剂瓶已隔离，不能领用");
        }
        Instant now = Instant.now(clock);
        if (!now.isBefore(bottle.effectiveDeadline())) {
            throw BizException.unprocessable("BOTTLE_EXPIRED", "已到截止时刻，不能领用");
        }
        if (amountMl.compareTo(bottle.remainingMl()) > 0) {
            throw BizException.unprocessable("INSUFFICIENT_REMAINING", "领用量超过剩余量");
        }
        BigDecimal remainingAfter = bottle.remainingMl().subtract(amountMl);
        bottles.updateRemaining(bottleId, remainingAfter);
        long id = dispenses.insert(bottleId, requestId, amountMl, remainingAfter,
                bottle.deadlineBasis(), now);
        return new DispenseOutcome(new DispenseRecord(id, bottleId, requestId, amountMl,
                remainingAfter, bottle.deadlineBasis(), now), false);
    }

    /**
     * 批号召回：隔离该批号下仍有余量的可用瓶；历史成功领用保留，不做负数扣减或倒改。
     */
    @Transactional
    public RecallOutcome recall(String batchNo, String reason) {
        Instant now = Instant.now(clock);
        List<ReagentBottle> targets = bottles.findActiveWithRemainingByBatchForUpdate(batchNo);
        for (ReagentBottle bottle : targets) {
            bottles.quarantine(bottle.id(), reason, now);
        }
        long recallId = recalls.insert(batchNo, reason, targets.size(), now);
        return new RecallOutcome(recallId, batchNo, reason, now,
                targets.stream().map(ReagentBottle::id).toList());
    }

    /**
     * 按瓶查询：瓶状态、到期来源、剩余量、领用流水与隔离原因。
     */
    @Transactional(readOnly = true)
    public BottleDetail detail(long bottleId) {
        ReagentBottle bottle = bottles.findById(bottleId)
                .orElseThrow(() -> BizException.notFound("试剂瓶不存在: " + bottleId));
        return new BottleDetail(bottle, dispenses.findByBottleId(bottleId));
    }

    /**
     * 瓶详情视图：瓶当前状态加全部成功领用流水。
     */
    public record BottleDetail(ReagentBottle bottle, List<DispenseRecord> dispenses) {
    }

    private ReagentBottle lockBottle(long bottleId) {
        return bottles.findByIdForUpdate(bottleId)
                .orElseThrow(() -> BizException.notFound("试剂瓶不存在: " + bottleId));
    }

    private void requireAmount(BigDecimal amount, String field) {
        if (amount == null || amount.signum() <= 0
                || amount.stripTrailingZeros().scale() > 1) {
            throw BizException.badRequest("INVALID_AMOUNT",
                    field + " 必须为正数且精度不超过 0.1 mL");
        }
    }
}