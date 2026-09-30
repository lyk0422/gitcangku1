package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.Activity;
import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.Pacing;
import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.repo.ActivityDailySnapshotRepository;
import com.example.starter.exposure.repo.ActivityHourBudgetFreezeRepository;
import com.example.starter.exposure.repo.ActivityHourLedgerRepository;
import com.example.starter.exposure.repo.ActivityRepository;
import com.example.starter.exposure.repo.CampaignRepository;
import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.exposure.repo.LedgerRepository;
import com.example.starter.exposure.repo.ReservationRepository;
import com.example.starter.exposure.web.ActivityResponse;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateActivityRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.DailySnapshotResponse;
import com.example.starter.exposure.web.HourDiagnosticResponse;
import com.example.starter.exposure.web.MigrateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationHistoryResponse;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpdateActivityRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 公告曝光频控与活动匀速投放业务服务实现。
 *
 * <p>所有写操作以 requestId 为全局幂等键：同键同参重放原成功结果，异参 409；
 * 业务失败随事务回滚，不占幂等键。所有操作与额度查询先结算相关过期预占，
 * 不依赖后台定时器。终态竞争由行锁 + 状态 CAS 保证只允许一个终态。</p>
 *
 * <p>归入活动的公告，申请在同一事务内按固定加锁顺序
 * （公告行 → 活动行 → 公告日总账 → 活动小时账 → 访客账）原子预占三侧名额，
 * 并发跨公告申请由活动小时账行锁保证不超卖；未归入活动的公告保留两级额度的历史行为。</p>
 */
@Service
public class ExposureServiceImpl implements ExposureService {

    static final int RESERVATION_TTL_MILLIS = 60_000;

    /** 某 UTC 日彻底终结的判定：次日 00:01 UTC 之后，当日最晚（23:59:59.x）创建的 60 秒预占也必然到期。 */
    private static final int FREEZE_GRACE_MINUTES = 1;

    /** 历史查询单次最大条数。 */
    private static final int HISTORY_MAX_LIMIT = 500;

    /** 并发同键竞争时等待胜出事务提交的最大时长。 */
    private static final long IDEMPOTENT_WAIT_MILLIS = 10_000L;

    private final Clock clock;
    private final CampaignRepository campaignRepository;
    private final ActivityRepository activityRepository;
    private final ActivityHourLedgerRepository activityHourLedgerRepository;
    private final ActivityHourBudgetFreezeRepository freezeRepository;
    private final ActivityDailySnapshotRepository snapshotRepository;
    private final ReservationRepository reservationRepository;
    private final LedgerRepository ledgerRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public ExposureServiceImpl(Clock clock,
                               CampaignRepository campaignRepository,
                               ActivityRepository activityRepository,
                               ActivityHourLedgerRepository activityHourLedgerRepository,
                               ActivityHourBudgetFreezeRepository freezeRepository,
                               ActivityDailySnapshotRepository snapshotRepository,
                               ReservationRepository reservationRepository,
                               LedgerRepository ledgerRepository,
                               IdempotencyRepository idempotencyRepository,
                               ObjectMapper objectMapper,
                               TransactionTemplate txTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.activityRepository = activityRepository;
        this.activityHourLedgerRepository = activityHourLedgerRepository;
        this.freezeRepository = freezeRepository;
        this.snapshotRepository = snapshotRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
    }

    @Override
    public CampaignResponse createCampaign(CreateCampaignRequest request) {
        String fingerprint = request.campaignId() + "|" + request.dailyTotalCap() + "|"
                + request.perVisitorDailyCap() + "|" + normalizeActivity(request.activityId());
        return runIdempotent(request.requestId(), Operation.CREATE_CAMPAIGN, fingerprint,
                CampaignResponse.class, () -> {
                    if (campaignRepository.findById(request.campaignId()).isPresent()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "campaign already exists: " + request.campaignId());
                    }
                    String activityId = normalizeActivity(request.activityId());
                    if (activityId != null) {
                        requireActivity(activityId);
                    }
                    Campaign campaign = new Campaign(
                            request.campaignId(),
                            activityId,
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
    public ActivityResponse createActivity(CreateActivityRequest request) {
        List<Integer> budgets = request.hourlyBudget();
        int sum;
        try {
            sum = Pacing.validateBudgets(budgets, request.dailyTotalCap());
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_HOURLY_BUDGET",
                    invalid.getMessage(), budgetSumDetails(budgets, request.dailyTotalCap()));
        }
        List<Integer> validated = new ArrayList<>(budgets);
        String fingerprint = request.activityId() + "|" + request.dailyTotalCap() + "|" + validated;
        return runIdempotent(request.requestId(), Operation.CREATE_ACTIVITY, fingerprint,
                ActivityResponse.class, () -> {
                    if (activityRepository.findById(request.activityId()).isPresent()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "activity already exists: " + request.activityId());
                    }
                    long now = clock.millis();
                    Activity activity = new Activity(
                            request.activityId(),
                            request.dailyTotalCap(),
                            validated.stream().mapToInt(Integer::intValue).toArray(),
                            0L,
                            now,
                            now);
                    try {
                        activityRepository.insert(activity);
                    } catch (DuplicateKeyException duplicate) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "activity already exists: " + request.activityId());
                    }
                    return ActivityResponse.from(activity);
                });
    }

    @Override
    public ActivityResponse updateActivity(String activityId, UpdateActivityRequest request) {
        List<Integer> budgets = request.hourlyBudget();
        try {
            Pacing.validateBudgets(budgets, request.dailyTotalCap());
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_HOURLY_BUDGET",
                    invalid.getMessage(), budgetSumDetails(budgets, request.dailyTotalCap()));
        }
        List<Integer> validated = new ArrayList<>(budgets);
        String fingerprint = activityId + "|" + request.dailyTotalCap() + "|" + validated
                + "|v" + request.expectedVersion();
        return runIdempotent(request.requestId(), Operation.UPDATE_ACTIVITY, fingerprint,
                ActivityResponse.class, () -> {
                    long now = clock.millis();
                    LocalDate today = LocalDate.now(clock);
                    int currentHour = Pacing.utcHourOf(now);

                    // 锁活动行，串行化同活动的配置修改与名额预占
                    Activity current = activityRepository.lockById(activityId)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "activity not found: " + activityId));

                    if (current.version() != request.expectedVersion()) {
                        Map<String, Object> details = new LinkedHashMap<>();
                        details.put("actualVersion", current.version());
                        details.put("requiredVersion", request.expectedVersion());
                        throw new ApiException(HttpStatus.CONFLICT, "ACTIVITY_VERSION_MISMATCH",
                                "activity version mismatch for " + activityId, details);
                    }

                    // 已开始小时（当天 h <= 当前小时）按当天“生效过的最高预算”冻结，禁止下调
                    int[] newBudget = validated.stream().mapToInt(Integer::intValue).toArray();
                    for (int h = 0; h <= currentHour; h++) {
                        freezeRepository.freezeMax(activityId, today, h, current.budget(h), now);
                        Integer frozen = freezeRepository.find(activityId, today, h);
                        int frozenBudget = frozen == null ? current.budget(h) : frozen;
                        if (newBudget[h] < frozenBudget) {
                            Map<String, Object> details = new LinkedHashMap<>();
                            details.put("utcDate", today.toString());
                            details.put("hour", h);
                            details.put("actualBudget", newBudget[h]);
                            details.put("requiredBudget", frozenBudget);
                            details.put("difference", frozenBudget - newBudget[h]);
                            throw new ApiException(HttpStatus.CONFLICT,
                                    "STARTED_HOUR_BUDGET_CANNOT_DECREASE",
                                    "budget of started UTC hour " + h + " cannot be decreased",
                                    details);
                        }
                    }

                    Activity updated = new Activity(
                            activityId,
                            request.dailyTotalCap(),
                            newBudget,
                            current.version() + 1,
                            current.createdAtUtc(),
                            now);
                    // 行锁 + 版本 CAS 双重保障：锁内二次版本校验，并发修改一方失败
                    if (!activityRepository.updateWithVersion(updated, request.expectedVersion())) {
                        Map<String, Object> details = new LinkedHashMap<>();
                        details.put("actualVersion", current.version());
                        details.put("requiredVersion", request.expectedVersion());
                        throw new ApiException(HttpStatus.CONFLICT, "ACTIVITY_VERSION_MISMATCH",
                                "activity version mismatch for " + activityId, details);
                    }
                    return ActivityResponse.from(updated);
                });
    }

    @Override
    public ActivityResponse getActivity(String activityId) {
        return ActivityResponse.from(requireActivity(activityId));
    }

    @Override
    public CampaignResponse migrateCampaign(String campaignId, MigrateCampaignRequest request) {
        String targetActivity = normalizeActivity(request.activityId());
        String fingerprint = campaignId + "->" + targetActivity;
        return runIdempotent(request.requestId(), Operation.MIGRATE, fingerprint,
                CampaignResponse.class, () -> {
                    // 固定加锁顺序：公告行 -> 活动行
                    Campaign campaign = campaignRepository.lockById(campaignId)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "campaign not found: " + campaignId));
                    if (targetActivity != null) {
                        requireActivity(targetActivity);
                    }
                    campaignRepository.updateActivity(campaignId, targetActivity);
                    Campaign updated = new Campaign(
                            campaign.campaignId(),
                            targetActivity,
                            campaign.dailyTotalCap(),
                            campaign.perVisitorDailyCap(),
                            campaign.createdAtUtc());
                    return CampaignResponse.from(updated);
                });
    }

    @Override
    public CampaignResponse getCampaign(String campaignId) {
        return CampaignResponse.from(requireCampaign(campaignId));
    }

    @Override
    public ReservationResponse apply(ApplyExposureRequest request) {
        String fingerprint = request.campaignId() + "|" + request.visitorId();
        return runIdempotent(request.requestId(), Operation.APPLY, fingerprint,
                ReservationResponse.class, () -> {
                    long now = clock.millis();
                    LocalDate utcDate = LocalDate.now(clock);
                    int hour = Pacing.utcHourOf(now);

                    // 先锁公告行：申请归属以公告当前活动列为准，与活动迁移按提交顺序裁决
                    Campaign campaign = campaignRepository.lockById(request.campaignId())
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "campaign not found: " + request.campaignId()));

                    Activity activity = null;
                    if (campaign.activityId() != null) {
                        activity = activityRepository.lockById(campaign.activityId())
                                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                                        "activity missing for campaign " + campaign.campaignId()));
                        // 结算该活动下所有公告已到期的预占，释放各侧名额后再判定当前小时容量
                        settleExpiredByActivity(activity.activityId(), now);
                    } else {
                        // 未归入活动：保留两级额度的历史行为
                        settleExpired(campaign.campaignId(), now);
                    }

                    // 固定加锁顺序：公告当日总账 -> 活动当小时账 -> 访客当日账，避免死锁
                    ledgerRepository.ensureTotalRow(campaign.campaignId(), utcDate);
                    int usedTotal = ledgerRepository.lockUsedTotal(campaign.campaignId(), utcDate);

                    Integer usedHour = null;
                    if (activity != null) {
                        activityHourLedgerRepository.ensureRow(activity.activityId(), utcDate, hour);
                        usedHour = activityHourLedgerRepository.lockUsed(
                                activity.activityId(), utcDate, hour);
                    }

                    ledgerRepository.ensureVisitorRow(
                            campaign.campaignId(), request.visitorId(), utcDate);
                    int usedVisitor = ledgerRepository.lockUsedVisitor(
                            campaign.campaignId(), request.visitorId(), utcDate);

                    // 先核对整批容量，任一不足整体回滚，三侧名额均不消耗
                    if (activity != null) {
                        int hourBudget = activity.budget(hour);
                        if (hourBudget == 0) {
                            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                                    "HOUR_BUDGET_NOT_CONFIGURED",
                                    "no hourly budget configured for UTC hour " + hour,
                                    hourExhaustedDetails(activity, utcDate, hour,
                                            usedHour, hourBudget, now));
                        }
                        if (usedHour + 1 > hourBudget) {
                            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                                    "HOUR_BUDGET_EXHAUSTED",
                                    "activity hour budget exhausted for hour " + hour,
                                    hourExhaustedDetails(activity, utcDate, hour,
                                            usedHour, hourBudget, now));
                        }
                    }
                    if (usedTotal + 1 > campaign.dailyTotalCap()) {
                        Map<String, Object> details = new LinkedHashMap<>();
                        details.put("actualUsed", usedTotal + 1);
                        details.put("requiredCap", campaign.dailyTotalCap());
                        details.put("difference", usedTotal + 1 - campaign.dailyTotalCap());
                        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                                "TOTAL_QUOTA_EXHAUSTED",
                                "exposure quota exhausted for campaign " + campaign.campaignId(),
                                details);
                    }
                    if (usedVisitor + 1 > campaign.perVisitorDailyCap()) {
                        Map<String, Object> details = new LinkedHashMap<>();
                        details.put("visitorId", request.visitorId());
                        details.put("actualUsed", usedVisitor + 1);
                        details.put("requiredCap", campaign.perVisitorDailyCap());
                        details.put("difference", usedVisitor + 1 - campaign.perVisitorDailyCap());
                        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                                "VISITOR_QUOTA_EXHAUSTED",
                                "visitor quota exhausted for campaign " + campaign.campaignId(),
                                details);
                    }

                    // 容量全部满足后一次写入三侧占用
                    ledgerRepository.addTotal(campaign.campaignId(), utcDate, 1);
                    if (activity != null) {
                        activityHourLedgerRepository.add(activity.activityId(), utcDate, hour);
                    }
                    ledgerRepository.addVisitor(
                            campaign.campaignId(), request.visitorId(), utcDate, 1);

                    String reservationId = UUID.randomUUID().toString().replace("-", "");
                    Reservation reservation = new Reservation(
                            reservationId,
                            campaign.campaignId(),
                            activity == null ? null : activity.activityId(),
                            request.visitorId(),
                            java.sql.Date.valueOf(utcDate),
                            activity == null ? null : hour,
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
    public HourDiagnosticResponse queryHourDiagnostic(String activityId, LocalDate requestedDate) {
        return txTemplate.execute(status -> {
            Activity activity = requireActivity(activityId);
            long now = clock.millis();
            LocalDate utcDate = requestedDate != null ? requestedDate : LocalDate.now(clock);

            // 结算后 used 才反映真实占用
            settleExpiredByActivity(activityId, now);

            Map<Integer, ReservationRepository.StatusCount> grouped = new LinkedHashMap<>();
            for (ReservationRepository.StatusCount sc
                    : reservationRepository.countGroupedByHourStatus(activityId, utcDate)) {
                grouped.put(sc.hour() * 100 + sc.status().ordinal(), sc);
            }
            List<HourDiagnosticResponse.HourStatus> hours = new ArrayList<>(24);
            for (int h = 0; h < 24; h++) {
                int reserved = countOf(grouped, h, ReservationStatus.RESERVED);
                int confirmed = countOf(grouped, h, ReservationStatus.CONFIRMED);
                int cancelled = countOf(grouped, h, ReservationStatus.CANCELLED);
                int expired = countOf(grouped, h, ReservationStatus.EXPIRED);
                int used = activityHourLedgerRepository.getUsed(activityId, utcDate, h);
                int budget = activity.budget(h);
                hours.add(new HourDiagnosticResponse.HourStatus(
                        h,
                        Pacing.hourStartEpochMillis(utcDate, h),
                        budget,
                        used,
                        reserved,
                        confirmed,
                        cancelled,
                        expired,
                        budget - used));
            }
            return new HourDiagnosticResponse(activityId, utcDate,
                    activity.dailyTotalCap(), now, hours);
        });
    }

    @Override
    public DailySnapshotResponse queryDailySnapshot(String activityId, LocalDate requestedDate) {
        return txTemplate.execute(status -> {
            long now = clock.millis();
            LocalDate utcDate = requestedDate != null ? requestedDate : LocalDate.now(clock);

            // 锁活动行，串行化并发日结固化；与申请/改配共用同一把活动行锁
            Activity activity = activityRepository.lockById(activityId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "activity not found: " + activityId));

            // 已固化日只读返回，配置变更与后续操作均不改写
            List<ActivityDailySnapshotRepository.HourCount> frozen =
                    snapshotRepository.findDay(activityId, utcDate);
            if (frozen.size() == 24) {
                return new DailySnapshotResponse(activityId, utcDate, true, now,
                        frozen.stream().map(hc -> new DailySnapshotResponse.HourCount(
                                hc.hour(), hc.confirmedCount(), hc.cancelledCount())).toList());
            }

            // 当日最晚预占在次日 00:00:59.x 前到期；次日 00:01 UTC 后才可固化
            long freezeThreshold = Pacing.hourStartEpochMillis(utcDate.plusDays(1), 0)
                    + FREEZE_GRACE_MINUTES * 60_000L;
            if (now < freezeThreshold) {
                // 当天或尚未彻底终结：返回实时值但不固化
                return new DailySnapshotResponse(activityId, utcDate, false, now,
                        liveHourCounts(activityId, utcDate));
            }

            // 固化前兜底结算该活动当日残留的到期预占（正常门槛下应已无 RESERVED）
            settleExpiredByActivity(activityId, now);

            for (int h = 0; h < 24; h++) {
                // 持活动行锁，并发固化被串行化，使用幂等插入双保险，绝不改写胜出行
                snapshotRepository.insertHourIfAbsent(activityId, utcDate, h,
                        reservationRepository.countByActivityDayHourStatus(
                                activityId, utcDate, h, ReservationStatus.CONFIRMED),
                        reservationRepository.countByActivityDayHourStatus(
                                activityId, utcDate, h, ReservationStatus.CANCELLED),
                        now);
            }
            List<ActivityDailySnapshotRepository.HourCount> saved =
                    snapshotRepository.findDay(activityId, utcDate);
            return new DailySnapshotResponse(activityId, utcDate, true, now,
                    saved.stream().map(hc -> new DailySnapshotResponse.HourCount(
                            hc.hour(), hc.confirmedCount(), hc.cancelledCount())).toList());
        });
    }

    @Override
    public ReservationHistoryResponse queryHistory(String campaignId, String visitorId,
                                                   LocalDate fromUtc, LocalDate toUtc,
                                                   Integer requestedLimit) {
        int limit = requestedLimit == null ? 100 : Math.min(requestedLimit, HISTORY_MAX_LIMIT);
        List<ReservationResponse> items = reservationRepository
                .findHistory(campaignId, visitorId, fromUtc, toUtc, limit)
                .stream().map(ReservationResponse::from).toList();
        return new ReservationHistoryResponse(items.size(), limit, items);
    }

    // ---- 内部辅助（作用域末尾） ----

    /** 幂等操作类型，同时标识存储响应的反序列化类型。 */
    private enum Operation {
        CREATE_CAMPAIGN,
        CREATE_ACTIVITY,
        UPDATE_ACTIVITY,
        MIGRATE,
        APPLY,
        CONFIRM,
        CANCEL
    }

    /** 下一可用小时视图；当天与次日均无正预算时所有字段为 null/0。 */
    private record NextAvailableHour(Integer hour, LocalDate utcDate,
                                     Long hourStartUtc, Long retryAfterMillis) {
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
                                    "IDEMPOTENCY_KEY_CONFLICT",
                                    "idempotency key reused with different parameters: " + requestId,
                                    Map.of("requestId", requestId));
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

        // 仅结算本单（本事务已持其行锁），到期则先转 EXPIRED 并释放三侧名额
        expireIfDue(current, now);
        current = reservationRepository.lockById(reservationId).orElseThrow();

        if (current.status() == ReservationStatus.RESERVED) {
            // 结算后仍为 RESERVED 说明 now < expiresAt，确认严格要求在到期时刻之前
            if (isConfirm) {
                if (!reservationRepository.compareAndSetStatus(
                        reservationId, ReservationStatus.RESERVED, ReservationStatus.CONFIRMED, now)) {
                    throw new ApiException(HttpStatus.CONFLICT, "reservation state changed concurrently");
                }
                // 确认：公告、访客、活动三侧名额均保持占用，不做增减
            } else {
                if (!reservationRepository.compareAndSetStatus(
                        reservationId, ReservationStatus.RESERVED, ReservationStatus.CANCELLED, now)) {
                    throw new ApiException(HttpStatus.CONFLICT, "reservation state changed concurrently");
                }
                // 取消：释放该尚未确认预占固化的三侧（公告/活动同小时/访客）名额
                releaseQuotas(current);
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
            // 重复同类终态操作：返回原状态，不重复释放/计数
        } else {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "unexpected reservation status: " + current.status());
        }

        Reservation result = reservationRepository.lockById(reservationId).orElseThrow();
        return ReservationResponse.from(result);
    }

    /**
     * 结算某公告当前已到期（now &gt;= expiresAt）但仍为 RESERVED 的预占：
     * 行锁查出后逐个 CAS 为 EXPIRED，仅 CAS 成功者释放三侧名额，杜绝重复释放。
     */
    private void settleExpired(String campaignId, long now) {
        List<Reservation> expired = reservationRepository.lockExpiredReserved(campaignId, now);
        for (Reservation reservation : expired) {
            expireIfDue(reservation, now);
        }
    }

    /**
     * 结算某活动（跨公告）当前已到期但仍为 RESERVED 的预占。
     * 调用方须已持活动行锁；各预占按统一顺序释放 公告总账 → 活动小时账 → 访客账。
     */
    private void settleExpiredByActivity(String activityId, long now) {
        List<Reservation> expired = reservationRepository.lockExpiredReservedByActivity(activityId, now);
        for (Reservation reservation : expired) {
            expireIfDue(reservation, now);
        }
    }

    /**
     * 若传入预占单（调用方已持其行锁）已到期，则 CAS 转 EXPIRED 并释放其固化的三侧名额；
     * 未到期或已非 RESERVED 则不做任何变更。历史预占（无活动）只释放两级名额。
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
                releaseQuotas(reservation);
            }
        }
    }

    /**
     * 释放一张尚未确认预占固化的名额：公告日总账 → 活动同小时账（若有）→ 访客日账。
     * 只能在 RESERVED → CANCELLED/EXPIRED 的 CAS 成功后调用一次；CHECK 约束保证不变负。
     */
    private void releaseQuotas(Reservation reservation) {
        LocalDate utcDate = reservation.utcDate().toLocalDate();
        ledgerRepository.releaseTotal(reservation.campaignId(), utcDate);
        if (reservation.activityId() != null && reservation.utcHour() != null) {
            activityHourLedgerRepository.release(
                    reservation.activityId(), utcDate, reservation.utcHour());
        }
        ledgerRepository.releaseVisitor(
                reservation.campaignId(), reservation.visitorId(), utcDate);
    }

    /** 小时额度不足/未配置时的诊断明细：实际值、要求值、差额与下一可用小时。 */
    private Map<String, Object> hourExhaustedDetails(Activity activity, LocalDate utcDate,
                                                     int hour, int usedHour,
                                                     int hourBudget, long now) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("activityId", activity.activityId());
        details.put("utcDate", utcDate.toString());
        details.put("currentHour", hour);
        details.put("actualUsed", usedHour);
        details.put("requiredBudget", hourBudget);
        details.put("difference", usedHour + 1 - hourBudget);
        NextAvailableHour next = nextAvailableHour(activity, utcDate, hour, now);
        if (next.hour() != null) {
            details.put("nextAvailableHour", next.hour());
            details.put("nextAvailableUtcDate", next.utcDate().toString());
            details.put("nextAvailableHourStartUtc", next.hourStartUtc());
            details.put("retryAfterMillis", next.retryAfterMillis());
        } else {
            details.put("nextAvailableHour", null);
        }
        return details;
    }

    /**
     * 计算从当前小时（不含）起第一个正预算小时；先查当天剩余小时，再查次日（配置按日重复）。
     * 全天预算为 0（日总额为 0）时返回空字段。
     */
    private NextAvailableHour nextAvailableHour(Activity activity, LocalDate utcDate,
                                                int currentHour, long now) {
        int[] budget = activity.hourlyBudget();
        int nextHour = Pacing.nextPositiveHour(budget, currentHour + 1);
        LocalDate nextDate = utcDate;
        if (nextHour < 0) {
            nextHour = Pacing.nextPositiveHour(budget, 0);
            nextDate = utcDate.plusDays(1);
        }
        if (nextHour < 0) {
            return new NextAvailableHour(null, null, null, null);
        }
        long start = Pacing.hourStartEpochMillis(nextDate, nextHour);
        return new NextAvailableHour(nextHour, nextDate, start, Math.max(0L, start - now));
    }

    /** 实时（未固化）24 小时确认/取消计数。 */
    private List<DailySnapshotResponse.HourCount> liveHourCounts(String activityId, LocalDate utcDate) {
        List<DailySnapshotResponse.HourCount> hours = new ArrayList<>(24);
        for (int h = 0; h < 24; h++) {
            hours.add(new DailySnapshotResponse.HourCount(
                    h,
                    reservationRepository.countByActivityDayHourStatus(
                            activityId, utcDate, h, ReservationStatus.CONFIRMED),
                    reservationRepository.countByActivityDayHourStatus(
                            activityId, utcDate, h, ReservationStatus.CANCELLED)));
        }
        return hours;
    }

    private int countOf(Map<Integer, ReservationRepository.StatusCount> grouped,
                        int hour, ReservationStatus status) {
        ReservationRepository.StatusCount sc = grouped.get(hour * 100 + status.ordinal());
        return sc == null ? 0 : sc.count();
    }

    private Map<String, Object> budgetSumDetails(List<Integer> budgets, int dailyTotalCap) {
        long actual = budgets == null ? 0 : budgets.stream().filter(v -> v != null).mapToLong(Integer::longValue).sum();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("actualSum", actual);
        details.put("requiredSum", dailyTotalCap);
        details.put("difference", actual - dailyTotalCap);
        return details;
    }

    private String normalizeActivity(String activityId) {
        return activityId == null || activityId.isBlank() ? null : activityId;
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
