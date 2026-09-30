package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.domain.Suppression;
import com.example.starter.exposure.domain.SuppressionStatus;
import com.example.starter.exposure.repo.CampaignRepository;
import com.example.starter.exposure.repo.LedgerRepository;
import com.example.starter.exposure.repo.ReservationRepository;
import com.example.starter.exposure.repo.SuppressionRepository;
import com.example.starter.exposure.repo.VisitorLockRepository;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 公告曝光频控业务服务实现。
 *
 * <p>所有写操作以 requestId 为全局幂等键：同键同参重放原成功结果，异参 409；
 * 业务失败随事务回滚，不占幂等键。所有操作与额度查询先结算相关过期预占，
 * 不依赖后台定时器。终态竞争由访客互斥锁 + 行锁 + 状态 CAS 保证只允许一个终态。</p>
 *
 * <p>访客抑制名单优先于公告与展示位的可投放规则：抑制生效期间该访客的新申请
 * 直接 409 且不占额度。全局加锁顺序为 访客锁（visitorId 字典序）-&gt;
 * 预占单行（reservationId 字典序）-&gt; 总额账行 -&gt; 访客额账行，避免死锁。</p>
 */
@Service
public class ExposureServiceImpl implements ExposureService {

    static final int RESERVATION_TTL_MILLIS = 60_000;

    static final String OP_CREATE_CAMPAIGN = "CREATE_CAMPAIGN";
    static final String OP_APPLY = "APPLY";
    static final String OP_CONFIRM = "CONFIRM";
    static final String OP_CANCEL = "CANCEL";

    private final Clock clock;
    private final CampaignRepository campaignRepository;
    private final ReservationRepository reservationRepository;
    private final LedgerRepository ledgerRepository;
    private final SuppressionRepository suppressionRepository;
    private final VisitorLockRepository visitorLockRepository;
    private final IdempotentRunner idempotentRunner;
    private final TransactionTemplate txTemplate;

    public ExposureServiceImpl(Clock clock,
                               CampaignRepository campaignRepository,
                               ReservationRepository reservationRepository,
                               LedgerRepository ledgerRepository,
                               SuppressionRepository suppressionRepository,
                               VisitorLockRepository visitorLockRepository,
                               IdempotentRunner idempotentRunner,
                               TransactionTemplate txTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.suppressionRepository = suppressionRepository;
        this.visitorLockRepository = visitorLockRepository;
        this.idempotentRunner = idempotentRunner;
        this.txTemplate = txTemplate;
    }

    @Override
    public CampaignResponse createCampaign(CreateCampaignRequest request) {
        String fingerprint = request.campaignId() + "|" + request.dailyTotalCap() + "|"
                + request.perVisitorDailyCap();
        return idempotentRunner.run(request.requestId(), OP_CREATE_CAMPAIGN, fingerprint,
                CampaignResponse.class, () -> {
                    if (campaignRepository.findById(request.campaignId()).isPresent()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "campaign already exists: " + request.campaignId());
                    }
                    Campaign campaign = new Campaign(
                            request.campaignId(),
                            request.dailyTotalCap(),
                            request.perVisitorDailyCap(),
                            clock.millis());
                    try {
                        campaignRepository.insert(campaign);
                    } catch (DuplicateKeyException duplicateCampaign) {
                        // 并发创建同一 campaignId：明确返回 409，而非误报幂等键冲突
                        throw new ApiException(HttpStatus.CONFLICT,
                                "campaign already exists: " + request.campaignId());
                    }
                    return CampaignResponse.from(campaign);
                });
    }

    @Override
    public ReservationResponse apply(ApplyExposureRequest request) {
        String fingerprint = request.campaignId() + "|" + request.visitorId();
        return idempotentRunner.run(request.requestId(), OP_APPLY, fingerprint,
                ReservationResponse.class, () -> {
                    long now = clock.millis();
                    LocalDate utcDate = LocalDate.now(clock);
                    Campaign campaign = requireCampaign(request.campaignId());

                    // 先取访客互斥锁：与名单变更、确认/取消竞争串行化
                    TreeSet<String> lockedVisitors = new TreeSet<>();
                    lockedVisitors.add(request.visitorId());
                    // 预读到期候选以收集需加锁的其他访客（候选集只减不增），随后统一按序加锁
                    lockedVisitors.addAll(visitorIdsOf(
                            reservationRepository.findExpiredReserved(campaign.campaignId(), now)));
                    visitorLockRepository.lockAll(lockedVisitors);

                    // 抑制优先：名单生效中的访客一律拒绝，且不占任何额度
                    if (isSuppressed(request.visitorId())) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "visitor is on the suppression list: " + request.visitorId());
                    }

                    // 持访客锁后重新加锁读取并结算该公告相关过期预占，释放两级额度
                    settleExpired(campaign.campaignId(), now);

                    // 总额账行 -> 访客额账行（与全服务锁序一致）
                    ledgerRepository.ensureTotalRow(campaign.campaignId(), utcDate);
                    ledgerRepository.ensureVisitorRow(campaign.campaignId(), request.visitorId(), utcDate);
                    int usedTotal = ledgerRepository.lockUsedTotal(campaign.campaignId(), utcDate);
                    int usedVisitor = ledgerRepository.lockUsedVisitor(
                            campaign.campaignId(), request.visitorId(), utcDate);

                    // 任一额度已满则 429，两个额度均不增加（尚未写入）
                    if (usedTotal + 1 > campaign.dailyTotalCap()
                            || usedVisitor + 1 > campaign.perVisitorDailyCap()) {
                        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                                "exposure quota exhausted for campaign " + campaign.campaignId());
                    }
                    ledgerRepository.addTotal(campaign.campaignId(), utcDate, 1);
                    ledgerRepository.addVisitor(campaign.campaignId(), request.visitorId(), utcDate, 1);

                    String reservationId = UUID.randomUUID().toString().replace("-", "");
                    Reservation reservation = new Reservation(
                            reservationId,
                            campaign.campaignId(),
                            request.visitorId(),
                            java.sql.Date.valueOf(utcDate),
                            ReservationStatus.RESERVED,
                            now,
                            now + RESERVATION_TTL_MILLIS,
                            null);
                    reservationRepository.insert(reservation);
                    return ReservationResponse.from(reservation);
                });
    }

    @Override
    public ReservationResponse confirm(String reservationId, ReservationActionRequest request) {
        return idempotentRunner.run(request.requestId(), OP_CONFIRM, reservationId,
                ReservationResponse.class, () -> transition(reservationId, true));
    }

    @Override
    public ReservationResponse cancel(String reservationId, ReservationActionRequest request) {
        return idempotentRunner.run(request.requestId(), OP_CANCEL, reservationId,
                ReservationResponse.class, () -> transition(reservationId, false));
    }

    @Override
    public ReservationResponse getReservation(String reservationId) {
        return txTemplate.execute(status -> {
            Reservation preloaded = requireReservation(reservationId);
            visitorLockRepository.lockAll(List.of(preloaded.visitorId()));
            Reservation locked = reservationRepository.lockById(reservationId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "reservation not found: " + reservationId));
            expireIfDue(List.of(locked), clock.millis());
            return ReservationResponse.from(
                    reservationRepository.lockById(reservationId).orElseThrow());
        });
    }

    @Override
    public QuotaResponse queryQuota(String campaignId, String visitorId, LocalDate requestedDate) {
        return txTemplate.execute(status -> {
            Campaign campaign = requireCampaign(campaignId);
            long now = clock.millis();
            LocalDate utcDate = requestedDate != null ? requestedDate : LocalDate.now(clock);

            TreeSet<String> lockedVisitors = new TreeSet<>(
                    visitorIdsOf(reservationRepository.findExpiredReserved(campaignId, now)));
            if (visitorId != null && !visitorId.isBlank()) {
                lockedVisitors.add(visitorId);
            }
            if (!lockedVisitors.isEmpty()) {
                visitorLockRepository.lockAll(lockedVisitors);
            }

            settleExpired(campaignId, now);

            int usedTotal = ledgerRepository.getUsedTotal(campaignId, utcDate);
            if (visitorId == null || visitorId.isBlank()) {
                return new QuotaResponse(
                        campaignId, null, utcDate,
                        campaign.dailyTotalCap(), usedTotal,
                        campaign.dailyTotalCap() - usedTotal,
                        null, null, null, now);
            }
            int usedVisitor = ledgerRepository.getUsedVisitor(campaignId, visitorId, utcDate);
            return new QuotaResponse(
                    campaignId, visitorId, utcDate,
                    campaign.dailyTotalCap(), usedTotal,
                    campaign.dailyTotalCap() - usedTotal,
                    campaign.perVisitorDailyCap(), usedVisitor,
                    campaign.perVisitorDailyCap() - usedVisitor,
                    now);
        });
    }

    // ---- 内部辅助（作用域末尾） ----

    /**
     * 确认/取消状态机。调用前已持幂等键；访客锁 + 行锁 + CAS 保证并发只有一个终态。
     *
     * @param isConfirm true=确认，false=取消
     */
    private ReservationResponse transition(String reservationId, boolean isConfirm) {
        long now = clock.millis();
        Reservation preloaded = requireReservation(reservationId);
        // 先取该访客互斥锁：与加入名单（原子取消）及跨访客结算竞争串行化
        visitorLockRepository.lockAll(List.of(preloaded.visitorId()));

        Reservation current = reservationRepository.lockById(reservationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "reservation not found: " + reservationId));

        // 仅结算本单（已持访客锁与预占行锁）
        expireIfDue(List.of(current), now);
        current = reservationRepository.lockById(reservationId).orElseThrow();

        if (current.status() == ReservationStatus.RESERVED) {
            // 结算后仍为 RESERVED 说明 now < expiresAt，确认严格要求在到期时刻之前
            if (isConfirm) {
                if (!reservationRepository.compareAndSetStatus(
                        reservationId, ReservationStatus.RESERVED, ReservationStatus.CONFIRMED, now)) {
                    throw new ApiException(HttpStatus.CONFLICT, "reservation state changed concurrently");
                }
            } else {
                if (!reservationRepository.compareAndSetStatus(
                        reservationId, ReservationStatus.RESERVED, ReservationStatus.CANCELLED, now)) {
                    throw new ApiException(HttpStatus.CONFLICT, "reservation state changed concurrently");
                }
                // 取消释放两级额度；固定顺序先总账后访客账
                releaseLedgerFor(List.of(current));
            }
        } else if (current.status() == ReservationStatus.CONFIRMED
                || current.status() == ReservationStatus.CANCELLED
                || current.status() == ReservationStatus.EXPIRED) {
            boolean sameTerminal = isConfirm
                    ? current.status() == ReservationStatus.CONFIRMED
                    : current.status() == ReservationStatus.CANCELLED;
            if (!sameTerminal) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "reservation is " + current.status() + ", cannot "
                                + (isConfirm ? "confirm" : "cancel"));
            }
            // 重复同类终态操作：返回原状态，不重复释放额度
        } else {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "unexpected reservation status: " + current.status());
        }

        Reservation result = reservationRepository.lockById(reservationId).orElseThrow();
        return ReservationResponse.from(result);
    }

    /**
     * 结算某公告当前已到期（now &gt;= expiresAt）但仍为 RESERVED 的预占。
     * 调用方须已按字典序持有全部相关访客锁；此处行锁重读（按预占编号有序）后
     * 逐个 CAS 为 EXPIRED，仅 CAS 成功者按固定账目顺序释放两级额度，杜绝重复释放。
     */
    private void settleExpired(String campaignId, long now) {
        List<Reservation> expired = reservationRepository.lockExpiredReserved(campaignId, now);
        expireIfDue(expired, now);
    }

    /**
     * 对调用方已持访客锁的预占单进行到期结算：仅仍为 RESERVED 且到期的单子
     * CAS 转 EXPIRED；CAS 成功者统一按 总额账 -&gt; 访客额账 顺序释放额度。
     */
    private void expireIfDue(List<Reservation> reservations, long now) {
        List<Reservation> won = new ArrayList<>();
        for (Reservation reservation : reservations) {
            if (reservation.status() == ReservationStatus.RESERVED
                    && now >= reservation.expiresAtUtc()
                    && reservationRepository.compareAndSetStatus(
                            reservation.reservationId(),
                            ReservationStatus.RESERVED,
                            ReservationStatus.EXPIRED,
                            now)) {
                won.add(reservation);
            }
        }
        releaseLedgerFor(won);
    }

    /**
     * 按固定锁序释放一批预占占用的两级额度：同一账目行上的多笔预占聚合为一次
     * 减去合计笔数（两笔预占共用同一额度账行时须返还 2 次）。
     * 总额账键（公告+UTC日）、访客额账键（公告+访客+UTC日）均有序处理；CHECK 约束保证不变负。
     */
    private void releaseLedgerFor(List<Reservation> reservations) {
        TreeMap<LedgerTotalKey, Integer> totalDeltas = new TreeMap<>();
        TreeMap<LedgerVisitorKey, Integer> visitorDeltas = new TreeMap<>();
        for (Reservation r : reservations) {
            LocalDate day = r.utcDate().toLocalDate();
            totalDeltas.merge(new LedgerTotalKey(r.campaignId(), day), 1, Integer::sum);
            visitorDeltas.merge(new LedgerVisitorKey(r.campaignId(), r.visitorId(), day), 1, Integer::sum);
        }
        for (Map.Entry<LedgerTotalKey, Integer> entry : totalDeltas.entrySet()) {
            LedgerTotalKey key = entry.getKey();
            ledgerRepository.releaseTotal(key.campaignId(), key.utcDate(), entry.getValue());
        }
        for (Map.Entry<LedgerVisitorKey, Integer> entry : visitorDeltas.entrySet()) {
            LedgerVisitorKey key = entry.getKey();
            ledgerRepository.releaseVisitor(key.campaignId(), key.visitorId(), key.utcDate(), entry.getValue());
        }
    }

    private List<String> visitorIdsOf(List<Reservation> reservations) {
        TreeSet<String> ids = new TreeSet<>();
        for (Reservation reservation : reservations) {
            ids.add(reservation.visitorId());
        }
        return new ArrayList<>(ids);
    }

    private boolean isSuppressed(String visitorId) {
        // 加锁读：即便事务早先已建立读视图，也必须读到名单最新已提交状态
        return suppressionRepository.lockById(visitorId)
                .map(Suppression::status)
                .filter(SuppressionStatus.ACTIVE::equals)
                .isPresent();
    }

    private Campaign requireCampaign(String campaignId) {
        return campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "campaign not found: " + campaignId));
    }

    private Reservation requireReservation(String reservationId) {
        return reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "reservation not found: " + reservationId));
    }

    /** 总额账行键，可按 公告 + UTC 日 排序。 */
    private record LedgerTotalKey(String campaignId, LocalDate utcDate)
            implements Comparable<LedgerTotalKey> {
        @Override
        public int compareTo(LedgerTotalKey other) {
            int byCampaign = campaignId.compareTo(other.campaignId);
            return byCampaign != 0 ? byCampaign : utcDate.compareTo(other.utcDate);
        }
    }

    /** 访客额账行键，可按 公告 + 访客 + UTC 日 排序。 */
    private record LedgerVisitorKey(String campaignId, String visitorId, LocalDate utcDate)
            implements Comparable<LedgerVisitorKey> {
        @Override
        public int compareTo(LedgerVisitorKey other) {
            int byCampaign = campaignId.compareTo(other.campaignId);
            if (byCampaign != 0) {
                return byCampaign;
            }
            int byVisitor = visitorId.compareTo(other.visitorId);
            return byVisitor != 0 ? byVisitor : utcDate.compareTo(other.utcDate);
        }
    }
}
