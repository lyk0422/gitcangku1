package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.CampaignCategory;
import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.domain.VisitorQuietHours;
import com.example.starter.exposure.repo.CampaignRepository;
import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.exposure.repo.LedgerRepository;
import com.example.starter.exposure.repo.ReservationRepository;
import com.example.starter.exposure.repo.SuppressionCounterRepository;
import com.example.starter.exposure.repo.VisitorQuietHoursRepository;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.ApplyResponse;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuietHoursSettingsRequest;
import com.example.starter.exposure.web.QuietHoursSettingsResponse;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SuppressionStatsResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 公告曝光频控业务服务实现。
 *
 * <p>所有写操作以 requestId 为全局幂等键：同键同参重放原成功结果，异参 409；
 * 业务失败随事务回滚，不占幂等键。申请先判定访客静默（抑制不创建预占、不写额度账目），
 * 再结算过期预占并校验两级额度；终态竞争由行锁 + 状态 CAS 保证只允许一个终态。
 * 静默设置修改走乐观锁版本，且申请事务持设置行锁，按提交顺序串行裁决。</p>
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
    private final IdempotencyRepository idempotencyRepository;
    private final VisitorQuietHoursRepository quietHoursRepository;
    private final SuppressionCounterRepository suppressionCounterRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public ExposureServiceImpl(Clock clock,
                               CampaignRepository campaignRepository,
                               ReservationRepository reservationRepository,
                               LedgerRepository ledgerRepository,
                               IdempotencyRepository idempotencyRepository,
                               VisitorQuietHoursRepository quietHoursRepository,
                               SuppressionCounterRepository suppressionCounterRepository,
                               ObjectMapper objectMapper,
                               TransactionTemplate txTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.quietHoursRepository = quietHoursRepository;
        this.suppressionCounterRepository = suppressionCounterRepository;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
    }

    @Override
    public CampaignResponse createCampaign(CreateCampaignRequest request) {
        String fingerprint = request.campaignId() + "|" + request.dailyTotalCap() + "|"
                + request.perVisitorDailyCap() + "|" + request.category();
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
                            request.category(),
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
    public ApplyResponse apply(ApplyExposureRequest request) {
        String fingerprint = request.campaignId() + "|" + request.visitorId();
        return runIdempotent(request.requestId(), Operation.APPLY, fingerprint,
                ApplyResponse.class, () -> doApply(request));
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

    @Override
    public QuietHoursSettingsResponse saveQuietHours(QuietHoursSettingsRequest request) {
        if (request.quietStartMinute().equals(request.quietEndMinute())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "quiet start minute and end minute must differ");
        }
        String fingerprint = request.visitorId() + "|" + request.utcOffsetMinutes() + "|"
                + request.quietStartMinute() + "|" + request.quietEndMinute() + "|"
                + request.allowCritical() + "|" + request.expectedVersion();
        return runIdempotent(request.requestId(), Operation.SAVE_QUIET_HOURS, fingerprint,
                QuietHoursSettingsResponse.class, () -> doSaveQuietHours(request));
    }

    @Override
    public QuietHoursSettingsResponse getQuietHours(String visitorId) {
        return txTemplate.execute(status -> {
            VisitorQuietHours settings = quietHoursRepository.lockById(visitorId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "visitor quiet hours not registered: " + visitorId));
            return QuietHoursSettingsResponse.from(settings);
        });
    }

    @Override
    public SuppressionStatsResponse querySuppressionStats(String campaignId, String visitorId,
                                                          LocalDate requestedDate) {
        return txTemplate.execute(status -> {
            requireCampaign(campaignId);
            LocalDate utcDate = requestedDate != null ? requestedDate : LocalDate.now(clock);
            int count = suppressionCounterRepository.getCount(campaignId, visitorId, utcDate);
            return new SuppressionStatsResponse(campaignId, visitorId, utcDate, count);
        });
    }

    // ---- 内部辅助（作用域末尾） ----

    /** 幂等操作类型，同时标识存储响应的反序列化类型。 */
    private enum Operation {
        CREATE_CAMPAIGN,
        APPLY,
        CONFIRM,
        CANCEL,
        SAVE_QUIET_HOURS
    }

    /**
     * 申请曝光事务主体：静默判定严格先于额度校验与账目写入。
     * 加锁顺序固定为 访客设置 -> 公告当日总账 -> 访客当日账，避免死锁。
     */
    private ApplyResponse doApply(ApplyExposureRequest request) {
        long now = clock.millis();
        LocalDate utcDate = LocalDate.now(clock);
        Campaign campaign = requireCampaign(request.campaignId());

        // 1) 静默判定（持访客设置行锁，与设置修改按提交顺序串行）
        Optional<VisitorQuietHours> settings = quietHoursRepository.lockById(request.visitorId());
        if (settings.isPresent() && isSuppressedNow(settings.get(), campaign.category(), now)) {
            VisitorQuietHours q = settings.get();
            long quietUntilUtc = QuietHoursCalculator.nextQuietEndUtcMillis(
                    now, q.utcOffsetMinutes(), q.quietStartMinute(), q.quietEndMinute());
            // 抑制计数按公告、访客与 UTC 日累计；不创建预占、不触碰额度账目
            suppressionCounterRepository.increment(
                    campaign.campaignId(), request.visitorId(), utcDate, campaign.category());
            return ApplyResponse.suppressed(
                    campaign.campaignId(), request.visitorId(),
                    campaign.category(), utcDate, quietUntilUtc);
        }

        // 2) 结算该公告相关过期预占并释放额度（静默期间不结算，避免无谓写竞争）
        settleExpired(campaign.campaignId(), now);

        // 3) 固定加锁顺序：公告当日总账 -> 访客当日账
        ledgerRepository.ensureTotalRow(campaign.campaignId(), utcDate);
        ledgerRepository.ensureVisitorRow(campaign.campaignId(), request.visitorId(), utcDate);
        int usedTotal = ledgerRepository.lockUsedTotal(campaign.campaignId(), utcDate);
        int usedVisitor = ledgerRepository.lockUsedVisitor(
                campaign.campaignId(), request.visitorId(), utcDate);

        // 4) 任一额度已满则 429，两个额度均不增加（尚未写入），抑制不参与此分支
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
        return ApplyResponse.reserved(reservation, campaign.category());
    }

    /**
     * 判定当前时刻公告类别是否应被静默抑制：
     * SERVICE/MARKETING 落入静默区间即抑制；CRITICAL 仅在 allowCritical=false 时抑制。
     */
    private boolean isSuppressedNow(VisitorQuietHours settings, CampaignCategory category, long now) {
        int localMinute = QuietHoursCalculator.localMinute(now, settings.utcOffsetMinutes());
        boolean within = QuietHoursCalculator.isWithinQuietHours(
                localMinute, settings.quietStartMinute(), settings.quietEndMinute());
        if (!within) {
            return false;
        }
        if (category == CampaignCategory.CRITICAL) {
            return !settings.allowCritical();
        }
        return true;
    }

    /**
     * 登记/修改静默设置事务主体：expectedVersion=0 为首次登记；
     * 已存在时按乐观锁版本整体覆盖，冲突返回 409。只影响后续申请。
     */
    private QuietHoursSettingsResponse doSaveQuietHours(QuietHoursSettingsRequest request) {
        long now = clock.millis();
        Optional<VisitorQuietHours> existing = quietHoursRepository.lockById(request.visitorId());
        if (existing.isEmpty()) {
            if (request.expectedVersion() != 0) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "visitor quiet hours not registered, expectedVersion must be 0 for registration: "
                                + request.visitorId());
            }
            VisitorQuietHours settings = new VisitorQuietHours(
                    request.visitorId(),
                    request.utcOffsetMinutes(),
                    request.quietStartMinute(),
                    request.quietEndMinute(),
                    request.allowCritical(),
                    1,
                    now,
                    now);
            try {
                quietHoursRepository.insert(settings);
            } catch (DuplicateKeyException duplicate) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "visitor quiet hours registered concurrently: " + request.visitorId());
            }
            return QuietHoursSettingsResponse.from(settings);
        }
        VisitorQuietHours current = existing.get();
        if (current.version() != request.expectedVersion()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "quiet hours version conflict: expected " + current.version()
                            + " but got " + request.expectedVersion());
        }
        VisitorQuietHours updated = new VisitorQuietHours(
                request.visitorId(),
                request.utcOffsetMinutes(),
                request.quietStartMinute(),
                request.quietEndMinute(),
                request.allowCritical(),
                current.version() + 1,
                current.createdAtUtc(),
                now);
        if (!quietHoursRepository.compareAndSetUpdate(updated, request.expectedVersion())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "quiet hours version conflict: " + request.visitorId());
        }
        return QuietHoursSettingsResponse.from(updated);
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
