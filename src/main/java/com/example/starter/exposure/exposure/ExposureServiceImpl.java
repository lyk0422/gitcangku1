package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.Activity;
import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.repo.ActivityLedgerRepository;
import com.example.starter.exposure.repo.ActivityRepository;
import com.example.starter.exposure.repo.CampaignRepository;
import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.exposure.repo.LedgerRepository;
import com.example.starter.exposure.repo.ReservationRepository;
import com.example.starter.exposure.web.ActivityHoursResponse;
import com.example.starter.exposure.web.ActivityResponse;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.AssignActivityRequest;
import com.example.starter.exposure.web.CampaignDetailResponse;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateActivityRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.DailySettlementResponse;
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
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * <p>公告归入活动后，申请须为当前 UTC 小时原子预占活动名额（先核对公告、访客、
 * 活动三侧最终状态再一次事务提交，任一侧不足都不留半成品）；确认同时结算三侧，
 * 取消/过期只释放尚未确认的同一小时预占。活动迁移只影响迁移后的新预占。</p>
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
    private final ActivityRepository activityRepository;
    private final ActivityLedgerRepository activityLedgerRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public ExposureServiceImpl(Clock clock,
                               CampaignRepository campaignRepository,
                               ReservationRepository reservationRepository,
                               LedgerRepository ledgerRepository,
                               IdempotencyRepository idempotencyRepository,
                               ActivityRepository activityRepository,
                               ActivityLedgerRepository activityLedgerRepository,
                               ObjectMapper objectMapper,
                               TransactionTemplate txTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.activityRepository = activityRepository;
        this.activityLedgerRepository = activityLedgerRepository;
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
    public ActivityResponse createActivity(CreateActivityRequest request) {
        String fingerprint = request.activityId() + "|" + request.dailyConfirmCap() + "|"
                + request.hourlyBudgets();
        return runIdempotent(request.requestId(), Operation.CREATE_ACTIVITY, fingerprint,
                ActivityResponse.class, () -> {
                    validateBudgetSum(request.dailyConfirmCap(), request.hourlyBudgets());
                    if (activityRepository.findById(request.activityId()).isPresent()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "activity already exists: " + request.activityId());
                    }
                    Activity activity = new Activity(
                            request.activityId(),
                            request.dailyConfirmCap(),
                            1L,
                            clock.millis());
                    try {
                        activityRepository.insert(activity);
                    } catch (DuplicateKeyException duplicateActivity) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "activity already exists: " + request.activityId());
                    }
                    if (request.hourlyBudgets() != null) {
                        activityRepository.replaceBudgets(activity.activityId(), request.hourlyBudgets());
                    }
                    return ActivityResponse.from(activity, request.hourlyBudgets());
                });
    }
    @Override
    public ActivityResponse updateActivity(String activityId, UpdateActivityRequest request) {
        String fingerprint = activityId + "|" + request.dailyConfirmCap() + "|"
                + request.hourlyBudgets() + "|" + request.expectedVersion();
        return runIdempotent(request.requestId(), Operation.UPDATE_ACTIVITY, fingerprint,
                ActivityResponse.class, () -> {
                    validateBudgetSum(request.dailyConfirmCap(), request.hourlyBudgets());
                    Activity current = activityRepository.lockById(activityId)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "activity not found: " + activityId));
                    if (current.version() != request.expectedVersion()) {
                        Map<String, Object> details = new LinkedHashMap<>();
                        details.put("reason", "ACTIVITY_VERSION_CONFLICT");
                        details.put("expectedVersion", request.expectedVersion());
                        details.put("actualVersion", current.version());
                        throw new ApiException(HttpStatus.CONFLICT,
                                "activity version conflict: expected=" + request.expectedVersion()
                                        + " actual=" + current.version(), details);
                    }
                    Integer[] oldBudgets = activityRepository.findBudgets(activityId);
                    int currentHour = ZonedDateTime.now(clock).getHour();
                    for (int hour = 0; hour <= currentHour; hour++) {
                        int oldBudget = oldBudgets[hour] == null ? 0 : oldBudgets[hour];
                        if (request.hourlyBudgets().get(hour) < oldBudget) {
                            Map<String, Object> details = new LinkedHashMap<>();
                            details.put("reason", "STARTED_HOUR_BUDGET_DECREASE");
                            details.put("hourOfDay", hour);
                            details.put("currentBudget", oldBudget);
                            details.put("requestedBudget", request.hourlyBudgets().get(hour));
                            throw new ApiException(HttpStatus.CONFLICT,
                                    "cannot decrease budget for started hour " + hour
                                            + ": current=" + oldBudget + " requested="
                                            + request.hourlyBudgets().get(hour), details);
                        }
                    }
                    if (!activityRepository.compareAndUpdateConfig(
                            activityId, request.dailyConfirmCap(), request.expectedVersion())) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "activity version conflict: expected=" + request.expectedVersion());
                    }
                    activityRepository.replaceBudgets(activityId, request.hourlyBudgets());
                    Activity updated = activityRepository.findById(activityId).orElseThrow();
                    return ActivityResponse.from(updated, request.hourlyBudgets());
                });
    }

    @Override
    public ActivityResponse getActivity(String activityId) {
        Activity activity = requireActivity(activityId);
        return ActivityResponse.from(activity, configuredBudgets(activityId));
    }

    @Override
    public CampaignDetailResponse assignCampaignActivity(String campaignId,
                                                         AssignActivityRequest request) {
        String fingerprint = campaignId + "|" + request.activityId();
        return runIdempotent(request.requestId(), Operation.ASSIGN_ACTIVITY, fingerprint,
                CampaignDetailResponse.class, () -> {
                    Campaign campaign = requireCampaign(campaignId);
                    if (request.activityId() != null) {
                        requireActivity(request.activityId());
                    }
                    campaignRepository.updateActivityId(campaignId, request.activityId());
                    return CampaignDetailResponse.from(
                            campaignRepository.findById(campaign.campaignId()).orElseThrow());
                });
    }

    @Override
    public CampaignDetailResponse getCampaign(String campaignId) {
        return CampaignDetailResponse.from(requireCampaign(campaignId));
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

                    // 固定加锁顺序：公告当日总账 -> 访客当日账 -> 活动小时账，避免死锁
                    ledgerRepository.ensureTotalRow(campaign.campaignId(), utcDate);
                    ledgerRepository.ensureVisitorRow(campaign.campaignId(), request.visitorId(), utcDate);
                    int usedTotal = ledgerRepository.lockUsedTotal(campaign.campaignId(), utcDate);
                    int usedVisitor = ledgerRepository.lockUsedVisitor(
                            campaign.campaignId(), request.visitorId(), utcDate);

                    // 先核对三侧最终状态：任一额度不足都 429 且三侧均不增加（尚未写入）
                    if (usedTotal + 1 > campaign.dailyTotalCap()) {
                        Map<String, Object> details = new LinkedHashMap<>();
                        details.put("reason", "CAMPAIGN_DAILY_CAP_EXHAUSTED");
                        details.put("dailyTotalCap", campaign.dailyTotalCap());
                        details.put("usedTotal", usedTotal);
                        details.put("requested", 1);
                        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                                "campaign daily quota exhausted for campaign "
                                        + campaign.campaignId(), details);
                    }
                    if (usedVisitor + 1 > campaign.perVisitorDailyCap()) {
                        Map<String, Object> details = new LinkedHashMap<>();
                        details.put("reason", "VISITOR_DAILY_CAP_EXHAUSTED");
                        details.put("perVisitorDailyCap", campaign.perVisitorDailyCap());
                        details.put("usedVisitor", usedVisitor);
                        details.put("requested", 1);
                        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                                "visitor daily quota exhausted for campaign "
                                        + campaign.campaignId(), details);
                    }

                    String activityId = campaign.activityId();
                    Integer hourOfDay = null;
                    if (activityId != null) {
                        hourOfDay = ZonedDateTime.now(clock).getHour();
                        reserveActivityHour(campaign, activityId, utcDate, hourOfDay, now);
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
                            hourOfDay,
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
    public List<ReservationResponse> listReservations(String campaignId, LocalDate utcDate) {
        return txTemplate.execute(status -> {
            requireCampaign(campaignId);
            settleExpired(campaignId, clock.millis());
            List<ReservationResponse> result = new ArrayList<>();
            for (Reservation reservation : reservationRepository.findByCampaign(campaignId, utcDate)) {
                result.add(ReservationResponse.from(reservation));
            }
            return result;
        });
    }

    @Override
    public ActivityHoursResponse queryActivityHours(String activityId, LocalDate requestedDate) {
        return txTemplate.execute(status -> {
            requireActivity(activityId);
            long now = clock.millis();
            LocalDate utcDate = requestedDate != null ? requestedDate : LocalDate.now(clock);
            settleExpiredByActivity(activityId, now);

            Integer[] budgets = activityRepository.findBudgets(activityId);
            boolean configured = isBudgetConfigured(budgets);
            List<ActivityLedgerRepository.HourLedger> ledgers =
                    activityLedgerRepository.fullDay(activityId, utcDate);
            List<ActivityHoursResponse.HourEntry> hours = new ArrayList<>(24);
            for (ActivityLedgerRepository.HourLedger ledger : ledgers) {
                Integer budget = configured ? budgets[ledger.hourOfDay()] : null;
                Integer remaining = budget == null
                        ? null : budget - ledger.reservedCount() - ledger.confirmedCount();
                hours.add(new ActivityHoursResponse.HourEntry(
                        ledger.hourOfDay(), budget, ledger.reservedCount(),
                        ledger.confirmedCount(), ledger.cancelledCount(), remaining));
            }
            return new ActivityHoursResponse(activityId, utcDate, hours, now);
        });
    }

    @Override
    public DailySettlementResponse queryDailySettlement(String activityId, LocalDate requestedDate) {
        return txTemplate.execute(status -> {
            requireActivity(activityId);
            long now = clock.millis();
            LocalDate utcDate = requestedDate != null ? requestedDate : LocalDate.now(clock);
            settleExpiredByActivity(activityId, now);

            List<ActivityLedgerRepository.HourLedger> ledgers =
                    activityLedgerRepository.fullDay(activityId, utcDate);
            List<DailySettlementResponse.HourSettlement> hours = new ArrayList<>(24);
            for (ActivityLedgerRepository.HourLedger ledger : ledgers) {
                hours.add(new DailySettlementResponse.HourSettlement(
                        ledger.hourOfDay(), ledger.confirmedCount(), ledger.cancelledCount()));
            }
            return new DailySettlementResponse(
                    activityId, utcDate, hours,
                    activityLedgerRepository.totalConfirmed(activityId, utcDate),
                    activityLedgerRepository.totalCancelled(activityId, utcDate),
                    now);
        });
    }
    // ---- 内部辅助（作用域末尾） ----

    /** 幂等操作类型，同时标识存储响应的反序列化类型。 */
    private enum Operation {
        CREATE_CAMPAIGN,
        CREATE_ACTIVITY,
        UPDATE_ACTIVITY,
        ASSIGN_ACTIVITY,
        APPLY,
        CONFIRM,
        CANCEL
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
     * 确认同时结算公告、访客（持续占用）与活动三侧名额；取消释放三侧名额。
     *
     * @param isConfirm true=确认，false=取消
     */
    private ReservationResponse transition(String reservationId, boolean isConfirm) {
        long now = clock.millis();
        Reservation current = reservationRepository.lockById(reservationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "reservation not found: " + reservationId));

        // 仅结算本单（本事务已持其行锁，加锁顺序保持为 预占单 -> 总账 -> 访客账 -> 活动小时账）
        expireIfDue(current, now);
        current = reservationRepository.lockById(reservationId).orElseThrow();

        if (current.status() == ReservationStatus.RESERVED) {
            // 结算后仍为 RESERVED 说明 now < expiresAt，确认严格要求在到期时刻之前
            if (isConfirm) {
                if (!reservationRepository.compareAndSetStatus(
                        reservationId, ReservationStatus.RESERVED, ReservationStatus.CONFIRMED, now)) {
                    throw new ApiException(HttpStatus.CONFLICT, "reservation state changed concurrently");
                }
                // 确认结算活动侧：同小时预占占用转确认
                if (current.activityId() != null) {
                    activityLedgerRepository.settleConfirm(current.activityId(),
                            current.utcDate().toLocalDate(), current.hourOfDay());
                }
            } else {
                if (!reservationRepository.compareAndSetStatus(
                        reservationId, ReservationStatus.RESERVED, ReservationStatus.CANCELLED, now)) {
                    throw new ApiException(HttpStatus.CONFLICT, "reservation state changed concurrently");
                }
                // 取消释放三侧额度；固定顺序先总账、访客账，后活动小时账
                ledgerRepository.releaseTotal(current.campaignId(), current.utcDate().toLocalDate());
                ledgerRepository.releaseVisitor(
                        current.campaignId(), current.visitorId(), current.utcDate().toLocalDate());
                releaseActivityHour(current);
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
            // 重复同类终态操作：返回原状态，不重复计数
        } else {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "unexpected reservation status: " + current.status());
        }

        Reservation result = reservationRepository.lockById(reservationId).orElseThrow();
        return ReservationResponse.from(result);
    }

    /**
     * 为当前小时原子预占活动名额。未配置小时预算或活动缺失直接失败；
     * 小时额度耗尽返回 429 并给出下一可用小时，公告与访客侧名额不消耗。
     */
    private void reserveActivityHour(Campaign campaign, String activityId, LocalDate utcDate,
                                     int hourOfDay, long now) {
        Activity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                        "activity not found for campaign " + campaign.campaignId()
                                + ": " + activityId));
        Integer[] budgets = activityRepository.findBudgets(activityId);
        if (!isBudgetConfigured(budgets)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reason", "ACTIVITY_HOURLY_BUDGET_NOT_CONFIGURED");
            details.put("activityId", activityId);
            throw new ApiException(HttpStatus.CONFLICT,
                    "activity hourly budget not configured: " + activityId, details);
        }
        activityLedgerRepository.ensureRow(activityId, utcDate, hourOfDay);
        if (activityLedgerRepository.tryReserveHour(activityId, utcDate, hourOfDay,
                budgets[hourOfDay])) {
            return;
        }
        int hourUsed = budgets[hourOfDay];
        ActivityLedgerRepository.HourLedger ledger =
                activityLedgerRepository.lockHour(activityId, utcDate, hourOfDay);
        if (ledger != null) {
            hourUsed = ledger.reservedCount() + ledger.confirmedCount();
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "ACTIVITY_HOUR_BUDGET_EXHAUSTED");
        details.put("activityId", activityId);
        details.put("utcDate", utcDate.toString());
        details.put("hourOfDay", hourOfDay);
        details.put("hourBudget", budgets[hourOfDay]);
        details.put("hourUsed", hourUsed);
        details.put("requested", 1);
        int[] next = nextAvailableHour(activityId, utcDate, hourOfDay, budgets);
        details.put("nextAvailableUtcDate",
                utcDate.plusDays(next[0]).toString());
        details.put("nextAvailableHourOfDay", next[1]);
        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                "activity hour budget exhausted for activity " + activityId
                        + " hour " + hourOfDay, details);
    }

    /**
     * 计算下一可用小时：先在当日当前小时之后找预算未用完的小时，
     * 当日没有则取次日第一个预算大于 0 的小时（预算按 UTC 日循环）。
     *
     * @return 长度为 2 的数组：[相对 utcDate 的偏移天数, 小时]
     */
    private int[] nextAvailableHour(String activityId, LocalDate utcDate, int currentHour,
                                    Integer[] budgets) {
        int[] used = activityLedgerRepository.usedByHour(activityId, utcDate);
        for (int hour = currentHour + 1; hour < 24; hour++) {
            if (budgets[hour] > used[hour]) {
                return new int[]{0, hour};
            }
        }
        for (int hour = 0; hour < 24; hour++) {
            if (budgets[hour] > 0) {
                return new int[]{1, hour};
            }
        }
        // 预算合计等于日总额（>=1），不会到达这里
        throw new IllegalStateException("no available hour for activity " + activityId);
    }

    /** 校验 24 小时预算均为非负整数且合计等于日总额。 */
    private void validateBudgetSum(int dailyConfirmCap, List<Integer> hourlyBudgets) {
        if (hourlyBudgets == null) {
            return;
        }
        long sum = 0;
        for (Integer budget : hourlyBudgets) {
            sum += budget;
        }
        if (sum != dailyConfirmCap) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reason", "HOURLY_BUDGET_SUM_MISMATCH");
            details.put("dailyConfirmCap", dailyConfirmCap);
            details.put("hourlyBudgetSum", sum);
            details.put("difference", sum - (long) dailyConfirmCap);
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "hourly budgets must sum to daily confirm cap: sum=" + sum
                            + " required=" + dailyConfirmCap, details);
        }
    }

    /** 24 小时预算是否已配置（24 行齐全）。 */
    private boolean isBudgetConfigured(Integer[] budgets) {
        for (Integer budget : budgets) {
            if (budget == null) {
                return false;
            }
        }
        return true;
    }

    /** 读取已配置的 24 小时预算列表；未配置返回 null。 */
    private List<Integer> configuredBudgets(String activityId) {
        Integer[] budgets = activityRepository.findBudgets(activityId);
        if (!isBudgetConfigured(budgets)) {
            return null;
        }
        return List.of(budgets);
    }

    /** 释放尚未确认的同小时活动预占（取消与过期共用）。 */
    private void releaseActivityHour(Reservation reservation) {
        if (reservation.activityId() != null) {
            activityLedgerRepository.releaseReserved(reservation.activityId(),
                    reservation.utcDate().toLocalDate(), reservation.hourOfDay());
        }
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
     * 结算某活动当前已到期但仍为 RESERVED 的预占（跨该活动下全部公告）。
     */
    private void settleExpiredByActivity(String activityId, long now) {
        List<Reservation> expired = reservationRepository.lockExpiredReservedByActivity(
                activityId, now);
        for (Reservation reservation : expired) {
            expireIfDue(reservation, now);
        }
    }

    /**
     * 若传入预占单（调用方已持其行锁）已到期，则 CAS 转 EXPIRED 并释放三侧额度；
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
                releaseActivityHour(reservation);
            }
        }
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
