package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.domain.SuppressionInterval;
import com.example.starter.exposure.repo.CampaignRepository;
import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.exposure.repo.LedgerRepository;
import com.example.starter.exposure.repo.ReservationRepository;
import com.example.starter.exposure.repo.SuppressionRepository;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.ExposureDecisionResponse;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SuppressionReasonResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 公告曝光频控业务服务实现。
 *
 * <p>所有写操作以 requestId 为全局幂等键：同键同参重放原成功结果，异参 409；
 * 业务失败随事务回滚，不占幂等键。所有操作与额度查询先结算相关过期预占，
 * 不依赖后台定时器。终态竞争由行锁 + 状态 CAS 保证只允许一个终态。</p>
 *
 * <p>曝光申请先在同一事务内对公告行加锁（与名单变更共享串行化锚点，按事务提交顺序
 * 裁决），再判定访客抑制名单：命中抑制区间返回 SUPPRESSED，不创建预占、不扣频次或
 * 预算；预占创建之后新增的抑制不回滚已存在预占，其回执仍按既有规则结算。</p>
 */
@Service
public class ExposureServiceImpl implements ExposureService {

    static final int RESERVATION_TTL_MILLIS = 60_000;

    /** 并发同键竞争时等待胜出事务提交的最大时长。 */
    private static final long IDEMPOTENT_WAIT_MILLIS = 10_000L;

    private final Clock clock;
    private final CampaignRepository campaignRepository;
    private final ReservationRepository reservationRepository;
    private final LedgerRepository ledgerRepository;
    private final SuppressionRepository suppressionRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public ExposureServiceImpl(Clock clock,
                               CampaignRepository campaignRepository,
                               ReservationRepository reservationRepository,
                               LedgerRepository ledgerRepository,
                               SuppressionRepository suppressionRepository,
                               IdempotencyRepository idempotencyRepository,
                               ObjectMapper objectMapper,
                               TransactionTemplate txTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.suppressionRepository = suppressionRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
    }

    @Override
    public CampaignResponse createCampaign(CreateCampaignRequest request) {
        String fingerprint = request.campaignId() + "|" + request.dailyTotalCap() + "|"
                + request.perVisitorDailyCap();
        return runIdempotent(request.requestId(), Operation.CREATE_CAMPAIGN, fingerprint,
                CampaignResponse.class, () -> {
                    if (campaignRepository.findById(request.campaignId()).isPresent()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "campaign already exists: " + request.campaignId());
                    }
                    Campaign campaign = new Campaign(
                            request.campaignId(),
                            request.dailyTotalCap(),
                            request.perVisitorDailyCap(),
                            0,
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
    public ExposureDecisionResponse decide(ApplyExposureRequest request) {
        // 指纹依赖事务内锁定的公告版本，故在锁公告行后动态计算
        return runIdempotentWithDynamicFingerprint(
                request.requestId(), Operation.APPLY, request.campaignId(),
                ExposureDecisionResponse.class,
                campaign -> applyFingerprint(request, campaign),
                campaign -> doDecide(request, campaign));
    }

    @Override
    public ReservationResponse apply(ApplyExposureRequest request) {
        ExposureDecisionResponse decision = decide(request);
        if (decision.outcome().equals(ExposureDecisionResponse.OUTCOME_SUPPRESSED)) {
            // 兼容旧入口：旧契约下不存在 SUPPRESSED，明确拒绝而非静默返回空
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "exposure is SUPPRESSED for visitor " + request.visitorId()
                            + "; use the decision endpoint for full result");
        }
        return decision.reservation();
    }

    @Override
    public ReservationResponse confirm(String reservationId, ReservationActionRequest request) {
        String fingerprint = reservationId;
        return runIdempotent(request.requestId(), Operation.CONFIRM, fingerprint,
                ReservationResponse.class, () -> transition(reservationId, true));
    }

    @Override
    public ReservationResponse cancel(String reservationId, ReservationActionRequest request) {
        String fingerprint = reservationId;
        return runIdempotent(request.requestId(), Operation.CANCEL, fingerprint,
                ReservationResponse.class, () -> transition(reservationId, false));
    }

    @Override
    public ReservationResponse getReservation(String reservationId) {
        return txTemplate.execute(status -> {
            Reservation locked = reservationRepository.lockById(reservationId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "reservation not found: " + reservationId));
            expireIfDue(locked, clock.millis());
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

    /** 幂等操作类型，同时标识存储响应的反序列化类型。 */
    private enum Operation {
        CREATE_CAMPAIGN,
        APPLY,
        CONFIRM,
        CANCEL
    }

    /**
     * 曝光申请联合裁决事务体。调用前已持幂等键并已对公告行加锁。
     */
    private ExposureDecisionResponse doDecide(ApplyExposureRequest request, Campaign campaign) {
        long now = request.requestAtUtc() != null ? request.requestAtUtc() : clock.millis();
        LocalDate utcDate = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC)
                .toLocalDate();

        // 先结算该公告相关过期预占并释放额度（预占创建后新增抑制不影响已存在预占）
        settleExpired(campaign.campaignId(), now);

        // 抑制名单前置裁决：命中则任何展示位均返回 SUPPRESSED，不创建预占、不扣频次或预算
        SuppressionInterval hit = suppressionRepository
                .lockActiveHitting(campaign.campaignId(), request.visitorId(), now)
                .orElse(null);
        if (hit != null) {
            String reason = "visitor " + request.visitorId() + " is suppressed by interval "
                    + hit.intervalId() + " of campaign " + campaign.campaignId()
                    + " (UTC half-open [" + hit.startAtUtc() + ", " + hit.endAtUtc()
                    + ")), hit at " + now;
            return ExposureDecisionResponse.suppressed(
                    new SuppressionReasonResponse(hit.intervalId(), request.visitorId(),
                            hit.startAtUtc(), hit.endAtUtc(), reason),
                    campaign.version(), now);
        }

        // 固定加锁顺序：公告行 -> 公告当日总账 -> 访客当日账，避免死锁
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
                normalizedPlacement(request),
                java.sql.Date.valueOf(utcDate),
                ReservationStatus.RESERVED,
                now,
                now + RESERVATION_TTL_MILLIS,
                null);
        reservationRepository.insert(reservation);
        return ExposureDecisionResponse.reserved(
                ReservationResponse.from(reservation), campaign.version(), now);
    }

    /**
     * 构造曝光申请指纹：含公告版本、访客、展示位、请求时刻与全部频控影响字段。
     * requestAtUtc 缺省（服务端取时）以 "server" 标记参与指纹，保证同键重放稳定；
     * 名单变更推进版本后，同键因版本差异判为异参 409。
     */
    private String applyFingerprint(ApplyExposureRequest request, Campaign campaign) {
        String requestTimePart = request.requestAtUtc() != null
                ? String.valueOf(request.requestAtUtc()) : "server";
        return request.campaignId() + "|v" + campaign.version()
                + "|" + request.visitorId()
                + "|" + normalizedPlacement(request)
                + "|t" + requestTimePart
                + "|cap" + campaign.dailyTotalCap() + ":" + campaign.perVisitorDailyCap();
    }

    /** 展示位缺省或空白时归一化为默认展示位。 */
    private String normalizedPlacement(ApplyExposureRequest request) {
        return request.placementId() == null || request.placementId().isBlank()
                ? ApplyExposureRequest.DEFAULT_PLACEMENT
                : request.placementId();
    }

    /**
     * 在事务内执行业务并维护幂等记录；业务变更与去重结果原子提交。
     * 并发同键插入冲突时等待胜出事务提交后重放其结果。
     */
    private <T> T runIdempotent(String requestId, Operation operation, String fingerprint,
                                Class<T> responseType, Supplier<T> action) {
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
                            return objectMapper.readValue(record.responseJson(), responseType);
                        } catch (Exception e) {
                            throw new IllegalStateException("failed to replay idempotent response", e);
                        }
                    }
                    T result = action.get();
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

    /**
     * 指纹依赖事务内公告版本的幂等执行：先锁幂等键，再以公告行锁为串行化屏障；
     * 屏障放行后重新复查幂等键——同键败者直接重放胜者已提交的完整响应，
     * 不会重复执行裁决或误报版本冲突。同键重放以公告当前版本重建指纹比对：
     * 名单变更推进版本后，同键重放因版本差异判为异参 409。
     */
    private <T> T runIdempotentWithDynamicFingerprint(
            String requestId, Operation operation, String campaignId, Class<T> responseType,
            java.util.function.Function<Campaign, String> fingerprintFn,
            java.util.function.Function<Campaign, T> action) {
        long deadline = System.currentTimeMillis() + IDEMPOTENT_WAIT_MILLIS;
        while (true) {
            try {
                return txTemplate.execute(status -> {
                    IdempotencyRecord existing = idempotencyRepository.lockById(requestId).orElse(null);
                    // 公告行锁是名单变更与曝光裁决的共同串行化屏障
                    Campaign current = campaignRepository.lockById(campaignId)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "campaign not found: " + campaignId));
                    // 屏障放行后胜者可能已提交：重新复查幂等键
                    IdempotencyRecord record = existing != null
                            ? existing : idempotencyRepository.findById(requestId).orElse(null);
                    String fingerprint = fingerprintFn.apply(current);
                    if (record != null) {
                        if (!record.operation().equals(operation.name())
                                || !record.requestFingerprint().equals(fingerprint)) {
                            throw new ApiException(HttpStatus.CONFLICT,
                                    "idempotency key reused with different parameters: " + requestId);
                        }
                        try {
                            return objectMapper.readValue(record.responseJson(), responseType);
                        } catch (Exception e) {
                            throw new IllegalStateException("failed to replay idempotent response", e);
                        }
                    }
                    T result = action.apply(current);
                    idempotencyRepository.insert(new IdempotencyRecord(
                            requestId, operation.name(), fingerprint, writeJson(result)), clock.millis());
                    return result;
                });
            } catch (DuplicateKeyException duplicate) {
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

    /**
     * 确认/取消状态机。调用前已持幂等键；行锁 + CAS 保证并发只有一个终态。
     *
     * @param isConfirm true=确认，false=取消
     */
    private ReservationResponse transition(String reservationId, boolean isConfirm) {
        long now = clock.millis();
        Reservation current = reservationRepository.lockById(reservationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "reservation not found: " + reservationId));

        // 仅结算本单（本事务已持其行锁，加锁顺序保持为 预占单 -> 总账 -> 访客账，避免死锁）
        expireIfDue(current, now);
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
                ledgerRepository.releaseTotal(current.campaignId(), current.utcDate().toLocalDate());
                ledgerRepository.releaseVisitor(
                        current.campaignId(), current.visitorId(), current.utcDate().toLocalDate());
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
     * 结算某公告当前已到期（now &gt;= expiresAt）但仍为 RESERVED 的预占：
     * 行锁查出后逐个 CAS 为 EXPIRED，仅 CAS 成功者释放两级额度，杜绝重复释放。
     */
    private void settleExpired(String campaignId, long now) {
        List<Reservation> expired = reservationRepository.lockExpiredReserved(campaignId, now);
        for (Reservation reservation : expired) {
            expireIfDue(reservation, now);
        }
    }

    /**
     * 若传入预占单（调用方已持其行锁）已到期，则 CAS 转 EXPIRED 并释放两级额度；
     * 未到期或已非 RESERVED 则不做任何变更。
     */
    private void expireIfDue(Reservation reservation, long now) {
        if (reservation.status() == ReservationStatus.RESERVED
                && now >= reservation.expiresAtUtc()) {
            boolean won = reservationRepository.compareAndSetStatus(
                    reservation.reservationId(),
                    ReservationStatus.RESERVED,
                    ReservationStatus.EXPIRED,
                    now);
            if (won) {
                LocalDate utcDate = reservation.utcDate().toLocalDate();
                ledgerRepository.releaseTotal(reservation.campaignId(), utcDate);
                ledgerRepository.releaseVisitor(
                        reservation.campaignId(), reservation.visitorId(), utcDate);
            }
        }
    }

    private Campaign requireCampaign(String campaignId) {
        return campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "campaign not found: " + campaignId));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }
}
