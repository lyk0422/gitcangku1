package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.Activity;
import com.example.starter.exposure.domain.ActivityDayConfig;
import com.example.starter.exposure.domain.ActivityHourLedger;
import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.CampaignActivity;
import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.repo.ActivityConfigRepository;
import com.example.starter.exposure.repo.ActivityHourLedgerRepository;
import com.example.starter.exposure.repo.ActivityRepository;
import com.example.starter.exposure.repo.CampaignActivityRepository;
import com.example.starter.exposure.repo.CampaignRepository;
import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.exposure.repo.LedgerRepository;
import com.example.starter.exposure.repo.ReservationRepository;
import com.example.starter.exposure.web.ActivityDayCloseResponse;
import com.example.starter.exposure.web.ActivityDayConfigResponse;
import com.example.starter.exposure.web.ActivityDiagnosticsResponse;
import com.example.starter.exposure.web.ActivityResponse;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignActivityResponse;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.ConfigHistoryResponse;
import com.example.starter.exposure.web.ConfigureDayBudgetRequest;
import com.example.starter.exposure.web.CreateActivityRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.MigrateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 公告曝光频控与活动匀速投放业务实现。
 *
 * <p>所有写操作以 requestId 为全局幂等键：同键同参重放原成功结果，异参 409；
 * 业务失败随事务回滚，不占幂等键。所有操作与额度查询先结算相关过期预占，
 * 不依赖后台定时器。终态竞争由行锁 + 状态 CAS 保证只允许一个终态。</p>
 *
 * <p>公告归入活动后，申请须为当前 UTC 小时原子预占活动名额；确认同时结算公告、
 * 访客、活动三侧；取消/过期只释放尚未确认的同一小时预占。加锁顺序固定为
 * 预占单/公告行 -&gt; 公告总账 -&gt; 访客账 -&gt; 活动小时账，避免死锁。</p>
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
    private final ActivityConfigRepository activityConfigRepository;
    private final CampaignActivityRepository campaignActivityRepository;
    private final ActivityHourLedgerRepository activityHourLedgerRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public ExposureServiceImpl(Clock clock,
                               CampaignRepository campaignRepository,
                               ReservationRepository reservationRepository,
                               LedgerRepository ledgerRepository,
                               IdempotencyRepository idempotencyRepository,
                               ActivityRepository activityRepository,
                               ActivityConfigRepository activityConfigRepository,
                               CampaignActivityRepository campaignActivityRepository,
                               ActivityHourLedgerRepository activityHourLedgerRepository,
                               ObjectMapper objectMapper,
                               TransactionTemplate txTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.activityRepository = activityRepository;
        this.activityConfigRepository = activityConfigRepository;
        this.campaignActivityRepository = campaignActivityRepository;
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
                        throw new ApiException(HttpStatus.CONFLICT,
                                "activity already exists: " + request.activityId());
                    }
                    return ActivityResponse.from(activity);
                });
    }
    @Override
    public ReservationResponse apply(ApplyExposureRequest request) {
        String fingerprint = request.campaignId() + "|" + request.visitorId();
        return runIdempotent(request.requestId(), Operation.APPLY, fingerprint,
                ReservationResponse.class, () -> {
                    long now = clock.millis();
                    LocalDate utcDate = LocalDate.now(clock);
                    int hourOfDay = LocalTime.now(clock).getHour();
                    Campaign campaign = requireCampaign(request.campaignId());

                    // 先结算该公告相关过期预占并释放额度
                    settleExpired(campaign.campaignId(), now);

                    // 申请时固化活动与小时归属；未归入活动则仅校验公告/访客两级额度
                    String activityId = campaignActivityRepository
                            .findByCampaign(campaign.campaignId())
                            .map(CampaignActivity::activityId)
                            .orElse(null);
                    ActivityDayConfig config = null;
                    if (activityId != null) {
                        config = activityConfigRepository.findConfig(activityId, utcDate)
                                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                                        "activity hourly budget not configured: activity="
                                                + activityId + " utcDate=" + utcDate));
                    }

                    // 固定加锁顺序：公告当日总账 -> 访客当日账 -> 活动小时账，避免死锁
                    ledgerRepository.ensureTotalRow(campaign.campaignId(), utcDate);
                    ledgerRepository.ensureVisitorRow(campaign.campaignId(), request.visitorId(), utcDate);
                    int usedTotal = ledgerRepository.lockUsedTotal(campaign.campaignId(), utcDate);
                    int usedVisitor = ledgerRepository.lockUsedVisitor(
                            campaign.campaignId(), request.visitorId(), utcDate);
                    int usedHour = 0;
                    if (activityId != null) {
                        activityHourLedgerRepository.ensureRow(activityId, utcDate, hourOfDay);
                        usedHour = activityHourLedgerRepository.lockUsed(activityId, utcDate, hourOfDay);
                    }

                    // 先核对整批最终状态，全部通过才写入；任一失败事务回滚不留半成品
                    if (usedTotal + 1 > campaign.dailyTotalCap()
                            || usedVisitor + 1 > campaign.perVisitorDailyCap()) {
                        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                                "exposure quota exhausted for campaign " + campaign.campaignId());
                    }
                    if (activityId != null) {
                        int budget = config.hourlyBudgets().get(hourOfDay);
                        if (usedHour + 1 > budget) {
                            Integer nextHour = nextAvailableHour(activityId, utcDate, hourOfDay, config);
                            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                                    "activity hour budget exhausted: activity=" + activityId
                                            + " utcDate=" + utcDate + " hour=" + hourOfDay
                                            + " used=" + usedHour + " budget=" + budget,
                                    nextHour);
                        }
                    }
                    ledgerRepository.addTotal(campaign.campaignId(), utcDate, 1);
                    ledgerRepository.addVisitor(campaign.campaignId(), request.visitorId(), utcDate, 1);
                    if (activityId != null) {
                        activityHourLedgerRepository.addUsed(activityId, utcDate, hourOfDay);
                    }

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
                            activityId == null ? null : hourOfDay);
                    reservationRepository.insert(reservation);
                    return ReservationResponse.from(reservation);
                });
    }

    @Override
    public ActivityDayConfigResponse configureDayBudget(String activityId, LocalDate utcDate,
                                                        ConfigureDayBudgetRequest request) {
        String fingerprint = activityId + "|" + utcDate + "|" + request.expectedVersion() + "|"
                + request.dailyTotalCap() + "|" + request.hourlyBudgets();
        return runIdempotent(request.requestId(), Operation.CONFIGURE_BUDGET, fingerprint,
                ActivityDayConfigResponse.class, () -> {
                    long now = clock.millis();
                    LocalDate today = LocalDate.now(clock);
                    // 行锁活动行，串行化同一活动的配置修改
                    activityRepository.lockById(activityId)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "activity not found: " + activityId));
                    if (utcDate.isBefore(today)) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "settled day config cannot be modified: utcDate=" + utcDate
                                        + " today=" + today);
                    }

                    // 整批校验：24 个小时预算合计必须等于日总额
                    int sum = request.hourlyBudgets().stream().mapToInt(Integer::intValue).sum();
                    if (sum != request.dailyTotalCap()) {
                        throw new ApiException(HttpStatus.BAD_REQUEST,
                                "hourly budgets sum mismatch: actual=" + sum
                                        + " required=" + request.dailyTotalCap());
                    }

                    ActivityDayConfig existing = activityConfigRepository
                            .findConfig(activityId, utcDate).orElse(null);
                    int currentVersion = existing == null ? 0 : existing.version();
                    if (currentVersion != request.expectedVersion()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "config version conflict: expected=" + request.expectedVersion()
                                        + " actual=" + currentVersion);
                    }

                    // 已开始小时（含当前小时）不可下调；历史预占与确认记录不迁移
                    if (existing != null && utcDate.equals(today)) {
                        int currentHour = LocalTime.now(clock).getHour();
                        for (int hour = 0; hour <= currentHour; hour++) {
                            int oldBudget = existing.hourlyBudgets().get(hour);
                            int newBudget = request.hourlyBudgets().get(hour);
                            if (newBudget < oldBudget) {
                                throw new ApiException(HttpStatus.CONFLICT,
                                        "started hour budget cannot be decreased: hour=" + hour
                                                + " actual=" + oldBudget + " required>=" + oldBudget
                                                + " given=" + newBudget);
                            }
                        }
                    }

                    int newVersion = currentVersion + 1;
                    if (existing == null) {
                        activityConfigRepository.insertConfig(
                                activityId, utcDate, request.dailyTotalCap(), newVersion, now);
                    } else {
                        activityConfigRepository.updateConfig(
                                activityId, utcDate, request.dailyTotalCap(), newVersion, now);
                    }
                    activityConfigRepository.replaceBudgets(activityId, utcDate, request.hourlyBudgets());
                    activityConfigRepository.insertHistory(new ActivityConfigRepository.ConfigHistoryEntry(
                            activityId, utcDate, newVersion, request.dailyTotalCap(),
                            request.hourlyBudgets(), request.requestId(), now));

                    return new ActivityDayConfigResponse(activityId, utcDate,
                            request.dailyTotalCap(), request.hourlyBudgets(), newVersion, now);
                });
    }

    @Override
    public CampaignActivityResponse migrateCampaign(String campaignId, MigrateCampaignRequest request) {
        String fingerprint = campaignId + "|" + request.activityId();
        return runIdempotent(request.requestId(), Operation.MIGRATE_CAMPAIGN, fingerprint,
                CampaignActivityResponse.class, () -> {
                    long now = clock.millis();
                    // 行锁公告行，迁移按提交顺序裁决
                    campaignRepository.lockById(campaignId)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "campaign not found: " + campaignId));
                    if (activityRepository.findById(request.activityId()).isEmpty()) {
                        throw new ApiException(HttpStatus.NOT_FOUND,
                                "activity not found: " + request.activityId());
                    }
                    var existing = campaignActivityRepository.findByCampaign(campaignId);
                    String previous = existing.map(CampaignActivity::activityId).orElse(null);
                    CampaignActivity mapping = new CampaignActivity(campaignId, request.activityId(), now);
                    if (existing.isPresent()) {
                        campaignActivityRepository.update(mapping);
                    } else {
                        campaignActivityRepository.insert(mapping);
                    }
                    return new CampaignActivityResponse(
                            campaignId, request.activityId(), previous, now);
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
    public ActivityDayCloseResponse queryDayClose(String activityId, LocalDate utcDate) {
        return txTemplate.execute(status -> {
            requireActivity(activityId);
            settleExpiredForActivity(activityId, clock.millis());

            ActivityDayConfig config = activityConfigRepository.findConfig(activityId, utcDate)
                    .orElse(null);
            Map<Integer, ActivityHourLedger> ledgerByHour = ledgerByHour(activityId, utcDate);

            List<ActivityDayCloseResponse.HourClose> hours = new java.util.ArrayList<>(24);
            for (int hour = 0; hour < 24; hour++) {
                ActivityHourLedger ledger = ledgerByHour.get(hour);
                hours.add(new ActivityDayCloseResponse.HourClose(
                        hour,
                        config == null ? null : config.hourlyBudgets().get(hour),
                        ledger == null ? 0 : ledger.confirmed(),
                        ledger == null ? 0 : ledger.cancelled()));
            }
            return new ActivityDayCloseResponse(activityId, utcDate,
                    config == null ? null : config.dailyTotalCap(),
                    config == null ? null : config.version(),
                    hours);
        });
    }

    @Override
    public ActivityDiagnosticsResponse queryDiagnostics(String activityId, LocalDate utcDate) {
        return txTemplate.execute(status -> {
            requireActivity(activityId);
            long now = clock.millis();
            settleExpiredForActivity(activityId, now);

            ActivityDayConfig config = activityConfigRepository.findConfig(activityId, utcDate)
                    .orElse(null);
            Map<Integer, ActivityHourLedger> ledgerByHour = ledgerByHour(activityId, utcDate);

            List<ActivityDiagnosticsResponse.HourDiagnostics> hours = new java.util.ArrayList<>(24);
            for (int hour = 0; hour < 24; hour++) {
                ActivityHourLedger ledger = ledgerByHour.get(hour);
                Integer budget = config == null ? null : config.hourlyBudgets().get(hour);
                int used = ledger == null ? 0 : ledger.used();
                hours.add(new ActivityDiagnosticsResponse.HourDiagnostics(
                        hour,
                        budget,
                        used,
                        budget == null ? null : budget - used,
                        ledger == null ? 0 : ledger.confirmed(),
                        ledger == null ? 0 : ledger.cancelled()));
            }
            return new ActivityDiagnosticsResponse(activityId, utcDate,
                    config == null ? null : config.version(), hours, now);
        });
    }

    @Override
    public ConfigHistoryResponse queryConfigHistory(String activityId, LocalDate utcDate) {
        return txTemplate.execute(status -> {
            requireActivity(activityId);
            List<ConfigHistoryResponse.Entry> entries = activityConfigRepository
                    .listHistory(activityId, utcDate)
                    .stream()
                    .map(e -> new ConfigHistoryResponse.Entry(e.version(), e.dailyTotalCap(),
                            e.hourlyBudgets(), e.requestId(), e.changedAtUtc()))
                    .toList();
            return new ConfigHistoryResponse(activityId, utcDate, entries);
        });
    }

    @Override
    public List<ReservationResponse> listReservations(String campaignId, LocalDate utcDate,
                                                      String status) {
        return txTemplate.execute(txStatus -> {
            requireCampaign(campaignId);
            settleExpired(campaignId, clock.millis());
            ReservationStatus filter = null;
            if (status != null && !status.isBlank()) {
                try {
                    filter = ReservationStatus.valueOf(status.trim().toUpperCase());
                } catch (IllegalArgumentException unknown) {
                    throw new ApiException(HttpStatus.BAD_REQUEST,
                            "unknown reservation status: " + status);
                }
            }
            return reservationRepository.listByCampaignAndDate(campaignId, utcDate, filter)
                    .stream()
                    .map(ReservationResponse::from)
                    .toList();
        });
    }
    // ---- 内部辅助（作用域末尾） ----

    /** 幂等操作类型，同时标识存储响应的反序列化类型。 */
    private enum Operation {
        CREATE_CAMPAIGN,
        CREATE_ACTIVITY,
        CONFIGURE_BUDGET,
        MIGRATE_CAMPAIGN,
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
     * 确认同时结算公告、访客、活动三侧名额；取消只释放尚未确认的同一小时预占。
     *
     * @param isConfirm true=确认，false=取消
     */
    private ReservationResponse transition(String reservationId, boolean isConfirm) {
        long now = clock.millis();
        Reservation current = reservationRepository.lockById(reservationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "reservation not found: " + reservationId));

        // 仅结算本单（本事务已持其行锁，加锁顺序保持为 预占单 -> 总账 -> 访客账 -> 活动小时账，避免死锁）
        expireIfDue(current, now);
        current = reservationRepository.lockById(reservationId).orElseThrow();

        if (current.status() == ReservationStatus.RESERVED) {
            // 结算后仍为 RESERVED 说明 now < expiresAt，确认严格要求在到期时刻之前
            if (isConfirm) {
                if (!reservationRepository.compareAndSetStatus(
                        reservationId, ReservationStatus.RESERVED, ReservationStatus.CONFIRMED, now)) {
                    throw new ApiException(HttpStatus.CONFLICT, "reservation state changed concurrently");
                }
                // 确认：活动侧该小时确认数快照 +1，占用不变
                if (current.activityId() != null) {
                    activityHourLedgerRepository.markConfirmed(current.activityId(),
                            current.utcDate().toLocalDate(), current.hourOfDay());
                }
            } else {
                if (!reservationRepository.compareAndSetStatus(
                        reservationId, ReservationStatus.RESERVED, ReservationStatus.CANCELLED, now)) {
                    throw new ApiException(HttpStatus.CONFLICT, "reservation state changed concurrently");
                }
                // 取消释放三级额度；固定顺序先总账、访客账、后活动小时账
                ledgerRepository.releaseTotal(current.campaignId(), current.utcDate().toLocalDate());
                ledgerRepository.releaseVisitor(
                        current.campaignId(), current.visitorId(), current.utcDate().toLocalDate());
                if (current.activityId() != null) {
                    activityHourLedgerRepository.markCancelled(current.activityId(),
                            current.utcDate().toLocalDate(), current.hourOfDay());
                }
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
            // 重复同类终态操作：返回原状态，不重复计数、不重复释放额度
        } else {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "unexpected reservation status: " + current.status());
        }

        Reservation result = reservationRepository.lockById(reservationId).orElseThrow();
        return ReservationResponse.from(result);
    }

    /**
     * 结算某公告当前已到期（now &gt;= expiresAt）但仍为 RESERVED 的预占单：
     * 行锁查出后逐个 CAS 为 EXPIRED，仅 CAS 成功者释放额度，杜绝重复释放。
     */
    private void settleExpired(String campaignId, long now) {
        List<Reservation> expired = reservationRepository.lockExpiredReserved(campaignId, now);
        for (Reservation reservation : expired) {
            expireIfDue(reservation, now);
        }
    }

    /**
     * 结算某活动当前已到期但仍为 RESERVED 的预占单，供日结/诊断查询固化快照。
     */
    private void settleExpiredForActivity(String activityId, long now) {
        List<Reservation> expired = reservationRepository.lockExpiredReservedByActivity(activityId, now);
        for (Reservation reservation : expired) {
            Reservation locked = reservationRepository.lockById(reservation.reservationId()).orElseThrow();
            expireIfDue(locked, now);
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
                ledgerRepository.releaseTotal(reservation.campaignId(), utcDate);
                ledgerRepository.releaseVisitor(
                        reservation.campaignId(), reservation.visitorId(), utcDate);
                if (reservation.activityId() != null) {
                    activityHourLedgerRepository.markCancelled(
                            reservation.activityId(), utcDate, reservation.hourOfDay());
                }
            }
        }
    }

    /**
     * 计算当前小时之后同一 UTC 日内第一个仍有剩余预算的小时；无则返回 null。
     * 区间左闭右开：小时 h 覆盖 [h:00, h+1:00)。
     */
    private Integer nextAvailableHour(String activityId, LocalDate utcDate, int currentHour,
                                      ActivityDayConfig config) {
        Map<Integer, ActivityHourLedger> ledgerByHour = ledgerByHour(activityId, utcDate);
        for (int hour = currentHour + 1; hour < 24; hour++) {
            int budget = config.hourlyBudgets().get(hour);
            ActivityHourLedger ledger = ledgerByHour.get(hour);
            int used = ledger == null ? 0 : ledger.used();
            if (used < budget) {
                return hour;
            }
        }
        return null;
    }

    /** 读取活动某日全部小时账本并按小时索引。 */
    private Map<Integer, ActivityHourLedger> ledgerByHour(String activityId, LocalDate utcDate) {
        Map<Integer, ActivityHourLedger> byHour = new HashMap<>();
        for (ActivityHourLedger ledger : activityHourLedgerRepository.listByDay(activityId, utcDate)) {
            byHour.put(ledger.hourOfDay(), ledger);
        }
        return byHour;
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