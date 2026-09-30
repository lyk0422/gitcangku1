package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.Activity;
import com.example.starter.exposure.domain.ActivityDayConfig;
import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.repo.ActivityDayConfigRepository;
import com.example.starter.exposure.repo.ActivityHourLedgerRepository;
import com.example.starter.exposure.repo.ActivityRepository;
import com.example.starter.exposure.repo.ActivitySettlementRepository;
import com.example.starter.exposure.repo.ActivitySettlementRepository.SettlementRow;
import com.example.starter.exposure.repo.CampaignRepository;
import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.exposure.repo.LedgerRepository;
import com.example.starter.exposure.repo.ReservationRepository;
import com.example.starter.exposure.web.ActivityDayConfigResponse;
import com.example.starter.exposure.web.ActivityDayQuotaResponse;
import com.example.starter.exposure.web.ActivityDaySettlementResponse;
import com.example.starter.exposure.web.ActivityResponse;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateActivityRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.MigrateActivityRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpsertActivityDayConfigRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 公告曝光频控业务服务实现。
 *
 * <p>所有写操作以 requestId 为全局幂等键：同键同参重放原成功结果，异参 409；
 * 业务失败随事务回滚，不占幂等键。所有操作与额度查询先结算相关过期预占，
 * 不依赖后台定时器。终态竞争由行锁 + 状态 CAS 保证只允许一个终态。</p>
 *
 * <p>公告可归入活动：申请时除公告/访客两级额度外，为当前 UTC 小时（左闭右开）
 * 原子预占活动小时名额；小时预算耗尽返回 429 并给出下一可用小时，未配置小时预算
 * 返回 409，两种情况均不消耗公告或访客侧名额。确认同时结算公告、访客和活动三侧
 * 名额；取消或过期只释放尚未确认的同一小时预占。活动配置修改携带 expectedVersion
 * 乐观锁，已开始小时不可下调；活动迁移只影响迁移后的新预占。</p>
 */
@Service
public class ExposureServiceImpl implements ExposureService {

    static final int RESERVATION_TTL_MILLIS = 60_000;

    /** 并发同键竞争时等待胜出事务提交的最大时长。 */
    private static final long IDEMPOTENT_WAIT_MILLIS = 10_000L;

    private static final int HOURS_PER_DAY = 24;
    private static final long HOUR_MILLIS = 3_600_000L;

    private final Clock clock;
    private final CampaignRepository campaignRepository;
    private final ReservationRepository reservationRepository;
    private final LedgerRepository ledgerRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ActivityRepository activityRepository;
    private final ActivityDayConfigRepository dayConfigRepository;
    private final ActivityHourLedgerRepository hourLedgerRepository;
    private final ActivitySettlementRepository settlementRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public ExposureServiceImpl(Clock clock,
                               CampaignRepository campaignRepository,
                               ReservationRepository reservationRepository,
                               LedgerRepository ledgerRepository,
                               IdempotencyRepository idempotencyRepository,
                               ActivityRepository activityRepository,
                               ActivityDayConfigRepository dayConfigRepository,
                               ActivityHourLedgerRepository hourLedgerRepository,
                               ActivitySettlementRepository settlementRepository,
                               ObjectMapper objectMapper,
                               TransactionTemplate txTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.activityRepository = activityRepository;
        this.dayConfigRepository = dayConfigRepository;
        this.hourLedgerRepository = hourLedgerRepository;
        this.settlementRepository = settlementRepository;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
    }

    @Override
    public CampaignResponse createCampaign(CreateCampaignRequest request) {
        String fingerprint = request.campaignId() + "|" + request.dailyTotalCap() + "|"
                + request.perVisitorDailyCap() + "|" + String.valueOf(request.activityId());
        return runIdempotent(request.requestId(), Operation.CREATE_CAMPAIGN, fingerprint,
                CampaignResponse.class, () -> {
                    if (campaignRepository.findById(request.campaignId()).isPresent()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "campaign already exists: " + request.campaignId());
                    }
                    if (request.activityId() != null) {
                        requireActivity(request.activityId());
                    }
                    Campaign campaign = new Campaign(
                            request.campaignId(),
                            request.dailyTotalCap(),
                            request.perVisitorDailyCap(),
                            clock.millis(),
                            request.activityId());
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

                    // 活动侧：为当前 UTC 小时（左闭右开）原子预占名额。
                    // 固定加锁顺序：活动配置 -> 活动小时账 -> 公告当日总账 -> 访客当日账，避免死锁
                    String activityId = campaign.activityId();
                    Long hourStartUtc = null;
                    int hourOfDay = -1;
                    if (activityId != null) {
                        hourStartUtc = hourStartOf(now);
                        hourOfDay = hourOfDayOf(hourStartUtc);
                        ActivityDayConfig dayConfig = dayConfigRepository
                                .lockConfig(activityId, utcDate)
                                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                                        "activity hourly budget not configured: activity=" + activityId
                                                + " utcDate=" + utcDate));
                        hourLedgerRepository.ensureRow(activityId, utcDate, hourOfDay);
                        int usedHour = hourLedgerRepository.lockUsed(activityId, utcDate, hourOfDay);
                        int budget = dayConfig.hourlyBudgets().get(hourOfDay);
                        if (usedHour + 1 > budget) {
                            Long nextHour = findNextAvailableHour(
                                    activityId, utcDate, hourOfDay, dayConfig);
                            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                                    "activity hour budget exhausted: activity=" + activityId
                                            + " utcDate=" + utcDate + " hour=" + hourOfDay
                                            + " used=" + usedHour + " budget=" + budget,
                                    nextHour);
                        }
                    }

                    ledgerRepository.ensureTotalRow(campaign.campaignId(), utcDate);
                    ledgerRepository.ensureVisitorRow(campaign.campaignId(), request.visitorId(), utcDate);
                    int usedTotal = ledgerRepository.lockUsedTotal(campaign.campaignId(), utcDate);
                    int usedVisitor = ledgerRepository.lockUsedVisitor(
                            campaign.campaignId(), request.visitorId(), utcDate);

                    // 任一额度已满则 429，三级额度均不增加（尚未写入）
                    if (usedTotal + 1 > campaign.dailyTotalCap()
                            || usedVisitor + 1 > campaign.perVisitorDailyCap()) {
                        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                                "exposure quota exhausted for campaign " + campaign.campaignId()
                                        + ": usedTotal=" + usedTotal + "/" + campaign.dailyTotalCap()
                                        + " usedVisitor=" + usedVisitor + "/"
                                        + campaign.perVisitorDailyCap());
                    }
                    if (activityId != null) {
                        hourLedgerRepository.addUsed(activityId, utcDate, hourOfDay, 1);
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
                            null,
                            activityId,
                            hourStartUtc);
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
    public ActivityResponse createActivity(CreateActivityRequest request) {
        return runIdempotent(request.requestId(), Operation.CREATE_ACTIVITY, request.activityId(),
                ActivityResponse.class, () -> {
                    if (activityRepository.findById(request.activityId()).isPresent()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "activity already exists: " + request.activityId());
                    }
                    Activity activity = new Activity(request.activityId(), clock.millis());
                    try {
                        activityRepository.insert(activity);
                    } catch (DuplicateKeyException duplicateActivity) {
                        // 并发创建同一 activityId：明确返回 409
                        throw new ApiException(HttpStatus.CONFLICT,
                                "activity already exists: " + request.activityId());
                    }
                    return new ActivityResponse(activity.activityId(), activity.createdAtUtc());
                });
    }

    @Override
    public ActivityResponse getActivity(String activityId) {
        return txTemplate.execute(status -> {
            Activity activity = requireActivity(activityId);
            return new ActivityResponse(activity.activityId(), activity.createdAtUtc());
        });
    }

    @Override
    public ActivityDayConfigResponse upsertDayConfig(String activityId, LocalDate utcDate,
                                                     UpsertActivityDayConfigRequest request) {
        validateBudgets(request.dailyTotalCap(), request.hourlyBudgets());
        String fingerprint = activityId + "|" + utcDate + "|" + request.expectedVersion() + "|"
                + request.dailyTotalCap() + "|" + request.hourlyBudgets();
        return runIdempotent(request.requestId(), Operation.UPSERT_DAY_CONFIG, fingerprint,
                ActivityDayConfigResponse.class, () -> {
                    requireActivity(activityId);
                    long now = clock.millis();
                    var existing = dayConfigRepository.lockConfig(activityId, utcDate);
                    ActivityDayConfig saved;
                    if (existing.isEmpty()) {
                        if (request.expectedVersion() != 0L) {
                            throw new ApiException(HttpStatus.CONFLICT,
                                    "day config not found: activity=" + activityId
                                            + " utcDate=" + utcDate
                                            + ", expectedVersion actual=" + request.expectedVersion()
                                            + " required=0");
                        }
                        saved = new ActivityDayConfig(activityId, utcDate, request.dailyTotalCap(),
                                List.copyOf(request.hourlyBudgets()), 1L, now);
                        try {
                            dayConfigRepository.insert(saved);
                        } catch (DuplicateKeyException duplicateConfig) {
                            // 并发创建同一日配置：明确返回 409
                            throw new ApiException(HttpStatus.CONFLICT,
                                    "day config already exists: activity=" + activityId
                                            + " utcDate=" + utcDate);
                        }
                    } else {
                        ActivityDayConfig current = existing.get();
                        if (request.expectedVersion() != current.version()) {
                            throw new ApiException(HttpStatus.CONFLICT,
                                    "day config version mismatch: activity=" + activityId
                                            + " utcDate=" + utcDate
                                            + " actual=" + current.version()
                                            + " required=" + request.expectedVersion());
                        }
                        assertStartedHoursNotDecreased(utcDate, current.hourlyBudgets(),
                                request.hourlyBudgets(), now);
                        saved = new ActivityDayConfig(activityId, utcDate, request.dailyTotalCap(),
                                List.copyOf(request.hourlyBudgets()), current.version() + 1, now);
                        if (!dayConfigRepository.update(saved, current.version())) {
                            throw new ApiException(HttpStatus.CONFLICT,
                                    "day config version changed concurrently: activity=" + activityId
                                            + " utcDate=" + utcDate);
                        }
                    }
                    return ActivityDayConfigResponse.from(saved);
                });
    }

    @Override
    public ActivityDayConfigResponse getDayConfig(String activityId, LocalDate utcDate) {
        return txTemplate.execute(status -> {
            requireActivity(activityId);
            ActivityDayConfig config = dayConfigRepository.findConfig(activityId, utcDate)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "day config not found: activity=" + activityId + " utcDate=" + utcDate));
            return ActivityDayConfigResponse.from(config);
        });
    }

    @Override
    public ActivityDayQuotaResponse queryActivityDayQuota(String activityId, LocalDate utcDate) {
        return txTemplate.execute(status -> {
            requireActivity(activityId);
            long now = clock.millis();
            settleExpiredByActivity(activityId, now);

            var config = dayConfigRepository.findConfig(activityId, utcDate);
            Map<Integer, Integer> usedByHour = hourLedgerRepository.usedByHour(activityId, utcDate);
            List<ActivityDayQuotaResponse.ActivityHourQuota> hours = new ArrayList<>(HOURS_PER_DAY);
            int usedTotal = 0;
            for (int hour = 0; hour < HOURS_PER_DAY; hour++) {
                int used = usedByHour.getOrDefault(hour, 0);
                usedTotal += used;
                final int h = hour;
                Integer budget = config.map(c -> c.hourlyBudgets().get(h)).orElse(null);
                hours.add(new ActivityDayQuotaResponse.ActivityHourQuota(
                        hour, hourStartEpoch(utcDate, hour), budget, used,
                        budget == null ? null : budget - used));
            }
            return new ActivityDayQuotaResponse(
                    activityId, utcDate, config.isPresent(),
                    config.map(ActivityDayConfig::dailyTotalCap).orElse(null),
                    config.map(ActivityDayConfig::version).orElse(null),
                    usedTotal, hours, now);
        });
    }

    @Override
    public ActivityDaySettlementResponse queryDaySettlement(String activityId, LocalDate utcDate) {
        return txTemplate.execute(status -> {
            requireActivity(activityId);
            long now = clock.millis();
            settleExpiredByActivity(activityId, now);

            LocalDate today = LocalDate.now(clock);
            if (utcDate.isBefore(today)) {
                // 已结算日：固化快照，后续配置变更不改写
                List<SettlementRow> rows = settlementRepository.findRows(activityId, utcDate);
                if (rows.size() < HOURS_PER_DAY) {
                    int[][] counts = countByHourAndStatus(activityId, utcDate);
                    for (int hour = 0; hour < HOURS_PER_DAY; hour++) {
                        settlementRepository.insertIfAbsent(activityId, utcDate, hour,
                                counts[hour][ReservationStatus.CONFIRMED.ordinal()],
                                counts[hour][ReservationStatus.CANCELLED.ordinal()],
                                counts[hour][ReservationStatus.EXPIRED.ordinal()],
                                now);
                    }
                    rows = settlementRepository.findRows(activityId, utcDate);
                }
                List<ActivityDaySettlementResponse.ActivityHourSettlement> hours =
                        new ArrayList<>(HOURS_PER_DAY);
                long settledAtUtc = 0L;
                for (SettlementRow row : rows) {
                    settledAtUtc = Math.max(settledAtUtc, row.settledAtUtc());
                    hours.add(new ActivityDaySettlementResponse.ActivityHourSettlement(
                            row.hourOfDay(), row.confirmedCount(), row.cancelledCount(),
                            row.expiredCount(), 0));
                }
                return new ActivityDaySettlementResponse(
                        activityId, utcDate, true, settledAtUtc, hours);
            }
            // 当日或未到的日：实时统计，不固化
            int[][] counts = countByHourAndStatus(activityId, utcDate);
            List<ActivityDaySettlementResponse.ActivityHourSettlement> hours =
                    new ArrayList<>(HOURS_PER_DAY);
            for (int hour = 0; hour < HOURS_PER_DAY; hour++) {
                hours.add(new ActivityDaySettlementResponse.ActivityHourSettlement(
                        hour,
                        counts[hour][ReservationStatus.CONFIRMED.ordinal()],
                        counts[hour][ReservationStatus.CANCELLED.ordinal()],
                        counts[hour][ReservationStatus.EXPIRED.ordinal()],
                        counts[hour][ReservationStatus.RESERVED.ordinal()]));
            }
            return new ActivityDaySettlementResponse(activityId, utcDate, false, null, hours);
        });
    }

    @Override
    public CampaignResponse migrateCampaignActivity(String campaignId, MigrateActivityRequest request) {
        String fingerprint = campaignId + "|" + String.valueOf(request.activityId());
        return runIdempotent(request.requestId(), Operation.MIGRATE_ACTIVITY, fingerprint,
                CampaignResponse.class, () -> {
                    Campaign campaign = campaignRepository.lockById(campaignId)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "campaign not found: " + campaignId));
                    if (request.activityId() != null) {
                        requireActivity(request.activityId());
                    }
                    campaignRepository.updateActivityId(campaignId, request.activityId());
                    Campaign updated = campaignRepository.lockById(campaignId).orElseThrow();
                    return CampaignResponse.from(updated);
                });
    }

    @Override
    public List<ReservationResponse> listReservations(String campaignId, LocalDate utcDate) {
        return txTemplate.execute(status -> {
            requireCampaign(campaignId);
            LocalDate date = utcDate != null ? utcDate : LocalDate.now(clock);
            return reservationRepository.listByCampaignAndDate(campaignId, date).stream()
                    .map(ReservationResponse::from)
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
        CREATE_ACTIVITY,
        UPSERT_DAY_CONFIG,
        MIGRATE_ACTIVITY
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
     * 确认同时结算公告、访客和活动三侧名额（三侧占用转为确认占用）；
     * 取消释放尚未确认的三侧名额，活动侧只释放预占固化的同一小时。
     *
     * @param isConfirm true=确认，false=取消
     */
    private ReservationResponse transition(String reservationId, boolean isConfirm) {
        long now = clock.millis();
        Reservation current = reservationRepository.lockById(reservationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "reservation not found: " + reservationId));

        // 仅结算本单（本事务已持其行锁，加锁顺序保持为 预占单 -> 活动小时账 -> 总账 -> 访客账，避免死锁）
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
                // 取消释放三级额度；固定顺序先活动小时账、再总账、后访客账
                releaseActivityHour(current);
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
     * 行锁查出后逐个 CAS 为 EXPIRED，仅 CAS 成功者释放三级额度，杜绝重复释放。
     */
    private void settleExpired(String campaignId, long now) {
        List<Reservation> expired = reservationRepository.lockExpiredReserved(campaignId, now);
        for (Reservation reservation : expired) {
            expireIfDue(reservation, now);
        }
    }

    /**
     * 结算某活动当前已到期但仍为 RESERVED 的预占，语义同 {@link #settleExpired}。
     */
    private void settleExpiredByActivity(String activityId, long now) {
        List<Reservation> expired = reservationRepository.lockExpiredReservedByActivity(activityId, now);
        for (Reservation reservation : expired) {
            expireIfDue(reservation, now);
        }
    }

    /**
     * 若传入预占单（调用方已持其行锁）已到期，则 CAS 转 EXPIRED 并释放三级额度；
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
                releaseActivityHour(reservation);
                ledgerRepository.releaseTotal(reservation.campaignId(), utcDate);
                ledgerRepository.releaseVisitor(
                        reservation.campaignId(), reservation.visitorId(), utcDate);
            }
        }
    }

    /**
     * 释放预占单固化的活动小时名额；无活动归属时不做任何变更。
     */
    private void releaseActivityHour(Reservation reservation) {
        if (reservation.activityId() != null) {
            hourLedgerRepository.release(
                    reservation.activityId(),
                    reservation.utcDate().toLocalDate(),
                    hourOfDayOf(reservation.hourStartUtc()));
        }
    }

    /**
     * 查找当前小时之后（同一 UTC 日，其次次日）首个预算未用完的小时起点；
     * 无可用小时返回 null。
     */
    private Long findNextAvailableHour(String activityId, LocalDate utcDate, int currentHour,
                                       ActivityDayConfig dayConfig) {
        Map<Integer, Integer> usedByHour = hourLedgerRepository.usedByHour(activityId, utcDate);
        for (int hour = currentHour + 1; hour < HOURS_PER_DAY; hour++) {
            if (dayConfig.hourlyBudgets().get(hour) > usedByHour.getOrDefault(hour, 0)) {
                return hourStartEpoch(utcDate, hour);
            }
        }
        LocalDate nextDay = utcDate.plusDays(1);
        var nextConfig = dayConfigRepository.findConfig(activityId, nextDay);
        if (nextConfig.isPresent()) {
            Map<Integer, Integer> usedNextDay = hourLedgerRepository.usedByHour(activityId, nextDay);
            for (int hour = 0; hour < HOURS_PER_DAY; hour++) {
                if (nextConfig.get().hourlyBudgets().get(hour) > usedNextDay.getOrDefault(hour, 0)) {
                    return hourStartEpoch(nextDay, hour);
                }
            }
        }
        return null;
    }

    /**
     * 校验 24 个小时预算：均为非负整数且合计等于日总确认额度；
     * 失败返回 400 并给出实际值、要求值与差额。
     */
    private void validateBudgets(Integer dailyTotalCap, List<Integer> hourlyBudgets) {
        if (dailyTotalCap == null || dailyTotalCap < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "dailyTotalCap must be a non-negative integer: actual=" + dailyTotalCap);
        }
        if (hourlyBudgets == null || hourlyBudgets.size() != HOURS_PER_DAY) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "hourlyBudgets must contain 24 entries: actual="
                            + (hourlyBudgets == null ? null : hourlyBudgets.size())
                            + " required=" + HOURS_PER_DAY);
        }
        long sum = 0L;
        for (int hour = 0; hour < HOURS_PER_DAY; hour++) {
            Integer budget = hourlyBudgets.get(hour);
            if (budget == null || budget < 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "hourlyBudgets[" + hour + "] must be a non-negative integer: actual=" + budget);
            }
            sum += budget;
        }
        if (sum != dailyTotalCap) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "hourly budget sum must equal daily total: actual=" + sum
                            + " required=" + dailyTotalCap + " diff=" + (sum - dailyTotalCap));
        }
    }

    /**
     * 已开始小时（hourStart &lt;= now，区间左闭右开）不可下调预算；
     * 违反时返回 409 并给出实际值与要求值。
     */
    private void assertStartedHoursNotDecreased(LocalDate utcDate, List<Integer> oldBudgets,
                                                List<Integer> newBudgets, long now) {
        for (int hour = 0; hour < HOURS_PER_DAY; hour++) {
            long hourStart = hourStartEpoch(utcDate, hour);
            if (hourStart <= now && newBudgets.get(hour) < oldBudgets.get(hour)) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "hour already started, budget cannot decrease: utcDate=" + utcDate
                                + " hour=" + hour + " hourStartUtc=" + hourStart
                                + " actual=" + newBudgets.get(hour)
                                + " required>=" + oldBudgets.get(hour));
            }
        }
    }

    /**
     * 统计活动某 UTC 日各小时各状态预占数；返回 [24][状态数] 计数表。
     */
    private int[][] countByHourAndStatus(String activityId, LocalDate utcDate) {
        int[][] counts = new int[HOURS_PER_DAY][ReservationStatus.values().length];
        for (ReservationRepository.ActivityHourStatus row
                : reservationRepository.listHourStatusByActivityAndDate(activityId, utcDate)) {
            counts[hourOfDayOf(row.hourStartUtc())][row.status().ordinal()]++;
        }
        return counts;
    }

    /** epoch 毫秒向下取整到 UTC 小时起点。 */
    private static long hourStartOf(long epochMillis) {
        return (epochMillis / HOUR_MILLIS) * HOUR_MILLIS;
    }

    /** 小时起点对应的 UTC 小时序号（0..23）。 */
    private static int hourOfDayOf(long hourStartUtc) {
        return (int) ((hourStartUtc / HOUR_MILLIS) % HOURS_PER_DAY);
    }

    /** 某 UTC 日某小时的小时起点（epoch 毫秒）。 */
    private static long hourStartEpoch(LocalDate utcDate, int hourOfDay) {
        return utcDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                + hourOfDay * HOUR_MILLIS;
    }

    private Campaign requireCampaign(String campaignId) {
        return campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "campaign not found: " + campaignId));
    }

    private Activity requireActivity(String activityId) {
        return activityRepository.findById(activityId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "activity not found: " + activityId));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }
}
