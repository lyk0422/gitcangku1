package com.example.starter.exposure.suppression;

import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.domain.Suppression;
import com.example.starter.exposure.domain.SuppressionCancelledReservation;
import com.example.starter.exposure.domain.SuppressionPeriod;
import com.example.starter.exposure.domain.SuppressionStatus;
import com.example.starter.exposure.exposure.IdempotentRunner;
import com.example.starter.exposure.repo.LedgerRepository;
import com.example.starter.exposure.repo.ReservationRepository;
import com.example.starter.exposure.repo.SuppressionCancelledReservationRepository;
import com.example.starter.exposure.repo.SuppressionPeriodRepository;
import com.example.starter.exposure.repo.SuppressionRepository;
import com.example.starter.exposure.repo.VisitorLockRepository;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.CancelledReservationView;
import com.example.starter.exposure.web.ConfirmedReservationView;
import com.example.starter.exposure.web.SuppressionJoinRequest;
import com.example.starter.exposure.web.SuppressionPeriodView;
import com.example.starter.exposure.web.SuppressionReleaseRequest;
import com.example.starter.exposure.web.SuppressionResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 访客受众抑制名单业务服务实现。
 *
 * <p>全局加锁顺序与曝光服务一致：访客锁 -&gt; 预占单行 -&gt; 总额账行 -&gt; 访客额账行。
 * 加入名单在持访客锁后行锁该访客全部 RESERVED 预占并逐个 CAS 取消，
 * 仅 CAS 成功者按有序去重的账目键释放两级额度，与“确认”竞争时二者必居其一。</p>
 */
@Service
public class SuppressionServiceImpl implements SuppressionService {

    static final String OP_SUPPRESSION_JOIN = "SUPPRESSION_JOIN";
    static final String OP_SUPPRESSION_RELEASE = "SUPPRESSION_RELEASE";

    private final Clock clock;
    private final SuppressionRepository suppressionRepository;
    private final SuppressionPeriodRepository suppressionPeriodRepository;
    private final SuppressionCancelledReservationRepository cancelledReservationRepository;
    private final ReservationRepository reservationRepository;
    private final LedgerRepository ledgerRepository;
    private final VisitorLockRepository visitorLockRepository;
    private final IdempotentRunner idempotentRunner;
    private final TransactionTemplate txTemplate;

    public SuppressionServiceImpl(Clock clock,
                                  SuppressionRepository suppressionRepository,
                                  SuppressionPeriodRepository suppressionPeriodRepository,
                                  SuppressionCancelledReservationRepository cancelledReservationRepository,
                                  ReservationRepository reservationRepository,
                                  LedgerRepository ledgerRepository,
                                  VisitorLockRepository visitorLockRepository,
                                  IdempotentRunner idempotentRunner,
                                  TransactionTemplate txTemplate) {
        this.clock = clock;
        this.suppressionRepository = suppressionRepository;
        this.suppressionPeriodRepository = suppressionPeriodRepository;
        this.cancelledReservationRepository = cancelledReservationRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.visitorLockRepository = visitorLockRepository;
        this.idempotentRunner = idempotentRunner;
        this.txTemplate = txTemplate;
    }

    @Override
    public SuppressionResponse join(SuppressionJoinRequest request) {
        String visitorId = request.visitorId();
        return idempotentRunner.run(request.requestId(), OP_SUPPRESSION_JOIN, visitorId,
                SuppressionResponse.class, () -> {
                    long now = clock.millis();
                    // 先取访客互斥锁：与曝光申请/确认/取消及解除操作串行化
                    visitorLockRepository.lockAll(List.of(visitorId));
                    Suppression current = suppressionRepository.lockById(visitorId).orElse(null);
                    if (current != null && current.status() == SuppressionStatus.ACTIVE) {
                        // 重复加入：按当前状态返回稳定结果，不重复取消、不重复释放
                        return assemble(visitorId, current, now);
                    }

                    // 行锁该访客全部未确认（RESERVED，含已到期未结算）预占，逐个 CAS 取消
                    List<Reservation> reserved = reservationRepository.lockReservedByVisitor(visitorId);
                    List<Reservation> cancelled = new ArrayList<>();
                    for (Reservation reservation : reserved) {
                        boolean won = reservationRepository.compareAndSetStatus(
                                reservation.reservationId(),
                                ReservationStatus.RESERVED,
                                ReservationStatus.CANCELLED,
                                now);
                        if (won) {
                            cancelled.add(reservation);
                        }
                    }
                    // 固定顺序释放两级额度：总额账（有序去重）-> 访客额账（有序去重）
                    releaseLedgerFor(cancelled);

                    String suppressionId = UUID.randomUUID().toString().replace("-", "");
                    int cancelledCount = cancelled.size();
                    SuppressionPeriod period = new SuppressionPeriod(
                            suppressionId,
                            visitorId,
                            now,
                            null,
                            cancelledCount,
                            cancelledCount,
                            request.requestId(),
                            null,
                            now);
                    suppressionPeriodRepository.insert(period);

                    List<SuppressionCancelledReservation> auditItems = new ArrayList<>();
                    for (Reservation reservation : cancelled) {
                        auditItems.add(new SuppressionCancelledReservation(
                                suppressionId,
                                reservation.reservationId(),
                                reservation.campaignId(),
                                reservation.utcDate().toLocalDate(),
                                1,
                                now));
                    }
                    if (!auditItems.isEmpty()) {
                        cancelledReservationRepository.insertBatch(auditItems);
                    }

                    Suppression updated = new Suppression(
                            visitorId,
                            SuppressionStatus.ACTIVE,
                            now,
                            null,
                            request.requestId(),
                            cancelledCount,
                            cancelledCount,
                            now);
                    if (current == null) {
                        suppressionRepository.insert(updated);
                    } else {
                        // 解除后再次加入：以新周期重新激活
                        suppressionRepository.reactivate(updated);
                    }
                    return assemble(visitorId, updated, now);
                });
    }

    @Override
    public SuppressionResponse release(String visitorId, SuppressionReleaseRequest request) {
        return idempotentRunner.run(request.requestId(), OP_SUPPRESSION_RELEASE, visitorId,
                SuppressionResponse.class, () -> {
                    long now = clock.millis();
                    visitorLockRepository.lockAll(List.of(visitorId));
                    Suppression current = suppressionRepository.lockById(visitorId)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "suppression not found for visitor: " + visitorId));
                    if (current.status() == SuppressionStatus.RELEASED) {
                        // 重复解除：按当前状态返回稳定结果，不影响已取消预占
                        return assemble(visitorId, current, now);
                    }

                    SuppressionPeriod activePeriod = suppressionPeriodRepository.lockActiveByVisitor(visitorId)
                            .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                                    "active suppression period missing for visitor: " + visitorId));
                    suppressionPeriodRepository.close(activePeriod.suppressionId(), now, request.requestId());
                    suppressionRepository.markReleased(visitorId, now, request.requestId(), now);

                    Suppression released = new Suppression(
                            visitorId,
                            SuppressionStatus.RELEASED,
                            current.effectiveFromUtc(),
                            now,
                            request.requestId(),
                            current.cancelledCount(),
                            current.releasedQuota(),
                            now);
                    return assemble(visitorId, released, now);
                });
    }

    @Override
    public SuppressionResponse getSuppression(String visitorId) {
        return txTemplate.execute(status -> {
            visitorLockRepository.lockAll(List.of(visitorId));
            Suppression current = suppressionRepository.lockById(visitorId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "suppression not found for visitor: " + visitorId));
            return assemble(visitorId, current, clock.millis());
        });
    }

    // ---- 内部辅助（作用域末尾） ----

    /**
     * 组装查询视图：全部抑制周期（含每笔取消明细）与历史已确认曝光。
     * 调用方须处于事务内并持有访客锁。
     */
    private SuppressionResponse assemble(String visitorId, Suppression current, long now) {
        List<SuppressionPeriod> periods = suppressionPeriodRepository.listByVisitor(visitorId);
        List<SuppressionPeriodView> periodViews = new ArrayList<>();
        String latestSuppressionId = null;
        for (SuppressionPeriod period : periods) {
            if (latestSuppressionId == null) {
                latestSuppressionId = period.suppressionId();
            }
            List<CancelledReservationView> itemViews = cancelledReservationRepository
                    .listBySuppression(period.suppressionId())
                    .stream()
                    .map(CancelledReservationView::from)
                    .toList();
            periodViews.add(SuppressionPeriodView.from(period, itemViews));
        }

        List<ConfirmedReservationView> confirmedViews = reservationRepository
                .findConfirmedByVisitor(visitorId)
                .stream()
                .map(ConfirmedReservationView::from)
                .toList();

        return new SuppressionResponse(
                visitorId,
                current.status(),
                latestSuppressionId,
                current.effectiveFromUtc(),
                current.effectiveToUtc(),
                current.cancelledCount(),
                current.releasedQuota(),
                periodViews,
                confirmedViews,
                now);
    }

    /**
     * 按固定锁序释放一批被取消预占的两级额度：同一账目行上的多笔预占聚合为一次
     * 减去合计笔数（同一访客同一天两笔预占须返还 2 次）。
     * 总额账键（公告+UTC日）、访客额账键（公告+访客+UTC日）均有序处理；CHECK 约束保证不变负。
     */
    private void releaseLedgerFor(List<Reservation> reservations) {
        TreeMap<TotalKey, Integer> totalDeltas = new TreeMap<>();
        TreeMap<VisitorKey, Integer> visitorDeltas = new TreeMap<>();
        for (Reservation r : reservations) {
            LocalDate day = r.utcDate().toLocalDate();
            totalDeltas.merge(new TotalKey(r.campaignId(), day), 1, Integer::sum);
            visitorDeltas.merge(new VisitorKey(r.campaignId(), r.visitorId(), day), 1, Integer::sum);
        }
        for (Map.Entry<TotalKey, Integer> entry : totalDeltas.entrySet()) {
            TotalKey key = entry.getKey();
            ledgerRepository.releaseTotal(key.campaignId(), key.utcDate(), entry.getValue());
        }
        for (Map.Entry<VisitorKey, Integer> entry : visitorDeltas.entrySet()) {
            VisitorKey key = entry.getKey();
            ledgerRepository.releaseVisitor(key.campaignId(), key.visitorId(), key.utcDate(), entry.getValue());
        }
    }

    /** 总额账行键，可按 公告 + UTC 日 排序。 */
    private record TotalKey(String campaignId, LocalDate utcDate)
            implements Comparable<TotalKey> {
        @Override
        public int compareTo(TotalKey other) {
            int byCampaign = campaignId.compareTo(other.campaignId);
            return byCampaign != 0 ? byCampaign : utcDate.compareTo(other.utcDate);
        }
    }

    /** 访客额账行键，可按 公告 + 访客 + UTC 日 排序。 */
    private record VisitorKey(String campaignId, String visitorId, LocalDate utcDate)
            implements Comparable<VisitorKey> {
        @Override
        public int compareTo(VisitorKey other) {
            int byCampaign = campaignId.compareTo(other.campaignId);
            if (byCampaign != 0) {
                return byCampaign;
            }
            int byVisitor = visitorId.compareTo(other.visitorId);
            return byVisitor != 0 ? byVisitor : utcDate.compareTo(other.utcDate);
        }
    }
}
