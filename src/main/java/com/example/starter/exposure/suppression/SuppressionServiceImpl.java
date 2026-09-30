package com.example.starter.exposure.suppression;

import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.domain.Suppression;
import com.example.starter.exposure.domain.SuppressionCancellation;
import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.exposure.repo.LedgerRepository;
import com.example.starter.exposure.repo.ReservationRepository;
import com.example.starter.exposure.repo.SuppressionRepository;
import com.example.starter.exposure.repo.SuppressionRepository.QuotaChangeRow;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.SuppressionActionRequest;
import com.example.starter.exposure.web.SuppressionCancellationItem;
import com.example.starter.exposure.web.SuppressionQuotaChange;
import com.example.starter.exposure.web.SuppressionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

/**
 * 访客受众抑制名单业务服务实现。
 *
 * <p>名单变更以 requestId 为全局幂等键（与曝光预占共用幂等表）：同键同访客同动作
 * 重放首次成功结果，换访客或动作返回 409；业务失败随事务回滚，不占幂等键。</p>
 *
 * <p>加入名单在单事务内完成：行锁激活访客状态 → 行锁查出其全部 RESERVED 预占 →
 * 逐笔 CAS 为 CANCELLED 并按先总账后访客账顺序释放额度。与曝光确认竞争时，
 * 由预占单行锁 + 状态 CAS 保证一笔预占要么 CONFIRMED 占额、要么 CANCELLED 释放，
 * 不会既计入确认又被取消。CONFIRMED 历史预占不参与取消，计数不回退。</p>
 */
@Service
public class SuppressionServiceImpl implements SuppressionService {

    /** 并发同键竞争时等待胜出事务提交的最大时长。 */
    private static final long IDEMPOTENT_WAIT_MILLIS = 10_000L;

    private final Clock clock;
    private final SuppressionRepository suppressionRepository;
    private final ReservationRepository reservationRepository;
    private final LedgerRepository ledgerRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public SuppressionServiceImpl(Clock clock,
                                  SuppressionRepository suppressionRepository,
                                  ReservationRepository reservationRepository,
                                  LedgerRepository ledgerRepository,
                                  IdempotencyRepository idempotencyRepository,
                                  ObjectMapper objectMapper,
                                  TransactionTemplate txTemplate) {
        this.clock = clock;
        this.suppressionRepository = suppressionRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
    }

    @Override
    public SuppressionResponse suppress(String visitorId, SuppressionActionRequest request) {
        return runIdempotent(request.requestId(), Operation.SUPPRESS, visitorId, () -> {
            long now = clock.millis();
            suppressionRepository.ensureVisitorRow(visitorId);
            Suppression state = suppressionRepository.lockByVisitor(visitorId).orElseThrow();

            // 已在名单：重复加入按当前状态稳定返回，不重复取消、不重复释放
            if (!suppressionRepository.activateIfInactive(visitorId, now)) {
                return buildResponse(suppressionRepository.lockByVisitor(visitorId).orElseThrow());
            }

            // 行锁查出全部未确认预占；与确认竞争时在此串行，快照仅含 RESERVED
            List<Reservation> reserved = reservationRepository.lockReservedByVisitor(visitorId);
            int cancelled = 0;
            for (Reservation reservation : reserved) {
                // CAS 保证只取消仍为 RESERVED 的预占；每笔先总账后访客账释放，各一次
                boolean won = reservationRepository.compareAndSetStatus(
                        reservation.reservationId(),
                        ReservationStatus.RESERVED,
                        ReservationStatus.CANCELLED,
                        now);
                if (!won) {
                    continue;
                }
                LocalDate utcDate = reservation.utcDate().toLocalDate();
                ledgerRepository.releaseTotal(reservation.campaignId(), utcDate);
                ledgerRepository.releaseVisitor(reservation.campaignId(), visitorId, utcDate);
                suppressionRepository.insertCancellation(new SuppressionCancellation(
                        reservation.reservationId(),
                        reservation.campaignId(),
                        visitorId,
                        reservation.utcDate(),
                        now));
                cancelled++;
            }
            suppressionRepository.updateCancellationStats(visitorId, cancelled, cancelled);
            return buildResponse(suppressionRepository.lockByVisitor(visitorId).orElseThrow());
        });
    }

    @Override
    public SuppressionResponse release(String visitorId, SuppressionActionRequest request) {
        return runIdempotent(request.requestId(), Operation.RELEASE, visitorId, () -> {
            long now = clock.millis();
            suppressionRepository.ensureVisitorRow(visitorId);
            suppressionRepository.lockByVisitor(visitorId).orElseThrow();

            // 条件解除：当前不在名单（含重复解除）时按当前状态稳定返回，不恢复已取消预占
            suppressionRepository.deactivateIfActive(visitorId, now);
            return buildResponse(suppressionRepository.lockByVisitor(visitorId).orElseThrow());
        });
    }

    @Override
    public SuppressionResponse getSuppression(String visitorId) {
        return txTemplate.execute(status -> {
            Suppression state = suppressionRepository.findByVisitor(visitorId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "suppression record not found for visitor: " + visitorId));
            return buildResponse(state);
        });
    }

    // ---- 内部辅助（作用域末尾） ----

    /** 幂等操作类型，复用曝光预占幂等表。 */
    private enum Operation {
        SUPPRESS,
        RELEASE
    }

    /**
     * 在事务内执行业务并维护幂等记录；业务变更与去重结果原子提交。
     * 并发同键插入冲突时等待胜出事务提交后重放其结果。
     */
    private SuppressionResponse runIdempotent(String requestId, Operation operation, String visitorId,
                                              Supplier<SuppressionResponse> action) {
        String fingerprint = visitorId;
        long deadline = System.currentTimeMillis() + IDEMPOTENT_WAIT_MILLIS;
        while (true) {
            try {
                return txTemplate.execute(status -> {
                    var existing = idempotencyRepository.lockById(requestId);
                    if (existing.isPresent()) {
                        IdempotencyRecord record = existing.get();
                        if (!record.operation().equals(operation.name())
                                || !record.requestFingerprint().equals(fingerprint)) {
                            throw new ApiException(HttpStatus.CONFLICT,
                                    "idempotency key reused with different parameters: " + requestId);
                        }
                        try {
                            return objectMapper.readValue(record.responseJson(), SuppressionResponse.class);
                        } catch (Exception e) {
                            throw new IllegalStateException("failed to replay idempotent response", e);
                        }
                    }
                    SuppressionResponse result = action.get();
                    idempotencyRepository.insert(new IdempotencyRecord(
                            requestId, operation.name(), fingerprint, writeJson(result)), clock.millis());
                    return result;
                });
            } catch (DuplicateKeyException duplicate) {
                // 同键并发事务抢先插入（可能尚未提交），等待后重放
                if (System.currentTimeMillis() >= deadline) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "concurrent idempotency key conflict: " + requestId);
                }
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "interrupted");
                }
            }
        }
    }

    /** 组装状态视图：附带最近一批次取消明细与按公告/UTC 日聚合的额度变化。 */
    private SuppressionResponse buildResponse(Suppression state) {
        List<SuppressionCancellationItem> cancellations = List.of();
        List<SuppressionQuotaChange> quotaChanges = List.of();
        if (state.suppressedAtUtc() != null) {
            cancellations = suppressionRepository
                    .findCancellations(state.visitorId(), state.suppressedAtUtc())
                    .stream()
                    .map(c -> new SuppressionCancellationItem(
                            c.reservationId(), c.campaignId(),
                            c.utcDate().toLocalDate(), c.suppressedAtUtc()))
                    .toList();
            quotaChanges = suppressionRepository
                    .aggregateQuotaChanges(state.visitorId(), state.suppressedAtUtc())
                    .stream()
                    .map((QuotaChangeRow row) -> new SuppressionQuotaChange(
                            row.campaignId(), row.utcDate(), row.count(), row.count()))
                    .toList();
        }
        return new SuppressionResponse(
                state.visitorId(),
                state.active(),
                state.suppressedAtUtc(),
                state.releasedAtUtc(),
                state.cancelledCount(),
                state.quotaReleased(),
                cancellations,
                quotaChanges);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }
}
