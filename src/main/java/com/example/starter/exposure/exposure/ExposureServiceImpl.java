package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.Placement;
import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.repo.CampaignRepository;
import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.exposure.repo.LedgerRepository;
import com.example.starter.exposure.repo.PlacementRepository;
import com.example.starter.exposure.repo.ReservationRepository;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.ApplyPlacementExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.CreatePlacementRequest;
import com.example.starter.exposure.web.PlacementResponse;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationPlacementDetail;
import com.example.starter.exposure.web.ReservationResponse;
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
 * <p>三层额度（公告当日总额、访客跨展示位共享当日上限、展示位当日额度）在同一事务内
 * 按固定加锁顺序（公告行 → 总账 → 访客账 → 展示位账 → 展示位行）取得，任一已满整体
 * 回滚返回 429；取消/到期释放三层，确认持续占用。新增展示位持公告行锁并递增
 * configVersion，与申请事务按提交顺序串行化。</p>
 */
@Service
public class ExposureServiceImpl implements ExposureService {

    static final int RESERVATION_TTL_MILLIS = 60_000;

    /** 并发同键竞争时等待胜出事务提交的最大时长。 */
    private static final long IDEMPOTENT_WAIT_MILLIS = 10_000L;

    private final Clock clock;
    private final CampaignRepository campaignRepository;
    private final PlacementRepository placementRepository;
    private final ReservationRepository reservationRepository;
    private final LedgerRepository ledgerRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public ExposureServiceImpl(Clock clock,
                               CampaignRepository campaignRepository,
                               PlacementRepository placementRepository,
                               ReservationRepository reservationRepository,
                               LedgerRepository ledgerRepository,
                               IdempotencyRepository idempotencyRepository,
                               ObjectMapper objectMapper,
                               TransactionTemplate txTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.placementRepository = placementRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
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
                    long now = clock.millis();
                    Campaign campaign = new Campaign(
                            request.campaignId(),
                            request.dailyTotalCap(),
                            request.perVisitorDailyCap(),
                            1,
                            now);
                    try {
                        campaignRepository.insert(campaign);
                    } catch (DuplicateKeyException duplicateCampaign) {
                        // 并发创建同一 campaignId：明确返回 409，而非误报幂等键冲突
                        throw new ApiException(HttpStatus.CONFLICT,
                                "campaign already exists: " + request.campaignId());
                    }
                    // 公告创建即生成 DEFAULT 展示位，日额度等于公告日总额度，配置版本 1
                    placementRepository.insert(new Placement(
                            campaign.campaignId(),
                            Placement.DEFAULT_CODE,
                            campaign.dailyTotalCap(),
                            1,
                            now));
                    return CampaignResponse.from(campaign);
                });
    }

    @Override
    public PlacementResponse createPlacement(String campaignId, CreatePlacementRequest request) {
        String fingerprint = campaignId + "|" + request.placementCode() + "|" + request.dailyCap()
                + "|" + request.expectedConfigVersion();
        return runIdempotent(request.requestId(), Operation.CREATE_PLACEMENT, fingerprint,
                PlacementResponse.class, () -> {
                    long now = clock.millis();
                    // 持公告行锁串行化所有配置变更与额度申请
                    Campaign campaign = campaignRepository.lockById(campaignId)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "campaign not found: " + campaignId));

                    if (campaign.configVersion() != request.expectedConfigVersion()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "config version mismatch: expected " + request.expectedConfigVersion()
                                        + " but current is " + campaign.configVersion());
                    }
                    if (Placement.DEFAULT_CODE.equals(request.placementCode())) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "placement code reserved: " + Placement.DEFAULT_CODE);
                    }
                    int existing = placementRepository.countByCampaign(campaignId);
                    if (existing >= PlacementRepository.MAX_PLACEMENTS_PER_CAMPAIGN) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "placement limit reached: at most "
                                        + PlacementRepository.MAX_PLACEMENTS_PER_CAMPAIGN
                                        + " placements per campaign");
                    }
                    if (request.dailyCap() > campaign.dailyTotalCap()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "placement daily cap must not exceed campaign daily total cap: "
                                        + campaign.dailyTotalCap());
                    }

                    int newVersion = campaign.configVersion() + 1;
                    Placement placement = new Placement(
                            campaignId,
                            request.placementCode(),
                            request.dailyCap(),
                            newVersion,
                            now);
                    try {
                        placementRepository.insert(placement);
                    } catch (DuplicateKeyException duplicatePlacement) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "placement already exists: " + request.placementCode());
                    }
                    // 展示位、版本号在同一事务原子提交
                    campaignRepository.updateConfigVersion(campaignId, newVersion);
                    return PlacementResponse.from(placement);
                });
    }

    @Override
    public ReservationResponse apply(ApplyExposureRequest request) {
        // 旧入口等价于申请 DEFAULT；指纹保持旧格式以兼容旧幂等语义
        String fingerprint = request.campaignId() + "|" + request.visitorId();
        return runIdempotent(request.requestId(), Operation.APPLY, fingerprint,
                ReservationResponse.class,
                () -> reserve(request.campaignId(), Placement.DEFAULT_CODE, request.visitorId()));
    }

    @Override
    public ReservationResponse applyPlacement(ApplyPlacementExposureRequest request) {
        String fingerprint = request.campaignId() + "|" + request.placementCode() + "|"
                + request.visitorId();
        return runIdempotent(request.requestId(), Operation.APPLY, fingerprint,
                ReservationResponse.class,
                () -> reserve(request.campaignId(), request.placementCode(), request.visitorId()));
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
    public QuotaResponse queryQuota(String campaignId, String visitorId, String placementCode,
                                    LocalDate requestedDate) {
        return txTemplate.execute(status -> {
            Campaign campaign = requireCampaign(campaignId);
            long now = clock.millis();
            LocalDate utcDate = requestedDate != null ? requestedDate : LocalDate.now(clock);

            settleExpired(campaignId, now);

            int usedTotal = ledgerRepository.getUsedTotal(campaignId, utcDate);
            Integer perVisitorCap = null;
            Integer usedVisitor = null;
            Integer remainingVisitor = null;
            if (visitorId != null && !visitorId.isBlank()) {
                perVisitorCap = campaign.perVisitorDailyCap();
                usedVisitor = ledgerRepository.getUsedVisitor(campaignId, visitorId, utcDate);
                remainingVisitor = perVisitorCap - usedVisitor;
            }

            Integer placementDailyCap = null;
            Integer usedPlacement = null;
            Integer remainingPlacement = null;
            if (placementCode != null && !placementCode.isBlank()) {
                Placement placement = placementRepository.findById(campaignId, placementCode)
                        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                "placement not found: " + placementCode));
                placementDailyCap = placement.dailyCap();
                usedPlacement = ledgerRepository.getUsedPlacement(campaignId, placementCode, utcDate);
                remainingPlacement = placementDailyCap - usedPlacement;
            }

            // 预占展示位明细：当日仍活跃（RESERVED/CONFIRMED）的预占，按维度过滤
            List<ReservationPlacementDetail> reservations = reservationRepository
                    .findActive(campaignId, visitorId, placementCode, utcDate)
                    .stream()
                    .map(ReservationPlacementDetail::from)
                    .toList();

            return new QuotaResponse(
                    campaignId,
                    visitorId == null || visitorId.isBlank() ? null : visitorId,
                    utcDate,
                    campaign.dailyTotalCap(), usedTotal,
                    campaign.dailyTotalCap() - usedTotal,
                    perVisitorCap, usedVisitor, remainingVisitor,
                    now,
                    campaign.configVersion(),
                    placementCode == null || placementCode.isBlank() ? null : placementCode,
                    placementDailyCap, usedPlacement, remainingPlacement,
                    reservations);
        });
    }

    // ---- 内部辅助（作用域末尾） ----

    /** 幂等操作类型，同时标识存储响应的反序列化类型。 */
    private enum Operation {
        CREATE_CAMPAIGN,
        CREATE_PLACEMENT,
        APPLY,
        CONFIRM,
        CANCEL
    }

    /**
     * 按展示位预占一次曝光：同一事务内同时取得三层额度，任一已满返回 429 且三层均不增加。
     */
    private ReservationResponse reserve(String campaignId, String placementCode, String visitorId) {
        long now = clock.millis();
        LocalDate utcDate = LocalDate.now(clock);

        // 先结算该公告相关过期预占并释放三层额度（预占行最先加锁）
        settleExpired(campaignId, now);

        // 公告行锁：与新增展示位事务按提交顺序串行化；随后固定加锁顺序 总账→访客账→展示位账
        Campaign campaign = campaignRepository.lockById(campaignId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "campaign not found: " + campaignId));
        Placement placement = placementRepository.lockById(campaignId, placementCode)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "placement not found: " + placementCode));

        ledgerRepository.ensureTotalRow(campaignId, utcDate);
        ledgerRepository.ensureVisitorRow(campaignId, visitorId, utcDate);
        ledgerRepository.ensurePlacementRow(campaignId, placementCode, utcDate);
        int usedTotal = ledgerRepository.lockUsedTotal(campaignId, utcDate);
        int usedVisitor = ledgerRepository.lockUsedVisitor(campaignId, visitorId, utcDate);
        int usedPlacement = ledgerRepository.lockUsedPlacement(campaignId, placementCode, utcDate);

        // 任一额度已满则 429，三层额度均不增加（尚未写入，事务回滚）
        if (usedTotal + 1 > campaign.dailyTotalCap()
                || usedVisitor + 1 > campaign.perVisitorDailyCap()
                || usedPlacement + 1 > placement.dailyCap()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "exposure quota exhausted for campaign " + campaignId
                            + " placement " + placementCode);
        }
        ledgerRepository.addTotal(campaignId, utcDate, 1);
        ledgerRepository.addVisitor(campaignId, visitorId, utcDate, 1);
        ledgerRepository.addPlacement(campaignId, placementCode, utcDate, 1);

        String reservationId = UUID.randomUUID().toString().replace("-", "");
        Reservation reservation = new Reservation(
                reservationId,
                campaignId,
                visitorId,
                placementCode,
                java.sql.Date.valueOf(utcDate),
                ReservationStatus.RESERVED,
                now,
                now + RESERVATION_TTL_MILLIS,
                null);
        reservationRepository.insert(reservation);
        return ReservationResponse.from(reservation);
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
     * 确认/取消状态机。调用前已持幂等键；行锁 + CAS 保证并发只有一个终态。
     *
     * @param isConfirm true=确认，false=取消
     */
    private ReservationResponse transition(String reservationId, boolean isConfirm) {
        long now = clock.millis();
        Reservation current = reservationRepository.lockById(reservationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "reservation not found: " + reservationId));

        // 仅结算本单（本事务已持其行锁，加锁顺序保持为 预占单 -> 总账 -> 访客账 -> 展示位账）
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
                // 取消释放三层额度；固定顺序先总账后访客账再展示位账
                releaseAllTiers(current);
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
     * 行锁查出后逐个 CAS 为 EXPIRED，仅 CAS 成功者释放三层额度，杜绝重复释放。
     */
    private void settleExpired(String campaignId, long now) {
        List<Reservation> expired = reservationRepository.lockExpiredReserved(campaignId, now);
        for (Reservation reservation : expired) {
            expireIfDue(reservation, now);
        }
    }

    /**
     * 若传入预占单（调用方已持其行锁）已到期，则 CAS 转 EXPIRED 并释放三层额度；
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
                releaseAllTiers(reservation);
            }
        }
    }

    /** 释放预占占用的三层额度：总账 → 访客账 → 展示位账，固定顺序避免死锁。 */
    private void releaseAllTiers(Reservation reservation) {
        LocalDate utcDate = reservation.utcDate().toLocalDate();
        ledgerRepository.releaseTotal(reservation.campaignId(), utcDate);
        ledgerRepository.releaseVisitor(
                reservation.campaignId(), reservation.visitorId(), utcDate);
        ledgerRepository.releasePlacement(
                reservation.campaignId(), reservation.placementCode(), utcDate);
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
