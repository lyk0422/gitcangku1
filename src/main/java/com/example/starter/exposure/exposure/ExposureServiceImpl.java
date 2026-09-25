package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.DecayRecord;
import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.domain.VisitorLastConfirmation;
import com.example.starter.exposure.repo.CampaignRepository;
import com.example.starter.exposure.repo.DecayRecordRepository;
import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.exposure.repo.LedgerRepository;
import com.example.starter.exposure.repo.ReservationRepository;
import com.example.starter.exposure.repo.VisitorLastConfirmationRepository;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CooldownNotElapsedException;
import com.example.starter.exposure.web.CooldownStatusResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.DecayRecordResponse;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpdateCooldownRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * <p>冷却期：申请在占用额度之前行锁读取该访客对该公告的最近确认时刻，
 * 未过冷却期返回 429 并携带冷却结束时刻，不创建预占、不占用额度；
 * 确认成功（CAS 胜出）同事务更新最近确认时刻并按当日第 N 次确认写入
 * 衰减权重 1/N（4 位小数 HALF_UP）。取消与过期不触碰冷却与衰减。
 * 全局行锁顺序固定为 预占单 → 额度账目 → 冷却行，避免死锁。</p>
 */
@Service
public class ExposureServiceImpl implements ExposureService {

    static final int RESERVATION_TTL_MILLIS = 60_000;

    /** 冷却分钟数上限（题干约定 0～1440）。 */
    static final int MAX_COOLDOWN_MINUTES = 1440;

    /** 并发同键竞争时等待胜出事务提交的最大时长。 */
    private static final long IDEMPOTENT_WAIT_MILLIS = 10_000L;

    private final Clock clock;
    private final CampaignRepository campaignRepository;
    private final ReservationRepository reservationRepository;
    private final LedgerRepository ledgerRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final VisitorLastConfirmationRepository lastConfirmationRepository;
    private final DecayRecordRepository decayRecordRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public ExposureServiceImpl(Clock clock,
                               CampaignRepository campaignRepository,
                               ReservationRepository reservationRepository,
                               LedgerRepository ledgerRepository,
                               IdempotencyRepository idempotencyRepository,
                               VisitorLastConfirmationRepository lastConfirmationRepository,
                               DecayRecordRepository decayRecordRepository,
                               ObjectMapper objectMapper,
                               TransactionTemplate txTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.lastConfirmationRepository = lastConfirmationRepository;
        this.decayRecordRepository = decayRecordRepository;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
    }

    @Override
    public CampaignResponse createCampaign(CreateCampaignRequest request) {
        String fingerprint = request.campaignId() + "|" + request.dailyTotalCap() + "|"
                + request.perVisitorDailyCap() + "|" + request.effectiveCooldownMinutes();
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
                            request.effectiveCooldownMinutes(),
                            0L,
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
        return runIdempotent(request.requestId(), Operation.APPLY, fingerprint,
                ReservationResponse.class, () -> {
                    long now = clock.millis();
                    LocalDate utcDate = LocalDate.now(clock);
                    Campaign campaign = requireCampaign(request.campaignId());

                    // 先结算该公告相关过期预占并释放额度
                    settleExpired(campaign.campaignId(), now);

                    // 固定加锁顺序：公告当日总账 -> 访客当日账 -> 冷却行，避免死锁
                    ledgerRepository.ensureTotalRow(campaign.campaignId(), utcDate);
                    ledgerRepository.ensureVisitorRow(campaign.campaignId(), request.visitorId(), utcDate);
                    int usedTotal = ledgerRepository.lockUsedTotal(campaign.campaignId(), utcDate);
                    int usedVisitor = ledgerRepository.lockUsedVisitor(
                            campaign.campaignId(), request.visitorId(), utcDate);

                    // 冷却判定在占用额度之前：未过冷却期 429，不创建预占、不占用当日额度
                    checkCooldown(campaign, request.visitorId(), now);

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
    public CampaignResponse updateCooldown(String campaignId, UpdateCooldownRequest request) {
        // 指纹含公告编号：同一 requestId 改用于其他公告视为异参 409
        String fingerprint = campaignId + "|" + request.expectedVersion() + "|"
                + request.cooldownMinutes();
        return runIdempotent(request.requestId(), Operation.UPDATE_COOLDOWN, fingerprint,
                CampaignResponse.class, () -> {
                    // 行锁公告行：与并发冷却修改按提交顺序串行裁决
                    Campaign campaign = campaignRepository.lockById(campaignId)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "campaign not found: " + campaignId));
                    if (campaign.version() != request.expectedVersion()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "campaign version conflict: expected " + request.expectedVersion()
                                        + " but was " + campaign.version());
                    }
                    if (!campaignRepository.compareAndUpdateCooldown(
                            campaignId, request.expectedVersion(),
                            request.cooldownMinutes())) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "campaign version conflict: expected " + request.expectedVersion());
                    }
                    return CampaignResponse.from(
                            campaignRepository.findById(campaignId).orElseThrow());
                });
    }

    @Override
    public CooldownStatusResponse queryCooldown(String campaignId, String visitorId) {
        return txTemplate.execute(status -> {
            Campaign campaign = requireCampaign(campaignId);
            long now = clock.millis();
            Long lastConfirmedAt = lastConfirmationRepository.find(campaignId, visitorId)
                    .map(VisitorLastConfirmation::lastConfirmedAtUtc)
                    .orElse(null);
            Long cooldownUntil = cooldownUntil(campaign.cooldownMinutes(), lastConfirmedAt);
            boolean cooling = cooldownUntil != null && now < cooldownUntil;
            return new CooldownStatusResponse(
                    campaignId, visitorId, campaign.cooldownMinutes(),
                    lastConfirmedAt, cooldownUntil, cooling, now);
        });
    }

    @Override
    public List<DecayRecordResponse> queryDecay(String campaignId, String visitorId,
                                                LocalDate requestedDate) {
        return txTemplate.execute(status -> {
            requireCampaign(campaignId);
            LocalDate utcDate = requestedDate != null ? requestedDate : LocalDate.now(clock);
            return decayRecordRepository.findByVisitorDay(campaignId, visitorId, utcDate)
                    .stream()
                    .map(DecayRecordResponse::from)
                    .toList();
        });
    }

    // ---- 内部辅助（作用域末尾） ----

    /** 幂等操作类型，同时标识存储响应的反序列化类型。 */
    private enum Operation {
        CREATE_CAMPAIGN,
        APPLY,
        CONFIRM,
        CANCEL,
        UPDATE_COOLDOWN
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
                // CAS 胜出者同事务记录确认：更新最近确认时刻并写入当日衰减权重
                recordConfirmation(current, now);
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
            // 重复同类终态操作：返回原状态，不重复释放额度，也不重复记录冷却与衰减
        } else {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "unexpected reservation status: " + current.status());
        }

        Reservation result = reservationRepository.lockById(reservationId).orElseThrow();
        return ReservationResponse.from(result);
    }

    /**
     * 申请阶段冷却判定：行锁读取该访客对该公告的最近确认时刻（与并发确认按提交顺序串行），
     * 未过冷却期抛 429 并携带冷却结束时刻；不创建预占、不占用额度。
     * 冷却分钟数为 0 或该访客从未确认过该公告时不限制。
     */
    private void checkCooldown(Campaign campaign, String visitorId, long now) {
        if (campaign.cooldownMinutes() <= 0) {
            return;
        }
        Long lastConfirmedAt = lastConfirmationRepository
                .lock(campaign.campaignId(), visitorId)
                .map(VisitorLastConfirmation::lastConfirmedAtUtc)
                .orElse(null);
        Long cooldownUntil = cooldownUntil(campaign.cooldownMinutes(), lastConfirmedAt);
        if (cooldownUntil != null && now < cooldownUntil) {
            throw new CooldownNotElapsedException(
                    "exposure cooldown not elapsed for campaign " + campaign.campaignId()
                            + " visitor " + visitorId,
                    cooldownUntil);
        }
    }

    /**
     * 确认成功同事务记录：更新最近确认时刻并按确认时刻所在 UTC 日的第 N 次确认
     * 写入衰减权重 1/N（4 位小数 HALF_UP）。先锁访客当日账目行使申请与确认按
     * 提交顺序串行；冷却行写锁串行化同访客并发确认，保证当日序号互斥递增。
     */
    private void recordConfirmation(Reservation reservation, long confirmedAtUtc) {
        String campaignId = reservation.campaignId();
        String visitorId = reservation.visitorId();
        LocalDate confirmDay = LocalDate.now(clock);

        ledgerRepository.ensureVisitorRow(campaignId, visitorId, confirmDay);
        ledgerRepository.lockUsedVisitor(campaignId, visitorId, confirmDay);

        lastConfirmationRepository.upsert(campaignId, visitorId, confirmedAtUtc);
        // 持锁重读：与并发确认在同一冷却行行锁上排队至对方事务提交后再计数
        lastConfirmationRepository.lock(campaignId, visitorId);

        int sequenceNo = decayRecordRepository.countByDay(campaignId, visitorId, confirmDay) + 1;
        BigDecimal weight = BigDecimal.ONE.divide(
                BigDecimal.valueOf(sequenceNo), 4, RoundingMode.HALF_UP);
        decayRecordRepository.insert(new DecayRecord(
                null, campaignId, visitorId, java.sql.Date.valueOf(confirmDay),
                sequenceNo, weight, reservation.reservationId(), confirmedAtUtc));
    }

    /** 冷却结束时刻；无冷却配置或从未确认过时为 null。 */
    private Long cooldownUntil(int cooldownMinutes, Long lastConfirmedAtUtc) {
        if (cooldownMinutes <= 0 || lastConfirmedAtUtc == null) {
            return null;
        }
        return lastConfirmedAtUtc + cooldownMinutes * 60_000L;
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
