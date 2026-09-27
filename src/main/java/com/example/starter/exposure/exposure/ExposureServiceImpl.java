package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.Activity;
import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.pacing.Pacing;
import com.example.starter.exposure.repo.ActivityHourLedgerRepository;
import com.example.starter.exposure.repo.ActivityHourLedgerRepository.HourLedger;
import com.example.starter.exposure.repo.ActivityHourSnapshotRepository;
import com.example.starter.exposure.repo.ActivityHourSnapshotRepository.HourSnapshot;
import com.example.starter.exposure.repo.ActivityRepository;
import com.example.starter.exposure.repo.CampaignRepository;
import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.exposure.repo.LedgerRepository;
import com.example.starter.exposure.repo.ReservationRepository;
import com.example.starter.exposure.web.ActivityDayResponse;
import com.example.starter.exposure.web.ActivityResponse;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateActivityRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.DailySettlementResponse;
import com.example.starter.exposure.web.MigrateCampaignRequest;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
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
 * 业务失败随事务回滚，不占幂等键。申请预占在一个事务内按固定加锁顺序
 * （公告行 → 活动行 → 相关到期预占单行 → 活动小时账 → 公告日账 → 访客日账）
 * 完成过期结算与三侧原子预占，任一失败整体回滚、不留半成品。
 * 终态竞争由行锁 + 状态 CAS 保证只允许一个终态，重复终态不重复计数。</p>
 */
@Service
public class ExposureServiceImpl implements ExposureService {

    static final int RESERVATION_TTL_MILLIS = 60_000;

    /** 并发同键竞争时等待胜出事务提交的最大时长。 */
    private static final long IDEMPOTENT_WAIT_MILLIS = 10_000L;

    /** 历史查询最大返回条数。 */
    private static final int MAX_HISTORY_LIMIT = 200;

    // ---- 可机器区分的失败原因码 ----
    static final String REASON_ACTIVITY_ALREADY_EXISTS = "ACTIVITY_ALREADY_EXISTS";
    static final String REASON_CAMPAIGN_ALREADY_EXISTS = "CAMPAIGN_ALREADY_EXISTS";
    static final String REASON_CAMPAIGN_ACTIVITY_NOT_CONFIGURED = "CAMPAIGN_ACTIVITY_NOT_CONFIGURED";
    static final String REASON_ACTIVITY_HOUR_BUDGET_EXHAUSTED = "ACTIVITY_HOUR_BUDGET_EXHAUSTED";
    static final String REASON_CAMPAIGN_DAILY_QUOTA_EXHAUSTED = "CAMPAIGN_DAILY_QUOTA_EXHAUSTED";
    static final String REASON_VISITOR_DAILY_QUOTA_EXHAUSTED = "VISITOR_DAILY_QUOTA_EXHAUSTED";
    static final String REASON_BUDGET_SUM_MISMATCH = "BUDGET_SUM_MISMATCH";
    static final String REASON_ACTIVITY_VERSION_MISMATCH = "ACTIVITY_VERSION_MISMATCH";
    static final String REASON_STARTED_HOUR_BUDGET_REDUCED = "STARTED_HOUR_BUDGET_REDUCED";
    static final String REASON_IDEMPOTENCY_PARAMS_MISMATCH = "IDEMPOTENCY_PARAMS_MISMATCH";
    static final String REASON_CONCURRENT_KEY_CONFLICT = "CONCURRENT_KEY_CONFLICT";
    static final String REASON_INVALID_STATUS_FILTER = "INVALID_STATUS_FILTER";

    private final Clock clock;
    private final CampaignRepository campaignRepository;
    private final ActivityRepository activityRepository;
    private final ReservationRepository reservationRepository;
    private final LedgerRepository ledgerRepository;
    private final ActivityHourLedgerRepository hourLedgerRepository;
    private final ActivityHourSnapshotRepository snapshotRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public ExposureServiceImpl(Clock clock,
                               CampaignRepository campaignRepository,
                               ActivityRepository activityRepository,
                               ReservationRepository reservationRepository,
                               LedgerRepository ledgerRepository,
                               ActivityHourLedgerRepository hourLedgerRepository,
                               ActivityHourSnapshotRepository snapshotRepository,
                               IdempotencyRepository idempotencyRepository,
                               ObjectMapper objectMapper,
                               TransactionTemplate txTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.activityRepository = activityRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.hourLedgerRepository = hourLedgerRepository;
        this.snapshotRepository = snapshotRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
    }

    @Override
    public CampaignResponse createCampaign(CreateCampaignRequest request) {
        String activityId = normalizeActivityId(request.activityId());
        String fingerprint = request.campaignId() + "|" + request.dailyTotalCap() + "|"
                + request.perVisitorDailyCap() + "|" + String.valueOf(activityId);
        return runIdempotent(request.requestId(), Operation.CREATE_CAMPAIGN, fingerprint,
                CampaignResponse.class, () -> {
                    Campaign existing = campaignRepository.findById(request.campaignId()).orElse(null);
                    if (existing != null) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "campaign already exists: " + request.campaignId(),
                                REASON_CAMPAIGN_ALREADY_EXISTS, null);
                    }
                    // 归属活动必须已存在；不允许把新公告挂到不存在的活动上
                    if (activityId != null) {
                        requireActivity(activityId);
                    }
                    Campaign campaign = new Campaign(
                            request.campaignId(),
                            request.dailyTotalCap(),
                            request.perVisitorDailyCap(),
                            activityId,
                            clock.millis());
                    try {
                        campaignRepository.insert(campaign);
                    } catch (DuplicateKeyException duplicateCampaign) {
                        // 并发创建同一 campaignId：明确返回 409，而非误报幂等键冲突
                        throw new ApiException(HttpStatus.CONFLICT,
                                "campaign already exists: " + request.campaignId(),
                                REASON_CAMPAIGN_ALREADY_EXISTS, null);
                    }
                    return CampaignResponse.from(campaign);
                });
    }

    @Override
    public ActivityResponse createActivity(CreateActivityRequest request) {
        validateBudgetsOr400(request.dailyTotalCap(), request.hourlyBudgets());
        String fingerprint = request.activityId() + "|" + request.dailyTotalCap() + "|"
                + request.hourlyBudgets();
        return runIdempotent(request.requestId(), Operation.CREATE_ACTIVITY, fingerprint,
                ActivityResponse.class, () -> {
                    if (activityRepository.findById(request.activityId()).isPresent()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "activity already exists: " + request.activityId(),
                                REASON_ACTIVITY_ALREADY_EXISTS, null);
                    }
                    long now = clock.millis();
                    Activity activity = new Activity(
                            request.activityId(),
                            request.dailyTotalCap(),
                            request.hourlyBudgets(),
                            1,
                            now,
                            now);
                    try {
                        activityRepository.insert(activity);
                    } catch (DuplicateKeyException duplicateActivity) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "activity already exists: " + request.activityId(),
                                REASON_ACTIVITY_ALREADY_EXISTS, null);
                    }
                    return ActivityResponse.from(activity);
                });
    }

    @Override
    public ActivityResponse updateActivity(String activityId, UpdateActivityRequest request) {
        validateBudgetsOr400(request.dailyTotalCap(), request.hourlyBudgets());
        String fingerprint = activityId + "|" + request.expectedVersion() + "|"
                + request.dailyTotalCap() + "|" + request.hourlyBudgets();
        return runIdempotent(request.requestId(), Operation.UPDATE_ACTIVITY, fingerprint,
                ActivityResponse.class, () -> {
                    long now = clock.millis();
                    Activity current = activityRepository.lockById(activityId)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "activity not found: " + activityId));

                    // 乐观锁：版本不匹配直接 409，给出期望版本与实际版本
                    if (current.version() != request.expectedVersion()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "activity version mismatch for " + activityId,
                                REASON_ACTIVITY_VERSION_MISMATCH,
                                Map.of(
                                        "activityId", activityId,
                                        "expectedVersion", request.expectedVersion(),
                                        "actualVersion", current.version()));
                    }

                    // 已开始（含当前进行中）的小时预算不可下调；历史预占和确认记录不迁移
                    Optional<Integer> reducedHour = Pacing.findReducedStartedHour(
                            current.hourlyBudgets(), request.hourlyBudgets(), Instant.ofEpochMilli(now));
                    if (reducedHour.isPresent()) {
                        int hour = reducedHour.get();
                        int requiredBudget = current.hourlyBudgets().get(hour);
                        int actualBudget = request.hourlyBudgets().get(hour);
                        throw new ApiException(HttpStatus.CONFLICT,
                                "budget of started UTC hour " + hour + " cannot be reduced",
                                REASON_STARTED_HOUR_BUDGET_REDUCED,
                                Map.of(
                                        "activityId", activityId,
                                        "utcHour", hour,
                                        "requiredHourBudget", requiredBudget,
                                        "actualHourBudget", actualBudget,
                                        "shortfall", requiredBudget - actualBudget));
                    }

                    Activity updated = new Activity(
                            activityId,
                            request.dailyTotalCap(),
                            request.hourlyBudgets(),
                            request.expectedVersion() + 1,
                            current.createdAtUtc(),
                            now);
                    if (!activityRepository.compareAndUpdate(updated, request.expectedVersion(), now)) {
                        // 行锁内仍做 CAS 兜底：并发改配时只有一个成功
                        Activity latest = activityRepository.lockById(activityId).orElseThrow();
                        throw new ApiException(HttpStatus.CONFLICT,
                                "activity version mismatch for " + activityId,
                                REASON_ACTIVITY_VERSION_MISMATCH,
                                Map.of(
                                        "activityId", activityId,
                                        "expectedVersion", request.expectedVersion(),
                                        "actualVersion", latest.version()));
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
        String fingerprint = campaignId + "|" + request.activityId();
        return runIdempotent(request.requestId(), Operation.MIGRATE_CAMPAIGN, fingerprint,
                CampaignResponse.class, () -> {
                    // 固定顺序先锁公告再锁目标活动，与申请路径一致，避免死锁
                    Campaign campaign = campaignRepository.lockById(campaignId)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "campaign not found: " + campaignId));
                    Activity target = activityRepository.lockById(request.activityId())
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "activity not found: " + request.activityId()));

                    // 只更新公告当前归属：迁移只影响迁移后的新预占，既有预占固化活动和小时归属
                    campaignRepository.updateActivity(campaignId, target.activityId());
                    return CampaignResponse.from(new Campaign(
                            campaign.campaignId(),
                            campaign.dailyTotalCap(),
                            campaign.perVisitorDailyCap(),
                            target.activityId(),
                            campaign.createdAtUtc()));
                });
    }

    @Override
    public ReservationResponse apply(ApplyExposureRequest request) {
        String fingerprint = request.campaignId() + "|" + request.visitorId();
        return runIdempotent(request.requestId(), Operation.APPLY, fingerprint,
                ReservationResponse.class, () -> {
                    long now = clock.millis();
                    Instant nowInstant = Instant.ofEpochMilli(now);
                    LocalDate utcDate = LocalDate.now(clock);
                    int utcHour = Pacing.utcHour(nowInstant);

                    // 固定加锁顺序：公告行 → 活动行
                    Campaign campaign = campaignRepository.lockById(request.campaignId())
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "campaign not found: " + request.campaignId()));
                    if (campaign.activityId() == null) {
                        // 未配置小时预算（公告未归入活动）时不得申请
                        throw new ApiException(HttpStatus.CONFLICT,
                                "campaign is not assigned to any activity, cannot apply: "
                                        + campaign.campaignId(),
                                REASON_CAMPAIGN_ACTIVITY_NOT_CONFIGURED,
                                Map.of("campaignId", campaign.campaignId()));
                    }
                    Activity activity = activityRepository.lockById(campaign.activityId())
                            .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                                    "assigned activity missing: " + campaign.activityId()));

                    // 先查出“本公告或本活动”全部到期候选，再按编号固定顺序逐行加锁 CAS 结算，
                    // 跨公告共享候选时加锁相对顺序一致，避免交叉加锁死锁
                    List<Reservation> expiredCandidates = reservationRepository.findExpiredForCampaignOrActivity(
                            campaign.campaignId(), activity.activityId(), now);
                    for (Reservation candidate : expiredCandidates) {
                        reservationRepository.lockById(candidate.reservationId())
                                .ifPresent(locked -> expireIfDue(locked, now));
                    }

                    int hourBudget = activity.budget(utcHour);

                    // 账目行先建后锁，固定顺序：活动小时账 → 公告当日总账 → 访客当日账
                    hourLedgerRepository.ensureRow(activity.activityId(), utcDate, utcHour);
                    ledgerRepository.ensureTotalRow(campaign.campaignId(), utcDate);
                    ledgerRepository.ensureVisitorRow(campaign.campaignId(), request.visitorId(), utcDate);
                    HourLedger hourLedger = hourLedgerRepository.lock(
                            activity.activityId(), utcDate, utcHour);
                    int usedTotal = ledgerRepository.lockUsedTotal(campaign.campaignId(), utcDate);
                    int usedVisitor = ledgerRepository.lockUsedVisitor(
                            campaign.campaignId(), request.visitorId(), utcDate);

                    // 先裁决活动当前小时：小时额度耗尽返回 429 并给出下一可用小时，
                    // 此时公告账、访客账、小时账均未增加
                    if (hourLedger.reservedCount() + 1 > hourBudget) {
                        throw hourBudgetExhausted(activity, utcDate, utcHour,
                                hourLedger.reservedCount(), hourBudget, nowInstant);
                    }
                    // 公告/访客额度裁决：任一已满 429，三侧名额均未消耗
                    if (usedTotal + 1 > campaign.dailyTotalCap()) {
                        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                                "campaign daily quota exhausted: " + campaign.campaignId(),
                                REASON_CAMPAIGN_DAILY_QUOTA_EXHAUSTED,
                                quotaDetails(campaign.campaignId(), utcDate,
                                        campaign.dailyTotalCap(), usedTotal));
                    }
                    if (usedVisitor + 1 > campaign.perVisitorDailyCap()) {
                        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                                "visitor daily quota exhausted: " + request.visitorId(),
                                REASON_VISITOR_DAILY_QUOTA_EXHAUSTED,
                                visitorQuotaDetails(campaign.campaignId(), request.visitorId(), utcDate,
                                        campaign.perVisitorDailyCap(), usedVisitor));
                    }

                    // 三侧原子预占（行锁已持，条件更新兜底不超卖）
                    if (!hourLedgerRepository.tryReserve(activity.activityId(), utcDate, utcHour, hourBudget)) {
                        throw hourBudgetExhausted(activity, utcDate, utcHour,
                                hourLedger.reservedCount(), hourBudget, nowInstant);
                    }
                    ledgerRepository.addTotal(campaign.campaignId(), utcDate, 1);
                    ledgerRepository.addVisitor(campaign.campaignId(), request.visitorId(), utcDate, 1);

                    String reservationId = UUID.randomUUID().toString().replace("-", "");
                    Reservation reservation = new Reservation(
                            reservationId,
                            campaign.campaignId(),
                            request.visitorId(),
                            java.sql.Date.valueOf(utcDate),
                            activity.activityId(),
                            utcHour,
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
        // 先在独立事务结算本单过期并提交：到期单的确认/取消随后返回 409，
        // 409 只回滚幂等键占位，不把已发生的过期释放一并回滚
        settleSingleExpiry(reservationId);
        String fingerprint = reservationId;
        return runIdempotent(request.requestId(), Operation.CONFIRM, fingerprint,
                ReservationResponse.class, () -> transition(reservationId, true));
    }

    @Override
    public ReservationResponse cancel(String reservationId, ReservationActionRequest request) {
        settleSingleExpiry(reservationId);
        String fingerprint = reservationId;
        return runIdempotent(request.requestId(), Operation.CANCEL, fingerprint,
                ReservationResponse.class, () -> transition(reservationId, false));
    }

    /**
     * 在独立短事务内行锁结算单个预占单的过期状态并提交；未到期、不存在或已终态均为无操作。
     * 与后续业务事务分离，保证“到期即释放”不被业务 409 的回滚撤销。
     */
    private void settleSingleExpiry(String reservationId) {
        long now = clock.millis();
        txTemplate.execute(status -> {
            reservationRepository.lockById(reservationId)
                    .ifPresent(reservation -> expireIfDue(reservation, now));
            return null;
        });
    }

    @Override
    public ReservationResponse getReservation(String reservationId) {
        // 明细查询保持只读：不触发惰性过期结算，状态以已提交数据为准
        return ReservationResponse.from(requireReservation(reservationId));
    }

    @Override
    public QuotaResponse queryQuota(String campaignId, String visitorId, LocalDate requestedDate) {
        return txTemplate.execute(status -> {
            Campaign campaign = requireCampaign(campaignId);
            long now = clock.millis();
            LocalDate utcDate = requestedDate != null ? requestedDate : LocalDate.now(clock);

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
    public DailySettlementResponse dailySettlement(String activityId, LocalDate requestedDate) {
        return txTemplate.execute(status -> {
            requireActivity(activityId);
            LocalDate today = LocalDate.now(clock);
            LocalDate utcDate = requestedDate != null ? requestedDate : today;
            long now = clock.millis();

            boolean frozen = false;
            Long settledAtUtc = null;
            // 往日首次查询：固化每小时确认数与取消数快照；快照只写一次，之后不改写
            if (utcDate.isBefore(today) && !snapshotRepository.existsByDay(activityId, utcDate)) {
                List<HourLedger> rows = hourLedgerRepository.lockByDay(activityId, utcDate);
                Map<Integer, HourLedger> byHour = new HashMap<>();
                for (HourLedger row : rows) {
                    byHour.put(row.utcHour(), row);
                }
                for (int hour = 0; hour < Activity.HOURS_PER_DAY; hour++) {
                    HourLedger row = byHour.getOrDefault(hour,
                            new HourLedger(activityId, utcDate, hour, 0, 0, 0, 0));
                    // WHERE NOT EXISTS：并发首次查询时后到者不覆盖先固化的值（往日账目已不变，两者等价）
                    snapshotRepository.insertIfAbsent(new HourSnapshot(
                            activityId, utcDate, hour,
                            row.confirmedCount(), row.cancelledCount(), now));
                }
                frozen = true;
                settledAtUtc = now;
            } else if (utcDate.isBefore(today)) {
                frozen = true;
            }

            Map<Integer, HourSnapshot> snapshotByHour = new HashMap<>();
            Map<Integer, HourLedger> ledgerByHour = new HashMap<>();
            if (frozen) {
                for (HourSnapshot snapshot : snapshotRepository.listByDay(activityId, utcDate)) {
                    snapshotByHour.put(snapshot.utcHour(), snapshot);
                    if (settledAtUtc == null) {
                        settledAtUtc = snapshot.settledAtUtc();
                    }
                }
            } else {
                for (HourLedger row : hourLedgerRepository.listByDay(activityId, utcDate)) {
                    ledgerByHour.put(row.utcHour(), row);
                }
            }

            List<DailySettlementResponse.Hour> hours = new ArrayList<>(Activity.HOURS_PER_DAY);
            int dailyConfirmed = 0;
            int dailyCancelled = 0;
            for (int hour = 0; hour < Activity.HOURS_PER_DAY; hour++) {
                int confirmed;
                int cancelled;
                if (frozen) {
                    HourSnapshot snapshot = snapshotByHour.get(hour);
                    confirmed = snapshot != null ? snapshot.confirmedCount() : 0;
                    cancelled = snapshot != null ? snapshot.cancelledCount() : 0;
                } else {
                    HourLedger row = ledgerByHour.get(hour);
                    confirmed = row != null ? row.confirmedCount() : 0;
                    cancelled = row != null ? row.cancelledCount() : 0;
                }
                dailyConfirmed += confirmed;
                dailyCancelled += cancelled;
                hours.add(new DailySettlementResponse.Hour(hour, confirmed, cancelled));
            }
            return new DailySettlementResponse(activityId, utcDate, frozen, settledAtUtc,
                    hours, dailyConfirmed, dailyCancelled);
        });
    }

    @Override
    public ActivityDayResponse activityDay(String activityId, LocalDate requestedDate) {
        return txTemplate.execute(status -> {
            Activity activity = requireActivity(activityId);
            long now = clock.millis();
            LocalDate utcDate = requestedDate != null ? requestedDate : LocalDate.now(clock);

            // 只读诊断：直接读账目，不触发过期结算（过期名额在下一次写操作时结算）
            Map<Integer, HourLedger> byHour = new HashMap<>();
            for (HourLedger row : hourLedgerRepository.listByDay(activityId, utcDate)) {
                byHour.put(row.utcHour(), row);
            }
            List<ActivityDayResponse.Hour> hours = new ArrayList<>(Activity.HOURS_PER_DAY);
            for (int hour = 0; hour < Activity.HOURS_PER_DAY; hour++) {
                HourLedger row = byHour.get(hour);
                int reserved = row != null ? row.reservedCount() : 0;
                int confirmed = row != null ? row.confirmedCount() : 0;
                int cancelled = row != null ? row.cancelledCount() : 0;
                int expired = row != null ? row.expiredCount() : 0;
                int budget = activity.budget(hour);
                hours.add(new ActivityDayResponse.Hour(hour, budget, reserved, confirmed,
                        cancelled, expired, budget - reserved));
            }
            return new ActivityDayResponse(activityId, utcDate, activity.dailyTotalCap(),
                    Pacing.utcHour(Instant.ofEpochMilli(now)), hours, now);
        });
    }

    @Override
    public List<ReservationResponse> listReservations(String campaignId, LocalDate utcDate,
                                                      String status, int requestedLimit) {
        return txTemplate.execute(txStatus -> {
            requireCampaign(campaignId);
            ReservationStatus statusFilter = null;
            if (status != null && !status.isBlank()) {
                try {
                    statusFilter = ReservationStatus.valueOf(status);
                } catch (IllegalArgumentException badStatus) {
                    throw new ApiException(HttpStatus.BAD_REQUEST,
                            "invalid reservation status filter: " + status,
                            REASON_INVALID_STATUS_FILTER,
                            Map.of("status", status));
                }
            }
            int limit = Math.min(Math.max(requestedLimit, 1), MAX_HISTORY_LIMIT);
            List<Reservation> reservations = reservationRepository.listHistory(
                    campaignId, utcDate, statusFilter, limit);
            return reservations.stream().map(ReservationResponse::from).toList();
        });
    }

    // ---- 内部辅助（作用域末尾） ----

    /** 幂等操作类型，同时标识存储响应的反序列化类型。 */
    private enum Operation {
        CREATE_CAMPAIGN,
        CREATE_ACTIVITY,
        UPDATE_ACTIVITY,
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
                                    "idempotency key reused with different parameters: " + requestId,
                                    REASON_IDEMPOTENCY_PARAMS_MISMATCH,
                                    Map.of("requestId", requestId,
                                            "requiredOperation", operation.name(),
                                            "actualOperation", record.operation()));
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
                            "concurrent idempotency key conflict: " + requestId,
                            REASON_CONCURRENT_KEY_CONFLICT, Map.of("requestId", requestId));
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
     * 确认同时结算三侧（公告/访客保持占用、活动 confirmed +1）；
     * 取消只释放尚未确认的同一小时预占（三侧释放/计数）。
     *
     * @param isConfirm true=确认，false=取消
     */
    private ReservationResponse transition(String reservationId, boolean isConfirm) {
        long now = clock.millis();
        Reservation current = reservationRepository.lockById(reservationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "reservation not found: " + reservationId));

        // 仅结算本单（本事务已持其行锁；先预占单行锁再额度账，与申请路径一致）
        expireIfDue(current, now);
        current = reservationRepository.lockById(reservationId).orElseThrow();

        if (current.status() == ReservationStatus.RESERVED) {
            // 结算后仍为 RESERVED 说明 now < expiresAt，确认严格要求在到期时刻之前
            LocalDate utcDate = current.utcDate().toLocalDate();
            if (isConfirm) {
                if (!reservationRepository.compareAndSetStatus(
                        reservationId, ReservationStatus.RESERVED, ReservationStatus.CONFIRMED, now)) {
                    throw new ApiException(HttpStatus.CONFLICT, "reservation state changed concurrently");
                }
                // 确认：活动小时确认数 +1；活动小时占用、公告额度、访客额度均保持占用不释放
                if (current.activityId() != null && current.utcHour() != null) {
                    hourLedgerRepository.addConfirmed(
                            current.activityId(), utcDate, current.utcHour());
                }
            } else {
                if (!reservationRepository.compareAndSetStatus(
                        reservationId, ReservationStatus.RESERVED, ReservationStatus.CANCELLED, now)) {
                    throw new ApiException(HttpStatus.CONFLICT, "reservation state changed concurrently");
                }
                // 取消释放三侧：活动同一小时预占、公告当日账、访客当日账
                if (current.activityId() != null && current.utcHour() != null) {
                    hourLedgerRepository.releaseAndCountCancel(
                            current.activityId(), utcDate, current.utcHour());
                }
                ledgerRepository.releaseTotal(current.campaignId(), utcDate);
                ledgerRepository.releaseVisitor(
                        current.campaignId(), current.visitorId(), utcDate);
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
            // 重复同类终态操作：返回原状态，不重复结算/释放任何名额
        } else {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "unexpected reservation status: " + current.status());
        }

        Reservation result = reservationRepository.lockById(reservationId).orElseThrow();
        return ReservationResponse.from(result);
    }

    /**
     * 若传入预占单（调用方已持其行锁）已到期，则 CAS 转 EXPIRED 并释放三侧尚未确认的同一小时预占；
     * 未到期或已非 RESERVED 则不做任何变更。历史无活动归属单据只释放公告/访客两侧。
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
                if (reservation.activityId() != null && reservation.utcHour() != null) {
                    hourLedgerRepository.releaseAndCountExpire(
                            reservation.activityId(), utcDate, reservation.utcHour());
                }
                ledgerRepository.releaseTotal(reservation.campaignId(), utcDate);
                ledgerRepository.releaseVisitor(
                        reservation.campaignId(), reservation.visitorId(), utcDate);
            }
        }
    }

    /**
     * 校验 24 小时预算：非负整数、合计等于日总额；不合法转 400 并返回实际合计与要求值。
     */
    private void validateBudgetsOr400(int dailyTotalCap, List<Integer> hourlyBudgets) {
        long sum = 0L;
        if (hourlyBudgets != null) {
            for (Integer budget : hourlyBudgets) {
                sum += budget == null ? 0 : budget;
            }
        }
        try {
            Pacing.validateBudgets(dailyTotalCap, hourlyBudgets);
        } catch (IllegalArgumentException invalid) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("requiredDailyTotalCap", dailyTotalCap);
            details.put("actualHourlyBudgetSum", sum);
            details.put("shortfall", dailyTotalCap - sum);
            details.put("requiredHourCount", Activity.HOURS_PER_DAY);
            details.put("actualHourCount", hourlyBudgets == null ? 0 : hourlyBudgets.size());
            throw new ApiException(HttpStatus.BAD_REQUEST, invalid.getMessage(),
                    REASON_BUDGET_SUM_MISMATCH, details);
        }
    }

    /** 构造小时额度耗尽的 429：给出预算（要求值）、实际占用、差额与下一可用小时。 */
    private ApiException hourBudgetExhausted(Activity activity, LocalDate utcDate, int utcHour,
                                            int reservedCount, int hourBudget, Instant now) {
        Optional<Instant> next = Pacing.nextAvailableHourStart(activity.hourlyBudgets(), now);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("activityId", activity.activityId());
        details.put("utcDate", utcDate.toString());
        details.put("utcHour", utcHour);
        details.put("hourBudget", hourBudget);
        details.put("reservedCount", reservedCount);
        details.put("requestedCount", 1);
        details.put("shortfall", reservedCount + 1 - hourBudget);
        if (next.isPresent()) {
            details.put("nextAvailableHourUtc", next.get().toEpochMilli());
            details.put("retryAfterMillis", next.get().toEpochMilli() - now.toEpochMilli());
        } else {
            // 全日预算均为 0：不存在下一可用小时，保持 null 语义
            details.put("nextAvailableHourUtc", null);
            details.put("retryAfterMillis", null);
        }
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                "activity hour budget exhausted: " + activity.activityId()
                        + " at UTC hour " + utcHour,
                REASON_ACTIVITY_HOUR_BUDGET_EXHAUSTED, details);
    }

    private Map<String, Object> quotaDetails(String campaignId, LocalDate utcDate,
                                             int dailyTotalCap, int usedTotal) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("campaignId", campaignId);
        details.put("utcDate", utcDate.toString());
        details.put("dailyTotalCap", dailyTotalCap);
        details.put("usedTotal", usedTotal);
        details.put("requestedCount", 1);
        details.put("shortfall", usedTotal + 1 - dailyTotalCap);
        return details;
    }

    private Map<String, Object> visitorQuotaDetails(String campaignId, String visitorId, LocalDate utcDate,
                                                    int perVisitorDailyCap, int usedVisitor) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("campaignId", campaignId);
        details.put("visitorId", visitorId);
        details.put("utcDate", utcDate.toString());
        details.put("perVisitorDailyCap", perVisitorDailyCap);
        details.put("usedVisitor", usedVisitor);
        details.put("requestedCount", 1);
        details.put("shortfall", usedVisitor + 1 - perVisitorDailyCap);
        return details;
    }

    private String normalizeActivityId(String activityId) {
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

    private Reservation requireReservation(String reservationId) {
        return reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "reservation not found: " + reservationId));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }
}
