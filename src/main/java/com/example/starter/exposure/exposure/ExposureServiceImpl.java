package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.Activity;
import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.repo.ActivityHourLedgerRepository;
import com.example.starter.exposure.repo.ActivityRepository;
import com.example.starter.exposure.repo.CampaignRepository;
import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.exposure.repo.LedgerRepository;
import com.example.starter.exposure.repo.ReservationRepository;
import com.example.starter.exposure.web.ActivityHourDiagnosticsResponse;
import com.example.starter.exposure.web.ActivityResponse;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateActivityRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.DailyCloseResponse;
import com.example.starter.exposure.web.MigrateCampaignActivityRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpdateActivityRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 公告曝光频控与活动匀速投放业务服务实现。
 *
 * <p>所有写操作以 requestId 为全局幂等键：同键同参重放原成功结果，异参 409；
 * 业务失败随事务回滚，不占幂等键。所有操作与额度查询先结算相关过期预占，
 * 不依赖后台定时器。终态竞争由行锁 + 状态 CAS 保证只允许一个终态。</p>
 *
 * <p>活动匀速投放：公告归入活动后，申请在为公告/访客占额之前，先为当前 UTC 小时
 * 原子预占活动小时名额；小时预算为 0（未配置）或已耗尽时返回 429 并给出下一可用小时，
 * 公告与访客侧名额不消耗。确认在活动小时账目固化确认数，取消/过期释放同一小时占用并
 * 固化取消/过期数。账目加锁顺序固定为 活动小时账 -> 公告当日总账 -> 访客当日账。</p>
 */
@Service
public class ExposureServiceImpl implements ExposureService {

    static final int RESERVATION_TTL_MILLIS = 60_000;

    /** 并发同键竞争时等待胜出事务提交的最大时长。 */
    private static final long IDEMPOTENT_WAIT_MILLIS = 10_000L;

    /** 一小时的毫秒数；UTC 小时边界按 epoch 毫秒整除对齐。 */
    private static final long HOUR_MILLIS = 3_600_000L;

    /** 下一可用小时向后搜索的最大小时数（7 天）。 */
    private static final int NEXT_HOUR_SEARCH_LIMIT = 24 * 7;

    private final Clock clock;
    private final CampaignRepository campaignRepository;
    private final ReservationRepository reservationRepository;
    private final LedgerRepository ledgerRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ActivityRepository activityRepository;
    private final ActivityHourLedgerRepository activityHourLedgerRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public ExposureServiceImpl(Clock clock,
                               CampaignRepository campaignRepository,
                               ReservationRepository reservationRepository,
                               LedgerRepository ledgerRepository,
                               IdempotencyRepository idempotencyRepository,
                               ActivityRepository activityRepository,
                               ActivityHourLedgerRepository activityHourLedgerRepository,
                               ObjectMapper objectMapper,
                               TransactionTemplate txTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.activityRepository = activityRepository;
        this.activityHourLedgerRepository = activityHourLedgerRepository;
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
                            clock.millis(),
                            null);
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

                    // 公告归入活动时：先为当前 UTC 小时原子预占活动名额，
                    // 失败（未配置/耗尽）直接 429，公告与访客侧名额不消耗
                    String activityId = campaign.activityId();
                    Integer hourOfDay = null;
                    if (activityId != null) {
                        settleExpiredActivity(activityId, now);
                        hourOfDay = LocalTime.now(clock).getHour();
                        List<Integer> budgets = activityRepository.getBudgets(activityId);
                        int budget = budgets.get(hourOfDay);
                        activityHourLedgerRepository.ensureRow(activityId, utcDate, hourOfDay);
                        int usedHour = activityHourLedgerRepository.lockUsed(
                                activityId, utcDate, hourOfDay);
                        if (usedHour + 1 > budget) {
                            Long nextHour = nextAvailableHourStart(budgets, now);
                            if (budget <= 0) {
                                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                                        "activity hour budget not configured for activity "
                                                + activityId + " at hour " + hourOfDay
                                                + " (UTC); required=1, available=0",
                                        nextHour);
                            }
                            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                                    "activity hour budget exhausted for activity " + activityId
                                            + " at hour " + hourOfDay + " (UTC); required=1, available="
                                            + (budget - usedHour),
                                    nextHour);
                        }
                        activityHourLedgerRepository.addUsed(activityId, utcDate, hourOfDay);
                    }

                    // 固定加锁顺序：活动小时账 -> 公告当日总账 -> 访客当日账，避免死锁
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
                            null,
                            activityId,
                            hourOfDay);
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
        String fingerprint = request.activityId() + "|" + request.dailyTotalCap() + "|"
                + request.hourlyBudgets();
        return runIdempotent(request.requestId(), Operation.CREATE_ACTIVITY, fingerprint,
                ActivityResponse.class, () -> {
                    validateBudgets(request.dailyTotalCap(), request.hourlyBudgets());
                    if (activityRepository.findById(request.activityId()).isPresent()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "activity already exists: " + request.activityId());
                    }
                    Activity activity = new Activity(
                            request.activityId(),
                            request.dailyTotalCap(),
                            1,
                            clock.millis());
                    try {
                        activityRepository.insert(activity);
                    } catch (DuplicateKeyException duplicateActivity) {
                        // 并发创建同一 activityId：明确返回 409，而非误报幂等键冲突
                        throw new ApiException(HttpStatus.CONFLICT,
                                "activity already exists: " + request.activityId());
                    }
                    activityRepository.replaceBudgets(activity.activityId(), request.hourlyBudgets());
                    return ActivityResponse.from(activity, request.hourlyBudgets());
                });
    }

    @Override
    public ActivityResponse updateActivity(String activityId, UpdateActivityRequest request) {
        String fingerprint = activityId + "|" + request.expectedVersion() + "|"
                + request.dailyTotalCap() + "|" + request.hourlyBudgets();
        return runIdempotent(request.requestId(), Operation.UPDATE_ACTIVITY, fingerprint,
                ActivityResponse.class, () -> {
                    // 先校验完整最终状态，再在同一事务中提交
                    validateBudgets(request.dailyTotalCap(), request.hourlyBudgets());
                    Activity activity = activityRepository.lockById(activityId)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "activity not found: " + activityId));
                    if (activity.version() != request.expectedVersion()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "activity version mismatch for " + activityId + ": expected="
                                        + request.expectedVersion() + ", actual=" + activity.version());
                    }
                    // 已开始（UTC 当日当前及更早）的小时预算不可下调；历史账目不迁移
                    List<Integer> oldBudgets = activityRepository.getBudgets(activityId);
                    int currentHour = LocalTime.now(clock).getHour();
                    for (int hour = 0; hour <= currentHour; hour++) {
                        if (request.hourlyBudgets().get(hour) < oldBudgets.get(hour)) {
                            throw new ApiException(HttpStatus.CONFLICT,
                                    "cannot decrease budget of started hour " + hour
                                            + " (UTC) for activity " + activityId + ": old="
                                            + oldBudgets.get(hour) + ", new="
                                            + request.hourlyBudgets().get(hour));
                        }
                    }
                    if (!activityRepository.compareAndSetConfig(
                            activityId, request.expectedVersion(), request.dailyTotalCap())) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "activity version changed concurrently: " + activityId);
                    }
                    activityRepository.replaceBudgets(activityId, request.hourlyBudgets());
                    return ActivityResponse.from(
                            activityRepository.findById(activityId).orElseThrow(),
                            request.hourlyBudgets());
                });
    }

    @Override
    public CampaignResponse migrateCampaign(String campaignId, MigrateCampaignActivityRequest request) {
        String fingerprint = campaignId + "|" + request.activityId();
        return runIdempotent(request.requestId(), Operation.MIGRATE_CAMPAIGN, fingerprint,
                CampaignResponse.class, () -> {
                    campaignRepository.lockById(campaignId)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "campaign not found: " + campaignId));
                    if (request.activityId() != null
                            && activityRepository.findById(request.activityId()).isEmpty()) {
                        throw new ApiException(HttpStatus.NOT_FOUND,
                                "activity not found: " + request.activityId());
                    }
                    // 迁移只影响之后的申请；既有预占的活动与小时归属固化在预占单上，不改写
                    campaignRepository.updateActivityId(campaignId, request.activityId());
                    return CampaignResponse.from(campaignRepository.findById(campaignId).orElseThrow());
                });
    }

    @Override
    public ActivityResponse getActivity(String activityId) {
        return txTemplate.execute(status -> {
            Activity activity = activityRepository.findById(activityId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "activity not found: " + activityId));
            return ActivityResponse.from(activity, activityRepository.getBudgets(activityId));
        });
    }

    @Override
    public DailyCloseResponse getDailyClose(String activityId, LocalDate requestedDate) {
        return txTemplate.execute(status -> {
            requireActivity(activityId);
            LocalDate utcDate = requestedDate != null ? requestedDate : LocalDate.now(clock);
            List<ActivityHourLedgerRepository.HourLedgerEntry> entries =
                    activityHourLedgerRepository.findByActivityAndDate(activityId, utcDate);
            int[] confirmed = new int[24];
            int[] cancelled = new int[24];
            int[] expired = new int[24];
            for (ActivityHourLedgerRepository.HourLedgerEntry entry : entries) {
                confirmed[entry.hourOfDay()] = entry.confirmed();
                cancelled[entry.hourOfDay()] = entry.cancelled();
                expired[entry.hourOfDay()] = entry.expired();
            }
            List<DailyCloseResponse.HourClose> hours = new ArrayList<>(24);
            int confirmedTotal = 0;
            int cancelledTotal = 0;
            int expiredTotal = 0;
            for (int hour = 0; hour < 24; hour++) {
                hours.add(new DailyCloseResponse.HourClose(
                        hour, confirmed[hour], cancelled[hour], expired[hour]));
                confirmedTotal += confirmed[hour];
                cancelledTotal += cancelled[hour];
                expiredTotal += expired[hour];
            }
            return new DailyCloseResponse(activityId, utcDate, hours,
                    confirmedTotal, cancelledTotal, expiredTotal);
        });
    }

    @Override
    public ActivityHourDiagnosticsResponse getHourDiagnostics(String activityId, LocalDate requestedDate) {
        return txTemplate.execute(status -> {
            requireActivity(activityId);
            long now = clock.millis();
            LocalDate utcDate = requestedDate != null ? requestedDate : LocalDate.now(clock);
            List<Integer> budgets = activityRepository.getBudgets(activityId);
            // 只读诊断：由预占单实时统计，不结算过期、不改变任何状态
            List<Reservation> reservations =
                    reservationRepository.findByActivityAndDate(activityId, utcDate);
            int[] activeReserved = new int[24];
            int[] pendingExpired = new int[24];
            int[] confirmed = new int[24];
            int[] cancelled = new int[24];
            int[] expired = new int[24];
            for (Reservation reservation : reservations) {
                int hour = reservation.hourOfDay();
                switch (reservation.status()) {
                    case RESERVED -> {
                        if (now >= reservation.expiresAtUtc()) {
                            pendingExpired[hour]++;
                        } else {
                            activeReserved[hour]++;
                        }
                    }
                    case CONFIRMED -> confirmed[hour]++;
                    case CANCELLED -> cancelled[hour]++;
                    case EXPIRED -> expired[hour]++;
                }
            }
            List<ActivityHourDiagnosticsResponse.HourDiagnostics> hours = new ArrayList<>(24);
            for (int hour = 0; hour < 24; hour++) {
                hours.add(new ActivityHourDiagnosticsResponse.HourDiagnostics(
                        hour, budgets.get(hour), activeReserved[hour], pendingExpired[hour],
                        confirmed[hour], cancelled[hour], expired[hour]));
            }
            return new ActivityHourDiagnosticsResponse(activityId, utcDate, now, hours);
        });
    }

    @Override
    public List<ReservationResponse> getActivityReservations(String activityId, LocalDate requestedDate) {
        return txTemplate.execute(status -> {
            requireActivity(activityId);
            LocalDate utcDate = requestedDate != null ? requestedDate : LocalDate.now(clock);
            return reservationRepository.findByActivityAndDate(activityId, utcDate)
                    .stream()
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
        UPDATE_ACTIVITY,
        MIGRATE_CAMPAIGN
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
     * 确认在活动小时账目固化确认数；取消释放同一小时占用并固化取消数。
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
                // 确认同时结算三侧：活动侧固化确认数；公告/访客侧保持占用至当日结束
                if (current.activityId() != null) {
                    activityHourLedgerRepository.addConfirmed(current.activityId(),
                            current.utcDate().toLocalDate(), current.hourOfDay());
                }
            } else {
                if (!reservationRepository.compareAndSetStatus(
                        reservationId, ReservationStatus.RESERVED, ReservationStatus.CANCELLED, now)) {
                    throw new ApiException(HttpStatus.CONFLICT, "reservation state changed concurrently");
                }
                // 取消释放三侧额度；固定顺序先活动小时账，再总账后访客账
                releaseActivitySlot(current, false);
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
     * 行锁查出后逐个 CAS 为 EXPIRED，仅 CAS 成功者释放额度，杜绝重复释放。
     */
    private void settleExpired(String campaignId, long now) {
        List<Reservation> expired = reservationRepository.lockExpiredReserved(campaignId, now);
        for (Reservation reservation : expired) {
            expireIfDue(reservation, now);
        }
    }

    /**
     * 结算某活动下所有公告当前已到期但仍为 RESERVED 的预占；
     * 用于申请前释放活动小时名额，覆盖该活动下全部公告。
     */
    private void settleExpiredActivity(String activityId, long now) {
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
                releaseActivitySlot(reservation, true);
                ledgerRepository.releaseTotal(reservation.campaignId(), utcDate);
                ledgerRepository.releaseVisitor(
                        reservation.campaignId(), reservation.visitorId(), utcDate);
            }
        }
    }

    /**
     * 释放预占单固化的活动小时名额并固化终态计数。
     * 仅处理 RESERVED 转出的取消/过期；无活动归属的预占不涉及活动侧。
     *
     * @param expired true=过期（固化 expired），false=取消（固化 cancelled）
     */
    private void releaseActivitySlot(Reservation reservation, boolean expired) {
        if (reservation.activityId() == null) {
            return;
        }
        LocalDate utcDate = reservation.utcDate().toLocalDate();
        activityHourLedgerRepository.releaseUsed(
                reservation.activityId(), utcDate, reservation.hourOfDay());
        if (expired) {
            activityHourLedgerRepository.addExpired(
                    reservation.activityId(), utcDate, reservation.hourOfDay());
        } else {
            activityHourLedgerRepository.addCancelled(
                    reservation.activityId(), utcDate, reservation.hourOfDay());
        }
    }

    /**
     * 校验 24 个小时预算：恰好 24 个、均非负且合计等于日总确认额度；不满足返回 400。
     */
    private void validateBudgets(int dailyTotalCap, List<Integer> hourlyBudgets) {
        if (hourlyBudgets.size() != 24) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "hourlyBudgets must contain exactly 24 entries, actual=" + hourlyBudgets.size());
        }
        long sum = 0;
        for (int hour = 0; hour < hourlyBudgets.size(); hour++) {
            Integer budget = hourlyBudgets.get(hour);
            if (budget == null || budget < 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "hourlyBudgets[" + hour + "] must be a non-negative integer");
            }
            sum += budget;
        }
        if (sum != dailyTotalCap) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "hourly budgets sum " + sum + " must equal dailyTotalCap " + dailyTotalCap
                            + " (diff=" + (dailyTotalCap - sum) + ")");
        }
    }

    /**
     * 计算下一可用小时起始时刻（epoch 毫秒，UTC）：从当前小时之后逐小时查找
     * 第一个预算大于 0 的小时；7 天内无可用小时（日总额为 0）返回 null。
     */
    private Long nextAvailableHourStart(List<Integer> budgets, long now) {
        long currentHourStart = now - Math.floorMod(now, HOUR_MILLIS);
        for (int offset = 1; offset <= NEXT_HOUR_SEARCH_LIMIT; offset++) {
            long candidate = currentHourStart + offset * HOUR_MILLIS;
            int hourOfDay = (int) Math.floorMod(candidate / HOUR_MILLIS, 24);
            if (budgets.get(hourOfDay) > 0) {
                return candidate;
            }
        }
        return null;
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
