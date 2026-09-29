package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.Activity;
import com.example.starter.exposure.domain.ActivityDayConfig;
import com.example.starter.exposure.domain.ActivityHourSnapshot;
import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.repo.ActivityDayConfigRepository;
import com.example.starter.exposure.repo.ActivityHourLedgerRepository;
import com.example.starter.exposure.repo.ActivityHourSnapshotRepository;
import com.example.starter.exposure.repo.ActivityRepository;
import com.example.starter.exposure.repo.CampaignRepository;
import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.exposure.repo.LedgerRepository;
import com.example.starter.exposure.repo.ReservationRepository;
import com.example.starter.exposure.web.ActivityDayConfigRequest;
import com.example.starter.exposure.web.ActivityDayConfigResponse;
import com.example.starter.exposure.web.ActivityDayDiagnosticsResponse;
import com.example.starter.exposure.web.ActivityDaySettlementResponse;
import com.example.starter.exposure.web.ActivityHourStatus;
import com.example.starter.exposure.web.ActivityResponse;
import com.example.starter.exposure.web.ActivitySettlementHour;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.AssignCampaignActivityRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateActivityRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuotaExceededException;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationHistoryResponse;
import com.example.starter.exposure.web.ReservationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 公告曝光频控与活动匀速投放业务服务实现。
 *
 * <p>所有写操作以 requestId 为全局幂等键：同键同参重放原成功结果，异参 409；
 * 业务失败随事务回滚，不占幂等键。所有操作与额度查询先结算相关过期预占，
 * 不依赖后台定时器。终态竞争由行锁 + 状态 CAS 保证只允许一个终态。</p>
 *
 * <p>申请时三侧名额（活动当前小时、公告当日总额、访客当日上限）全部行锁就位并
 * 校验通过后才一次性增加，任一失败整体回滚，不留半成品。加锁全局顺序固定为：
 * 预占单行 → 公告行 → 活动日配置行 → 活动小时账行 → 公告日总账行 → 访客日账行。</p>
 */
@Service
public class ExposureServiceImpl implements ExposureService {

    static final int RESERVATION_TTL_MILLIS = 60_000;

    /** 历史查询默认/最大返回条数。 */
    private static final int HISTORY_DEFAULT_LIMIT = 50;
    private static final int HISTORY_MAX_LIMIT = 200;

    /** 并发同键竞争时等待胜出事务提交的最大时长。 */
    private static final long IDEMPOTENT_WAIT_MILLIS = 10_000L;

    private final Clock clock;
    private final CampaignRepository campaignRepository;
    private final ActivityRepository activityRepository;
    private final ActivityDayConfigRepository activityDayConfigRepository;
    private final ActivityHourLedgerRepository activityHourLedgerRepository;
    private final ActivityHourSnapshotRepository activityHourSnapshotRepository;
    private final ReservationRepository reservationRepository;
    private final LedgerRepository ledgerRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public ExposureServiceImpl(Clock clock,
                               CampaignRepository campaignRepository,
                               ActivityRepository activityRepository,
                               ActivityDayConfigRepository activityDayConfigRepository,
                               ActivityHourLedgerRepository activityHourLedgerRepository,
                               ActivityHourSnapshotRepository activityHourSnapshotRepository,
                               ReservationRepository reservationRepository,
                               LedgerRepository ledgerRepository,
                               IdempotencyRepository idempotencyRepository,
                               ObjectMapper objectMapper,
                               TransactionTemplate txTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.activityRepository = activityRepository;
        this.activityDayConfigRepository = activityDayConfigRepository;
        this.activityHourLedgerRepository = activityHourLedgerRepository;
        this.activityHourSnapshotRepository = activityHourSnapshotRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
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
                        // 并发创建同一 activityId：明确返回 409，而非误报幂等键冲突
                        throw new ApiException(HttpStatus.CONFLICT,
                                "activity already exists: " + request.activityId());
                    }
                    return ActivityResponse.from(activity);
                });
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
    public ActivityDayConfigResponse configureActivityDay(String activityId, LocalDate utcDate,
                                                          ActivityDayConfigRequest request) {
        int[] hours = request.hourlyBudgets().stream().mapToInt(Integer::intValue).toArray();
        // 24 小时预算均为非负整数且合计必须等于日总额
        String budgetError = PacingRules.validateBudgets(request.dailyTotalCap(), hours);
        if (budgetError != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, budgetError);
        }

        String fingerprint = activityId + "|" + utcDate + "|" + request.dailyTotalCap() + "|"
                + request.hourlyBudgets().stream().map(String::valueOf).collect(Collectors.joining(","))
                + "|" + (request.expectedVersion() == null ? "" : request.expectedVersion());
        return runIdempotent(request.requestId(), Operation.CONFIGURE_ACTIVITY_DAY, fingerprint,
                ActivityDayConfigResponse.class, () -> {
                    requireActivity(activityId);
                    long now = clock.millis();
                    ActivityDayConfig existing =
                            activityDayConfigRepository.lock(activityId, utcDate).orElse(null);

                    if (existing == null) {
                        if (request.expectedVersion() != null) {
                            throw new ApiException(HttpStatus.CONFLICT,
                                    "activity day config does not exist; omit expectedVersion to create: "
                                            + activityId + " " + utcDate);
                        }
                        ActivityDayConfig created = new ActivityDayConfig(
                                activityId, utcDate, request.dailyTotalCap(), hours, 1, now);
                        try {
                            activityDayConfigRepository.insert(created);
                        } catch (DuplicateKeyException duplicateConfig) {
                            throw new ApiException(HttpStatus.CONFLICT,
                                    "activity day config already exists: " + activityId + " " + utcDate);
                        }
                        return ActivityDayConfigResponse.from(created);
                    }

                    if (request.expectedVersion() == null) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "activity day config already exists; supply expectedVersion: currentVersion="
                                        + existing.version());
                    }
                    if (existing.version() != request.expectedVersion()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "activity day config version mismatch: actual=" + existing.version()
                                        + ", required=" + request.expectedVersion()
                                        + ", difference=" + (request.expectedVersion() - existing.version()));
                    }
                    // 已开始小时（起点 <= 当前时刻）不可下调；当日之前整天均已开始
                    LocalDate today = LocalDate.now(clock);
                    for (int h = 0; h < ActivityDayConfig.HOURS_PER_DAY; h++) {
                        boolean started = utcDate.isBefore(today)
                                || (utcDate.isEqual(today)
                                        && PacingRules.isStartedHour(utcDate, h, now));
                        if (started && hours[h] < existing.hourlyCap(h)) {
                            throw new ApiException(HttpStatus.CONFLICT,
                                    "started hour budget cannot be decreased: hour=" + h
                                            + ", actual=" + existing.hourlyCap(h)
                                            + ", required=" + hours[h]
                                            + ", difference=" + (hours[h] - existing.hourlyCap(h)));
                        }
                    }

                    ActivityDayConfig updated = new ActivityDayConfig(
                            activityId, utcDate, request.dailyTotalCap(), hours,
                            existing.version() + 1, now);
                    if (!activityDayConfigRepository.compareAndSetUpdate(
                            updated, request.expectedVersion())) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "activity day config version changed concurrently");
                    }
                    return ActivityDayConfigResponse.from(updated);
                });
    }

    @Override
    public ActivityDayConfigResponse getActivityDayConfig(String activityId, LocalDate utcDate) {
        return txTemplate.execute(status -> {
            requireActivity(activityId);
            ActivityDayConfig config = activityDayConfigRepository.find(activityId, utcDate)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "activity day config not found: " + activityId + " " + utcDate));
            return ActivityDayConfigResponse.from(config);
        });
    }

    @Override
    public CampaignResponse assignCampaignActivity(String campaignId,
                                                   AssignCampaignActivityRequest request) {
        String fingerprint = campaignId + "|"
                + (request.activityId() == null ? "" : request.activityId());
        return runIdempotent(request.requestId(), Operation.ASSIGN_CAMPAIGN_ACTIVITY, fingerprint,
                CampaignResponse.class, () -> {
                    // 公告行加锁，与申请事务互斥；迁移只改当前归属，不回写既有预占
                    Campaign campaign = campaignRepository.lockById(campaignId)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "campaign not found: " + campaignId));
                    String targetActivityId = normalizeActivityId(request.activityId());
                    if (targetActivityId != null) {
                        requireActivity(targetActivityId);
                    }
                    if (!java.util.Objects.equals(campaign.activityId(), targetActivityId)) {
                        campaignRepository.updateActivityId(campaignId, targetActivityId);
                    }
                    return CampaignResponse.from(new Campaign(
                            campaign.campaignId(),
                            campaign.dailyTotalCap(),
                            campaign.perVisitorDailyCap(),
                            targetActivityId,
                            campaign.createdAtUtc()));
                });
    }

    @Override
    public ReservationResponse apply(ApplyExposureRequest request) {
        String fingerprint = request.campaignId() + "|" + request.visitorId();
        return runIdempotent(request.requestId(), Operation.APPLY, fingerprint,
                ReservationResponse.class, () -> {
                    long now = clock.millis();
                    LocalDate utcDate = LocalDate.now(clock);
                    int utcHour = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now),
                            ZoneOffset.UTC).getHour();

                    // 公告行加锁：阻塞并发活动迁移，保证读到的归属与本次预占一致
                    Campaign campaign = campaignRepository.lockById(request.campaignId())
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "campaign not found: " + request.campaignId()));

                    // 活动配置行先于预占单加锁，固定全局锁顺序：
                    // 公告行 -> 活动日配置行 -> 预占单行 -> 活动小时账 -> 公告日账 -> 访客账
                    ActivityDayConfig activityConfig = null;
                    if (campaign.activityId() != null) {
                        activityConfig = activityDayConfigRepository
                                .lock(campaign.activityId(), utcDate)
                                .orElseThrow(() -> new QuotaExceededException(
                                        QuotaExceededException.Reason.ACTIVITY_DAY_NOT_CONFIGURED,
                                        "activity day budget not configured, apply rejected: activity="
                                                + campaign.activityId() + " date=" + utcDate,
                                        null, null, null));
                    }

                    // 再结算该公告相关过期预占并释放三侧名额（释放的活动小时以预占单固化归属为准）
                    settleExpired(request.campaignId(), now);

                    // 活动小时账：公告归属活动时锁当前小时
                    int usedActivityHour = 0;
                    int activityHourBudget = 0;
                    if (activityConfig != null) {
                        activityHourBudget = activityConfig.hourlyCap(utcHour);
                        activityHourLedgerRepository.ensureRow(
                                campaign.activityId(), utcDate, utcHour);
                        usedActivityHour = activityHourLedgerRepository.lockUsed(
                                campaign.activityId(), utcDate, utcHour);
                    }

                    // 固定加锁顺序：公告当日总账 -> 访客当日账
                    ledgerRepository.ensureTotalRow(campaign.campaignId(), utcDate);
                    ledgerRepository.ensureVisitorRow(campaign.campaignId(), request.visitorId(), utcDate);
                    int usedTotal = ledgerRepository.lockUsedTotal(campaign.campaignId(), utcDate);
                    int usedVisitor = ledgerRepository.lockUsedVisitor(
                            campaign.campaignId(), request.visitorId(), utcDate);

                    // 三侧全部就位后统一裁决，任一失败直接抛错，事务回滚不占任何名额
                    if (activityConfig != null && usedActivityHour + 1 > activityHourBudget) {
                        Long nextAvailableAtUtc = nextAvailableHourStart(
                                activityConfig, utcDate, utcHour);
                        throw new QuotaExceededException(
                                QuotaExceededException.Reason.ACTIVITY_HOUR_EXHAUSTED,
                                "activity hour quota exhausted: activity=" + activityConfig.activityId()
                                        + " date=" + utcDate + " hour=" + utcHour,
                                (long) usedActivityHour, (long) activityHourBudget,
                                nextAvailableAtUtc);
                    }
                    if (usedTotal + 1 > campaign.dailyTotalCap()) {
                        throw new QuotaExceededException(
                                QuotaExceededException.Reason.CAMPAIGN_DAILY_TOTAL_EXHAUSTED,
                                "exposure quota exhausted for campaign " + campaign.campaignId(),
                                (long) usedTotal, (long) campaign.dailyTotalCap(), null);
                    }
                    if (usedVisitor + 1 > campaign.perVisitorDailyCap()) {
                        throw new QuotaExceededException(
                                QuotaExceededException.Reason.VISITOR_DAILY_CAP_EXHAUSTED,
                                "visitor daily cap exhausted for campaign " + campaign.campaignId()
                                        + " visitor " + request.visitorId(),
                                (long) usedVisitor, (long) campaign.perVisitorDailyCap(), null);
                    }

                    // 全部通过：一次性占用三侧名额
                    if (activityConfig != null) {
                        activityHourLedgerRepository.add(
                                campaign.activityId(), utcDate, utcHour, 1);
                    }
                    ledgerRepository.addTotal(campaign.campaignId(), utcDate, 1);
                    ledgerRepository.addVisitor(campaign.campaignId(), request.visitorId(), utcDate, 1);

                    String reservationId = UUID.randomUUID().toString().replace("-", "");
                    Reservation reservation = new Reservation(
                            reservationId,
                            campaign.campaignId(),
                            request.visitorId(),
                            campaign.activityId(),
                            java.sql.Date.valueOf(utcDate),
                            campaign.activityId() == null ? null : utcHour,
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
            Campaign campaign = campaignRepository.lockById(campaignId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "campaign not found: " + campaignId));
            long now = clock.millis();
            LocalDate today = LocalDate.now(clock);
            LocalDate utcDate = requestedDate != null ? requestedDate : today;

            settleExpired(campaignId, now);

            int usedTotal = ledgerRepository.getUsedTotal(campaignId, utcDate);

            String activityId = campaign.activityId();
            Integer currentHour = null;
            Integer activityHourCap = null;
            Integer usedActivityHour = null;
            Integer remainingActivityHour = null;
            if (activityId != null && utcDate.isEqual(today)) {
                currentHour = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now),
                        ZoneOffset.UTC).getHour();
                ActivityDayConfig config =
                        activityDayConfigRepository.find(activityId, utcDate).orElse(null);
                if (config != null) {
                    activityHourCap = config.hourlyCap(currentHour);
                    usedActivityHour = readActivityHourUsed(activityId, utcDate, currentHour);
                    remainingActivityHour = activityHourCap - usedActivityHour;
                }
            }

            if (visitorId == null || visitorId.isBlank()) {
                return new QuotaResponse(
                        campaignId, null, utcDate,
                        campaign.dailyTotalCap(), usedTotal,
                        campaign.dailyTotalCap() - usedTotal,
                        null, null, null, now,
                        activityId, currentHour, activityHourCap,
                        usedActivityHour, remainingActivityHour);
            }
            int usedVisitor = ledgerRepository.getUsedVisitor(campaignId, visitorId, utcDate);
            return new QuotaResponse(
                    campaignId, visitorId, utcDate,
                    campaign.dailyTotalCap(), usedTotal,
                    campaign.dailyTotalCap() - usedTotal,
                    campaign.perVisitorDailyCap(), usedVisitor,
                    campaign.perVisitorDailyCap() - usedVisitor,
                    now,
                    activityId, currentHour, activityHourCap,
                    usedActivityHour, remainingActivityHour);
        });
    }

    @Override
    public ActivityDayDiagnosticsResponse activityDayDiagnostics(String activityId, LocalDate requestedDate) {
        return txTemplate.execute(status -> {
            requireActivity(activityId);
            LocalDate utcDate = requestedDate != null ? requestedDate : LocalDate.now(clock);
            long now = clock.millis();
            ActivityDayConfig config =
                    activityDayConfigRepository.find(activityId, utcDate).orElse(null);
            List<ActivityHourStatus> hours = new ArrayList<>(ActivityDayConfig.HOURS_PER_DAY);
            for (int h = 0; h < ActivityDayConfig.HOURS_PER_DAY; h++) {
                long start = hourStart(utcDate, h);
                int budget = config == null ? 0 : config.hourlyCap(h);
                int used = readActivityHourUsed(activityId, utcDate, h);
                int remaining = config == null ? 0 : budget - used;
                hours.add(new ActivityHourStatus(
                        h, start, hourStart(utcDate, h + 1),
                        config != null, budget, used, remaining));
            }
            return new ActivityDayDiagnosticsResponse(activityId, utcDate, now, List.copyOf(hours));
        });
    }

    @Override
    public ActivityDaySettlementResponse settleActivityDay(String activityId, LocalDate requestedDate) {
        return txTemplate.execute(status -> {
            requireActivity(activityId);
            LocalDate utcDate = requestedDate != null ? requestedDate : LocalDate.now(clock);
            long now = clock.millis();
            // 锁配置行以固化当时版本（配置可能不存在）
            ActivityDayConfig config =
                    activityDayConfigRepository.lock(activityId, utcDate).orElse(null);

            List<ActivitySettlementHour> hours =
                    new ArrayList<>(ActivityDayConfig.HOURS_PER_DAY);
            for (int h = 0; h < ActivityDayConfig.HOURS_PER_DAY; h++) {
                ActivityHourSnapshot existing =
                        activityHourSnapshotRepository.lock(activityId, utcDate, h).orElse(null);
                long start = hourStart(utcDate, h);
                if (existing != null) {
                    // 已固化快照不随后续操作或配置变更改写（预算展示取当前配置，计数与版本以快照为准）
                    hours.add(new ActivitySettlementHour(
                            h, start, hourStart(utcDate, h + 1),
                            existing.confirmedCount(), existing.cancelledCount(),
                            existing.expiredCount(),
                            config == null ? null : config.hourlyCap(h),
                            existing.configVersion(), true));
                    continue;
                }

                // 锁该小时全部预占单并先结算已到期单，再按终态计数；仍为 RESERVED 的不计入任何桶
                List<Reservation> reservations =
                        reservationRepository.lockByActivityHour(activityId, utcDate, h);
                int confirmed = 0;
                int cancelled = 0;
                int expired = 0;
                for (Reservation reservation : reservations) {
                    expireIfDue(reservation, now);
                    Reservation fresh = reservationRepository.lockById(
                            reservation.reservationId()).orElseThrow();
                    switch (fresh.status()) {
                        case CONFIRMED -> confirmed++;
                        case CANCELLED -> cancelled++;
                        case EXPIRED -> expired++;
                        default -> {
                            // RESERVED：尚未终态，不进入日结快照
                        }
                    }
                }

                Integer configVersion = config == null ? null : config.version();
                Integer budget = config == null ? null : config.hourlyCap(h);
                ActivityHourSnapshot snapshot = new ActivityHourSnapshot(
                        activityId, utcDate, h, confirmed, cancelled, expired,
                        configVersion, now);
                try {
                    activityHourSnapshotRepository.insert(snapshot);
                } catch (DuplicateKeyException concurrentSettlement) {
                    // 并发日结抢先固化该小时：回读胜出者快照，绝不覆盖
                    ActivityHourSnapshot winner = activityHourSnapshotRepository
                            .lock(activityId, utcDate, h).orElseThrow();
                    hours.add(new ActivitySettlementHour(
                            h, start, hourStart(utcDate, h + 1),
                            winner.confirmedCount(), winner.cancelledCount(),
                            winner.expiredCount(),
                            config == null ? null : config.hourlyCap(h),
                            winner.configVersion(), true));
                    continue;
                }
                hours.add(new ActivitySettlementHour(
                        h, start, hourStart(utcDate, h + 1),
                        confirmed, cancelled, expired, budget, configVersion, false));
            }
            return new ActivityDaySettlementResponse(activityId, utcDate, now, List.copyOf(hours));
        });
    }

    @Override
    public ReservationHistoryResponse findReservations(String activityId, String campaignId,
                                                       String visitorId, Long fromCreatedAtUtc,
                                                       Long toCreatedAtUtc, Integer requestedLimit) {
        if (fromCreatedAtUtc != null && toCreatedAtUtc != null
                && fromCreatedAtUtc > toCreatedAtUtc) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "fromCreatedAtUtc must be <= toCreatedAtUtc: actual=" + fromCreatedAtUtc
                            + ", required<=" + toCreatedAtUtc
                            + ", difference=" + (toCreatedAtUtc - fromCreatedAtUtc));
        }
        int limit = requestedLimit == null ? HISTORY_DEFAULT_LIMIT : requestedLimit;
        if (limit < 1 || limit > HISTORY_MAX_LIMIT) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "limit must be between 1 and " + HISTORY_MAX_LIMIT + ": actual=" + limit);
        }
        return txTemplate.execute(status -> {
            List<Reservation> reservations = reservationRepository.findHistory(
                    activityId, campaignId, visitorId,
                    fromCreatedAtUtc, toCreatedAtUtc, limit);
            List<ReservationResponse> items = reservations.stream()
                    .map(ReservationResponse::from)
                    .toList();
            return new ReservationHistoryResponse(
                    blankToNull(activityId), blankToNull(campaignId), blankToNull(visitorId),
                    fromCreatedAtUtc, toCreatedAtUtc, limit, items.size(), items);
        });
    }

    // ---- 内部辅助（作用域末尾） ----

    /** 幂等操作类型，同时标识存储响应的反序列化类型。 */
    private enum Operation {
        CREATE_ACTIVITY,
        CREATE_CAMPAIGN,
        CONFIGURE_ACTIVITY_DAY,
        ASSIGN_CAMPAIGN_ACTIVITY,
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
     * 加锁顺序为 预占单行 → 活动小时账 → 公告日总账 → 访客日账。
     *
     * @param isConfirm true=确认，false=取消
     */
    private ReservationResponse transition(String reservationId, boolean isConfirm) {
        long now = clock.millis();
        Reservation current = reservationRepository.lockById(reservationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "reservation not found: " + reservationId));

        // 仅结算本单（本事务已持其行锁）
        expireIfDue(current, now);
        current = reservationRepository.lockById(reservationId).orElseThrow();

        if (current.status() == ReservationStatus.RESERVED) {
            // 结算后仍为 RESERVED 说明 now < expiresAt，确认严格要求在到期时刻之前
            if (isConfirm) {
                if (!reservationRepository.compareAndSetStatus(
                        reservationId, ReservationStatus.RESERVED, ReservationStatus.CONFIRMED, now)) {
                    throw new ApiException(HttpStatus.CONFLICT, "reservation state changed concurrently");
                }
                // 确认同时结算三侧名额：活动/公告/访客占用均保持不变（预占即占用）
            } else {
                if (!reservationRepository.compareAndSetStatus(
                        reservationId, ReservationStatus.RESERVED, ReservationStatus.CANCELLED, now)) {
                    throw new ApiException(HttpStatus.CONFLICT, "reservation state changed concurrently");
                }
                // 取消只释放尚未确认的同一小时预占；固定顺序先活动小时账再总账后访客账
                releaseReservationQuotas(current);
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
            // 重复同类终态操作：返回原状态，不重复释放名额
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
     * 若传入预占单（调用方已持其行锁）已到期，则 CAS 转 EXPIRED 并释放三侧名额；
     * 未到期或已非 RESERVED 则不做任何变更。释放的活动小时以预占单固化归属为准。
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
                releaseReservationQuotas(reservation);
            }
        }
    }

    /**
     * 释放预占单三侧名额各一次：活动固化小时（有则）→ 公告当日总额 → 访客当日额度。
     * 仅可在取消/过期 CAS 胜出后调用一次；CHECK 约束保证账目不变负。
     */
    private void releaseReservationQuotas(Reservation reservation) {
        if (reservation.activityId() != null && reservation.utcHour() != null) {
            activityHourLedgerRepository.release(
                    reservation.activityId(),
                    reservation.utcDate().toLocalDate(),
                    reservation.utcHour());
        }
        LocalDate utcDate = reservation.utcDate().toLocalDate();
        ledgerRepository.releaseTotal(reservation.campaignId(), utcDate);
        ledgerRepository.releaseVisitor(
                reservation.campaignId(), reservation.visitorId(), utcDate);
    }

    /**
     * 当前小时耗尽后查找下一可用自然小时起点：取当日严格晚于当前小时、
     * 预算大于 0 且账面上仍有剩余的最早小时；当日没有则 null（次日配置未知）。
     */
    private Long nextAvailableHourStart(ActivityDayConfig config, LocalDate utcDate, int currentHour) {
        int nextHour = PacingRules.nextAvailableHour(
                config.hourlyCaps(),
                h -> readActivityHourUsed(config.activityId(), utcDate, h),
                currentHour);
        return nextHour < 0 ? null : PacingRules.hourStartMillis(utcDate, nextHour);
    }

    /** 活动某小时账面上的当前占用数；无账目行视为 0。 */
    private int readActivityHourUsed(String activityId, LocalDate utcDate, int hour) {
        Integer value = activityHourLedgerRepository.jdbcReadUsed(activityId, utcDate, hour);
        return value == null ? 0 : value;
    }

    /** 指定 UTC 日第 hour 个自然小时的起点（hour 可为 24 表示次日零点），epoch 毫秒 UTC。 */
    private static long hourStart(LocalDate utcDate, int hour) {
        return PacingRules.hourStartMillis(utcDate, hour);
    }

    private Activity requireActivity(String activityId) {
        return activityRepository.findById(activityId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "activity not found: " + activityId));
    }

    private static String normalizeActivityId(String activityId) {
        return activityId == null || activityId.isBlank() ? null : activityId;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }
}
