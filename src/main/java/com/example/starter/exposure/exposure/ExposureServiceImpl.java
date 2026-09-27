package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.Activity;
import com.example.starter.exposure.domain.ActivityDayConfig;
import com.example.starter.exposure.domain.ActivityHourLedger;
import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.repo.ActivityBudgetRepository;
import com.example.starter.exposure.repo.ActivityLedgerRepository;
import com.example.starter.exposure.repo.ActivityRepository;
import com.example.starter.exposure.repo.ActivitySnapshotRepository;
import com.example.starter.exposure.repo.ActivitySnapshotRepository.SnapshotHourRow;
import com.example.starter.exposure.repo.CampaignRepository;
import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.exposure.repo.LedgerRepository;
import com.example.starter.exposure.repo.ReservationRepository;
import com.example.starter.exposure.web.ActivityBudgetResponse;
import com.example.starter.exposure.web.ActivityDiagnosticsResponse;
import com.example.starter.exposure.web.ActivityHourDiagnostic;
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
import com.example.starter.exposure.web.SettlementHour;
import com.example.starter.exposure.web.SettlementResponse;
import com.example.starter.exposure.web.UpdateActivityBudgetRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 公告曝光频控与活动匀速投放业务服务实现。
 *
 * <p>所有写操作以 requestId 为全局幂等键：同键同参重放原成功结果，异参 409；
 * 业务失败随事务回滚，不占幂等键。所有操作与额度查询先结算相关过期预占，
 * 不依赖后台定时器。终态竞争由行锁 + 状态 CAS 保证只允许一个终态。</p>
 *
 * <p>活动匀速投放：公告归入活动后，申请须先为当前 UTC 小时原子预占活动小时名额，
 * 再占用公告/访客两级额度；确认把活动侧 reserved 转 confirmed，取消/过期把
 * reserved 转 cancelled/expired 并释放公告与访客额度，已确认名额永不释放。
 * 加锁顺序固定为：幂等记录 → 活动行 → 公告行 → 预占单行 → 活动小时账目 →
 * 公告总额账目 → 访客账目，避免死锁。</p>
 */
@Service
public class ExposureServiceImpl implements ExposureService {

    static final int RESERVATION_TTL_MILLIS = 60_000;

    /** 并发同键竞争时等待胜出事务提交的最大时长。 */
    private static final long IDEMPOTENT_WAIT_MILLIS = 10_000L;

    /** 小时预算耗尽时向后搜索下一可用小时的最大天数。 */
    private static final int NEXT_HOUR_SEARCH_DAYS = 7;

    private final Clock clock;
    private final CampaignRepository campaignRepository;
    private final ReservationRepository reservationRepository;
    private final LedgerRepository ledgerRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ActivityRepository activityRepository;
    private final ActivityBudgetRepository activityBudgetRepository;
    private final ActivityLedgerRepository activityLedgerRepository;
    private final ActivitySnapshotRepository activitySnapshotRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public ExposureServiceImpl(Clock clock,
                               CampaignRepository campaignRepository,
                               ReservationRepository reservationRepository,
                               LedgerRepository ledgerRepository,
                               IdempotencyRepository idempotencyRepository,
                               ActivityRepository activityRepository,
                               ActivityBudgetRepository activityBudgetRepository,
                               ActivityLedgerRepository activityLedgerRepository,
                               ActivitySnapshotRepository activitySnapshotRepository,
                               ObjectMapper objectMapper,
                               TransactionTemplate txTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.activityRepository = activityRepository;
        this.activityBudgetRepository = activityBudgetRepository;
        this.activityLedgerRepository = activityLedgerRepository;
        this.activitySnapshotRepository = activitySnapshotRepository;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
    }

    // ---- 公告与曝光预占 ----

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
                            null,
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
    public CampaignResponse getCampaign(String campaignId) {
        return txTemplate.execute(status -> CampaignResponse.from(requireCampaign(campaignId)));
    }

    @Override
    public ReservationResponse apply(ApplyExposureRequest request) {
        String fingerprint = request.campaignId() + "|" + request.visitorId();
        return runIdempotent(request.requestId(), Operation.APPLY, fingerprint,
                ReservationResponse.class, () -> {
                    long now = clock.millis();
                    LocalDate utcDate = LocalDate.now(clock);
                    int hourOfDay = LocalTime.now(clock).getHour();
                    // 行锁读取公告：与活动迁移按提交顺序串行裁决
                    Campaign campaign = campaignRepository.lockById(request.campaignId())
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "campaign not found: " + request.campaignId()));

                    // 先结算该公告相关过期预占并释放额度
                    settleExpired(campaign.campaignId(), now);

                    // 公告归入活动时：先为当前 UTC 小时原子预占活动名额，
                    // 小时额度耗尽或未配置预算时直接失败，不触碰公告/访客两侧额度
                    String activityId = campaign.activityId();
                    Integer reservedHour = null;
                    if (activityId != null) {
                        reserveActivityHour(activityId, utcDate, hourOfDay, now);
                        reservedHour = hourOfDay;
                    }

                    // 固定加锁顺序：公告当日总账 -> 访客当日账，避免死锁
                    ledgerRepository.ensureTotalRow(campaign.campaignId(), utcDate);
                    ledgerRepository.ensureVisitorRow(campaign.campaignId(), request.visitorId(), utcDate);
                    int usedTotal = ledgerRepository.lockUsedTotal(campaign.campaignId(), utcDate);
                    int usedVisitor = ledgerRepository.lockUsedVisitor(
                            campaign.campaignId(), request.visitorId(), utcDate);

                    // 任一额度已满则 429，三侧额度均不增加（活动侧占用随事务回滚）
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
                            activityId,
                            reservedHour,
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

    // ---- 活动与匀速投放配置 ----

    @Override
    public ActivityResponse createActivity(CreateActivityRequest request) {
        return runIdempotent(request.requestId(), Operation.CREATE_ACTIVITY, request.activityId(),
                ActivityResponse.class, () -> {
                    if (activityRepository.findById(request.activityId()).isPresent()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "activity already exists: " + request.activityId());
                    }
                    Activity activity = new Activity(request.activityId(), 0, clock.millis());
                    try {
                        activityRepository.insert(activity);
                    } catch (DuplicateKeyException duplicateActivity) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "activity already exists: " + request.activityId());
                    }
                    return ActivityResponse.from(activity);
                });
    }

    @Override
    public ActivityResponse getActivity(String activityId) {
        return txTemplate.execute(status -> ActivityResponse.from(requireActivity(activityId)));
    }

    @Override
    public ActivityBudgetResponse updateBudget(String activityId, UpdateActivityBudgetRequest request) {
        String fingerprint = activityId + "|" + request.utcDate() + "|" + request.dailyTotal()
                + "|" + request.hourBudgets() + "|" + request.expectedVersion();
        return runIdempotent(request.requestId(), Operation.UPDATE_BUDGET, fingerprint,
                ActivityBudgetResponse.class, () -> {
                    long now = clock.millis();
                    Activity activity = activityRepository.lockById(activityId)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "activity not found: " + activityId));

                    // 先校验完整最终状态，全部通过后才在一个事务内提交
                    if (request.hourBudgets().size() != 24) {
                        Map<String, Object> details = new LinkedHashMap<>();
                        details.put("requiredSize", 24);
                        details.put("actualSize", request.hourBudgets().size());
                        throw new ApiException(HttpStatus.BAD_REQUEST,
                                "hour budgets must contain exactly 24 entries, got "
                                        + request.hourBudgets().size(),
                                details);
                    }
                    for (int hour = 0; hour < 24; hour++) {
                        Integer budget = request.hourBudgets().get(hour);
                        if (budget == null || budget < 0) {
                            Map<String, Object> details = new LinkedHashMap<>();
                            details.put("hourOfDay", hour);
                            details.put("budget", budget);
                            throw new ApiException(HttpStatus.BAD_REQUEST,
                                    "hour budget must be a non-negative integer at hour " + hour,
                                    details);
                        }
                    }
                    int sum = request.hourBudgets().stream().mapToInt(Integer::intValue).sum();
                    if (sum != request.dailyTotal()) {
                        Map<String, Object> details = new LinkedHashMap<>();
                        details.put("requiredDailyTotal", request.dailyTotal());
                        details.put("actualHourBudgetSum", sum);
                        details.put("difference", sum - request.dailyTotal());
                        throw new ApiException(HttpStatus.BAD_REQUEST,
                                "hour budgets sum " + sum + " does not equal daily total "
                                        + request.dailyTotal(),
                                details);
                    }
                    if (activity.version() != request.expectedVersion()) {
                        Map<String, Object> details = new LinkedHashMap<>();
                        details.put("expectedVersion", request.expectedVersion());
                        details.put("currentVersion", activity.version());
                        throw new ApiException(HttpStatus.CONFLICT,
                                "activity config version mismatch: expected "
                                        + request.expectedVersion() + ", current " + activity.version(),
                                details);
                    }
                    rejectStartedHourDecrease(activityId, request);

                    activityBudgetRepository.replaceConfig(activityId, request.utcDate(),
                            request.dailyTotal(), request.hourBudgets(), now);
                    activityRepository.incrementVersion(activityId);
                    return new ActivityBudgetResponse(activityId, request.utcDate(),
                            request.dailyTotal(), List.copyOf(request.hourBudgets()),
                            activity.version() + 1, now);
                });
    }

    @Override
    public ActivityBudgetResponse getBudget(String activityId, LocalDate utcDate) {
        return txTemplate.execute(status -> {
            Activity activity = requireActivity(activityId);
            ActivityDayConfig config = activityBudgetRepository.findConfig(activityId, utcDate)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "activity budget not configured for " + activityId + " on " + utcDate));
            List<Integer> budgets = activityBudgetRepository.findHourBudgets(activityId, utcDate);
            return new ActivityBudgetResponse(activityId, utcDate, config.dailyTotal(),
                    budgets, activity.version(), config.updatedAtUtc());
        });
    }

    @Override
    public CampaignResponse migrateCampaignActivity(String campaignId, MigrateActivityRequest request) {
        String fingerprint = campaignId + "|" + (request.activityId() == null ? "" : request.activityId());
        return runIdempotent(request.requestId(), Operation.MIGRATE_ACTIVITY, fingerprint,
                CampaignResponse.class, () -> {
                    // 行锁公告：与申请按提交顺序串行裁决；既有预占的活动归属已固化不改写
                    campaignRepository.lockById(campaignId)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "campaign not found: " + campaignId));
                    if (request.activityId() != null) {
                        requireActivity(request.activityId());
                    }
                    campaignRepository.updateActivity(campaignId, request.activityId());
                    return CampaignResponse.from(campaignRepository.findById(campaignId).orElseThrow());
                });
    }

    // ---- 活动查询：诊断、日结、历史（除日结固化快照外均为纯读取） ----

    @Override
    public ActivityDiagnosticsResponse getHourDiagnostics(String activityId, LocalDate utcDate) {
        return txTemplate.execute(status -> {
            requireActivity(activityId);
            ActivityDayConfig config = activityBudgetRepository.findConfig(activityId, utcDate)
                    .orElse(null);
            List<Integer> budgets = config != null
                    ? activityBudgetRepository.findHourBudgets(activityId, utcDate) : List.of();
            Map<Integer, ActivityHourLedger> ledgers = new LinkedHashMap<>();
            for (ActivityHourLedger ledger : activityLedgerRepository.listHours(activityId, utcDate)) {
                ledgers.put(ledger.hourOfDay(), ledger);
            }
            List<ActivityHourDiagnostic> hours = new ArrayList<>(24);
            for (int hour = 0; hour < 24; hour++) {
                ActivityHourLedger ledger = ledgers.get(hour);
                int reserved = ledger != null ? ledger.reservedCount() : 0;
                int confirmed = ledger != null ? ledger.confirmedCount() : 0;
                int cancelled = ledger != null ? ledger.cancelledCount() : 0;
                int expired = ledger != null ? ledger.expiredCount() : 0;
                Integer budget = config != null ? budgets.get(hour) : null;
                Integer remaining = budget != null ? budget - reserved - confirmed : null;
                hours.add(new ActivityHourDiagnostic(hour, budget, reserved, confirmed,
                        cancelled, expired, remaining));
            }
            return new ActivityDiagnosticsResponse(activityId, utcDate, config != null,
                    config != null ? config.dailyTotal() : null, hours, clock.millis());
        });
    }

    @Override
    public SettlementResponse getSettlement(String activityId, LocalDate utcDate) {
        return txTemplate.execute(status -> {
            activityRepository.lockById(activityId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "activity not found: " + activityId));
            long now = clock.millis();

            List<SnapshotHourRow> existing =
                    activitySnapshotRepository.findByActivityAndDate(activityId, utcDate);
            if (existing.size() == 24) {
                return toSettlementResponse(activityId, utcDate, existing);
            }

            // 该 UTC 日结束且全部预占必然到达终态（当日最后一刻 + TTL）后才可日结
            long settleableAfterUtc = dayStartMillis(utcDate.plusDays(1)) + RESERVATION_TTL_MILLIS;
            if (now < settleableAfterUtc) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("utcDate", utcDate.toString());
                details.put("settleableAfterUtc", settleableAfterUtc);
                details.put("nowUtc", now);
                throw new ApiException(HttpStatus.CONFLICT,
                        "day " + utcDate + " is not final yet; settlement available after "
                                + settleableAfterUtc,
                        details);
            }

            // 日结前结算该活动当日全部已到期预占（同一事务，三侧释放）
            for (Reservation reservation : reservationRepository
                    .lockExpiredReservedForActivityDay(activityId, utcDate, now)) {
                expireIfDue(reservation, now);
            }

            Map<Integer, ActivityHourLedger> ledgers = new LinkedHashMap<>();
            for (ActivityHourLedger ledger : activityLedgerRepository.listHours(activityId, utcDate)) {
                ledgers.put(ledger.hourOfDay(), ledger);
            }
            for (int hour = 0; hour < 24; hour++) {
                ActivityHourLedger ledger = ledgers.get(hour);
                activitySnapshotRepository.insertIfAbsent(activityId, utcDate, hour,
                        ledger != null ? ledger.confirmedCount() : 0,
                        ledger != null ? ledger.cancelledCount() : 0,
                        ledger != null ? ledger.expiredCount() : 0,
                        now);
            }
            return toSettlementResponse(activityId, utcDate,
                    activitySnapshotRepository.findByActivityAndDate(activityId, utcDate));
        });
    }

    @Override
    public List<ReservationResponse> listActivityReservations(String activityId, LocalDate utcDate,
                                                              Integer hourOfDay) {
        return txTemplate.execute(status -> {
            requireActivity(activityId);
            return reservationRepository.listByActivityAndDay(activityId, utcDate, hourOfDay)
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
        UPDATE_BUDGET,
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
     * 为当前 UTC 小时原子预占活动名额。未配置当日预算返回 409；
     * 小时额度耗尽返回 429 并给出下一可用小时，且未触碰公告/访客两侧额度。
     */
    private void reserveActivityHour(String activityId, LocalDate utcDate, int hourOfDay, long now) {
        ActivityDayConfig config = activityBudgetRepository.findConfig(activityId, utcDate)
                .orElseThrow(() -> {
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("activityId", activityId);
                    details.put("utcDate", utcDate.toString());
                    return new ApiException(HttpStatus.CONFLICT,
                            "activity hour budget not configured for " + activityId + " on " + utcDate,
                            details);
                });
        List<Integer> budgets = activityBudgetRepository.findHourBudgets(activityId, utcDate);
        if (budgets.size() != 24) {
            throw new IllegalStateException("corrupt hour budgets for " + activityId + " " + utcDate);
        }

        // 先结算该活动当前小时已到期预占，避免陈旧占用挤占预算
        for (Reservation reservation : reservationRepository
                .lockExpiredReservedForActivityHour(activityId, utcDate, hourOfDay, now)) {
            expireIfDue(reservation, now);
        }

        activityLedgerRepository.ensureHourRow(activityId, utcDate, hourOfDay);
        ActivityHourLedger ledger = activityLedgerRepository.lockHour(activityId, utcDate, hourOfDay)
                .orElseThrow();
        int budget = budgets.get(hourOfDay);
        if (ledger.occupied() + 1 > budget) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("activityId", activityId);
            details.put("utcDate", utcDate.toString());
            details.put("hourOfDay", hourOfDay);
            details.put("hourBudget", budget);
            details.put("hourOccupied", ledger.occupied());
            details.put("nextAvailableHour",
                    findNextAvailableHour(activityId, utcDate, hourOfDay));
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "activity hour budget exhausted for " + activityId + " on " + utcDate
                            + " hour " + hourOfDay,
                    details);
        }
        activityLedgerRepository.addReserved(activityId, utcDate, hourOfDay);
    }

    /**
     * 从当前小时之后向后搜索第一个仍有剩余名额的小时（当日剩余小时，再向后至多
     * {@value #NEXT_HOUR_SEARCH_DAYS} 天内已配置的日）；无可用小时返回 null。
     */
    private Map<String, Object> findNextAvailableHour(String activityId, LocalDate utcDate,
                                                      int hourOfDay) {
        for (int offset = 0; offset <= NEXT_HOUR_SEARCH_DAYS; offset++) {
            LocalDate date = utcDate.plusDays(offset);
            List<Integer> budgets = activityBudgetRepository.findHourBudgets(activityId, date);
            if (budgets.size() != 24) {
                continue;
            }
            Map<Integer, ActivityHourLedger> ledgers = new LinkedHashMap<>();
            for (ActivityHourLedger ledger : activityLedgerRepository.listHours(activityId, date)) {
                ledgers.put(ledger.hourOfDay(), ledger);
            }
            int startHour = offset == 0 ? hourOfDay + 1 : 0;
            for (int hour = startHour; hour < 24; hour++) {
                ActivityHourLedger ledger = ledgers.get(hour);
                int occupied = ledger != null ? ledger.occupied() : 0;
                if (budgets.get(hour) - occupied > 0) {
                    Map<String, Object> next = new LinkedHashMap<>();
                    next.put("utcDate", date.toString());
                    next.put("hourOfDay", hour);
                    next.put("hourStartUtc", dayStartMillis(date) + hour * 3_600_000L);
                    next.put("remaining", budgets.get(hour) - occupied);
                    return next;
                }
            }
        }
        return null;
    }

    /**
     * 已开始小时（过去日的全部小时、当日不晚于当前小时）的预算不可下调；
     * 违反时返回 409 并给出具体小时、原预算与目标预算。
     */
    private void rejectStartedHourDecrease(String activityId, UpdateActivityBudgetRequest request) {
        List<Integer> oldBudgets = activityBudgetRepository.findHourBudgets(activityId, request.utcDate());
        if (oldBudgets.size() != 24) {
            return;
        }
        LocalDate today = LocalDate.now(clock);
        int currentHour = LocalTime.now(clock).getHour();
        for (int hour = 0; hour < 24; hour++) {
            boolean started = request.utcDate().isBefore(today)
                    || (request.utcDate().equals(today) && hour <= currentHour);
            if (started && request.hourBudgets().get(hour) < oldBudgets.get(hour)) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("utcDate", request.utcDate().toString());
                details.put("hourOfDay", hour);
                details.put("oldBudget", oldBudgets.get(hour));
                details.put("newBudget", request.hourBudgets().get(hour));
                throw new ApiException(HttpStatus.CONFLICT,
                        "cannot decrease budget of started hour " + hour + " on "
                                + request.utcDate() + " from " + oldBudgets.get(hour) + " to "
                                + request.hourBudgets().get(hour),
                        details);
            }
        }
    }

    /**
     * 确认/取消状态机。调用前已持幂等键；行锁 + CAS 保证并发只有一个终态。
     * 确认同时结算活动侧名额（reserved 转 confirmed）；取消释放活动与公告、访客三侧名额。
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
                if (current.activityId() != null) {
                    activityLedgerRepository.settleConfirm(current.activityId(),
                            current.utcDate().toLocalDate(), current.hourOfDay());
                }
            } else {
                if (!reservationRepository.compareAndSetStatus(
                        reservationId, ReservationStatus.RESERVED, ReservationStatus.CANCELLED, now)) {
                    throw new ApiException(HttpStatus.CONFLICT, "reservation state changed concurrently");
                }
                // 取消释放三侧额度；固定顺序先活动小时账，再总账后访客账
                if (current.activityId() != null) {
                    activityLedgerRepository.settleCancel(current.activityId(),
                            current.utcDate().toLocalDate(), current.hourOfDay());
                }
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
     * 行锁查出后逐个 CAS 为 EXPIRED，仅 CAS 成功者释放三侧额度，杜绝重复释放。
     */
    private void settleExpired(String campaignId, long now) {
        List<Reservation> expired = reservationRepository.lockExpiredReserved(campaignId, now);
        for (Reservation reservation : expired) {
            expireIfDue(reservation, now);
        }
    }

    /**
     * 若传入预占单（调用方已持其行锁）已到期，则 CAS 转 EXPIRED 并释放活动、公告、
     * 访客三侧额度；未到期或已非 RESERVED 则不做任何变更。
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
                if (reservation.activityId() != null) {
                    activityLedgerRepository.settleExpire(
                            reservation.activityId(), utcDate, reservation.hourOfDay());
                }
                ledgerRepository.releaseTotal(reservation.campaignId(), utcDate);
                ledgerRepository.releaseVisitor(
                        reservation.campaignId(), reservation.visitorId(), utcDate);
            }
        }
    }

    private SettlementResponse toSettlementResponse(String activityId, LocalDate utcDate,
                                                    List<SnapshotHourRow> rows) {
        List<SettlementHour> hours = new ArrayList<>(rows.size());
        long snapshottedAtUtc = 0L;
        for (SnapshotHourRow row : rows) {
            hours.add(new SettlementHour(row.hourOfDay(), row.confirmedCount(),
                    row.cancelledCount(), row.expiredCount()));
            snapshottedAtUtc = row.snapshottedAtUtc();
        }
        return new SettlementResponse(activityId, utcDate, hours, snapshottedAtUtc);
    }

    private static long dayStartMillis(LocalDate utcDate) {
        return utcDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
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
