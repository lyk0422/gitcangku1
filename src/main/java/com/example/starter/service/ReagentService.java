package com.example.starter.service;

import com.example.starter.repo.ReagentRepository;
import com.example.starter.repo.ReagentRepository.BottleRow;
import com.example.starter.repo.ReagentRepository.DispenseRow;
import com.example.starter.web.dto.ApiDtos.BottleDetailView;
import com.example.starter.web.dto.ApiDtos.BottleView;
import com.example.starter.web.dto.ApiDtos.DispenseView;
import com.example.starter.web.dto.ApiDtos.RecallView;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 试剂接收、开封、领用、召回与查询的业务逻辑。 */
@Service
public class ReagentService {

    /** 开封后使用期限天数。 */
    public static final long OPEN_VALIDITY_DAYS = 30;

    private static final String SOURCE_SUPPLIER = "SUPPLIER_EXPIRY";
    private static final String SOURCE_OPEN = "OPEN_EXPIRY_30D";

    private final ReagentRepository repo;
    private final Clock clock;

    public ReagentService(ReagentRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /** 接收登记：记录批号、容量与供应商到期 UTC 时刻，初始剩余量等于容量。 */
    @Transactional
    public BottleView receive(String lotNumber, BigDecimal capacityMl, Instant supplierExpiryUtc) {
        validateAmount(capacityMl, "容量");
        Instant now = Instant.now(clock);
        long id = repo.insertBottle(lotNumber, capacityMl, supplierExpiryUtc, now);
        repo.insertEvent(id, "RECEIVE", now, "接收登记");
        return toView(repo.findBottle(id).orElseThrow());
    }

    /** 首次开封：固定开封时刻，实际截止取供应商到期与开封 30 天中较早者；重复开封返回现状。 */
    @Transactional
    public BottleView open(long bottleId) {
        BottleRow bottle = repo.findBottleForUpdate(bottleId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "试剂瓶不存在"));
        if (bottle.openedAtUtc() != null) {
            return toView(bottle);
        }
        Instant now = Instant.now(clock);
        Instant openDeadline = now.plus(OPEN_VALIDITY_DAYS, ChronoUnit.DAYS);
        boolean openEarlier = openDeadline.isBefore(bottle.supplierExpiryUtc());
        Instant effective = openEarlier ? openDeadline : bottle.supplierExpiryUtc();
        String source = openEarlier ? SOURCE_OPEN : SOURCE_SUPPLIER;
        repo.markOpened(bottleId, now, effective, source);
        repo.insertEvent(bottleId, "OPEN", now, "首次开封");
        return toView(repo.findBottle(bottleId).orElseThrow());
    }

    /**
     * 领用：0.1 mL 精度；仅在截止时刻之前（左闭右开）且未隔离、已开封时扣减。
     * requestId 同参重放首次记录，异参返回 409；失败请求不占键。
     */
    @Transactional
    public DispenseView dispense(long bottleId, String requestId, BigDecimal amountMl) {
        validateAmount(amountMl, "领用量");
        var existing = repo.findDispenseByRequestId(requestId);
        if (existing.isPresent()) {
            DispenseRow prior = existing.get();
            if (prior.bottleId() == bottleId && prior.amountMl().compareTo(amountMl) == 0) {
                return toView(prior);
            }
            throw new ApiException(HttpStatus.CONFLICT, "requestId 已以不同参数使用");
        }
        BottleRow bottle = repo.findBottleForUpdate(bottleId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "试剂瓶不存在"));
        if (bottle.openedAtUtc() == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "试剂未开封，不能领用");
        }
        if (bottle.quarantined()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "试剂已隔离，不能领用");
        }
        Instant now = Instant.now(clock);
        if (!now.isBefore(bottle.effectiveDeadlineUtc())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "已到达或超过实际截止时刻，不能领用");
        }
        if (amountMl.compareTo(bottle.remainingMl()) > 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "领用量超过剩余量");
        }
        BigDecimal remaining = bottle.remainingMl().subtract(amountMl);
        repo.updateRemaining(bottleId, remaining);
        try {
            long id = repo.insertDispense(bottleId, requestId, amountMl, remaining,
                    bottle.deadlineSource(), now);
            return toView(repo.findDispenseByRequestId(requestId).orElseThrow());
        } catch (DuplicateKeyException e) {
            // 并发下同 requestId 已提交：按已固化记录重放或报异参冲突
            DispenseRow prior = repo.findDispenseByRequestId(requestId).orElseThrow(() -> e);
            if (prior.bottleId() == bottleId && prior.amountMl().compareTo(amountMl) == 0) {
                return toView(prior);
            }
            throw new ApiException(HttpStatus.CONFLICT, "requestId 已以不同参数使用");
        }
    }

    /** 批号召回：隔离该批号下尚有余量且未隔离的瓶，历史成功领用保留。 */
    @Transactional
    public RecallView recall(String lotNumber, String reason) {
        var bottles = repo.findBottlesByLotForUpdate(lotNumber);
        if (bottles.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "批号不存在");
        }
        Instant now = Instant.now(clock);
        var ids = new ArrayList<Long>();
        for (BottleRow bottle : bottles) {
            if (!bottle.quarantined() && bottle.remainingMl().signum() > 0) {
                repo.markQuarantined(bottle.id(), reason, now);
                repo.insertEvent(bottle.id(), "QUARANTINE", now, reason);
                ids.add(bottle.id());
            }
        }
        return new RecallView(lotNumber, ids.size(), ids);
    }

    /** 按瓶查询：到期来源、剩余量、领用流水与隔离原因。 */
    @Transactional(readOnly = true)
    public BottleDetailView detail(long bottleId) {
        BottleRow bottle = repo.findBottle(bottleId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "试剂瓶不存在"));
        var dispenses = repo.findDispensesByBottle(bottleId).stream().map(this::toView).toList();
        return new BottleDetailView(toView(bottle), dispenses);
    }

    private void validateAmount(BigDecimal amount, String label) {
        if (amount == null || amount.signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, label + "必须为正数");
        }
        if (amount.stripTrailingZeros().scale() > 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, label + "精度须为 0.1 mL");
        }
    }

    private BottleView toView(BottleRow b) {
        return new BottleView(b.id(), b.lotNumber(), b.capacityMl(), b.remainingMl(), b.supplierExpiryUtc(),
                b.openedAtUtc(), b.effectiveDeadlineUtc(), b.deadlineSource(), b.quarantined(),
                b.quarantineReason());
    }

    private DispenseView toView(DispenseRow d) {
        return new DispenseView(d.id(), d.bottleId(), d.requestId(), d.amountMl(), d.remainingAfterMl(),
                d.deadlineSource(), d.dispensedAtUtc());
    }
}