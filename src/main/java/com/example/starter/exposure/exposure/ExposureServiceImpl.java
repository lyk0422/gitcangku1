package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.Consent;
import com.example.starter.exposure.domain.ConsentDecision;
import com.example.starter.exposure.domain.ConsentStatus;
import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.repo.CampaignRepository;
import com.example.starter.exposure.repo.ConsentRepository;
import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.exposure.repo.LedgerRepository;
import com.example.starter.exposure.repo.ReservationRepository;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.BatchApplyRequest;
import com.example.starter.exposure.web.BatchApplyResponse;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.ConsentResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.EvaluationResponse;
import com.example.starter.exposure.web.FailureReason;
import com.example.starter.exposure.web.GrantConsentRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpdateCategoryRequest;
import com.example.starter.exposure.web.WithdrawConsentRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 公告曝光频控与同意版本联合裁决业务服务实现。
 *
 * <p>裁决顺序固定为：请求时刻同意 → UTC 静默窗口 → 同访客冷却频控 → 两级预算；
 * 缺少 ALLOW 或命中 DENY 抛 {@link FailureReason#CONSENT_DENIED}，不建预占、不扣频次预算。</p>
 *
 * <p>同意按 (访客, 类别) 维护左闭右开、互不重叠的版本时间轴：高版本覆盖时把旧区间
 * 截断成残片；DENY 不可被更低版本覆盖（版本严格递增校验前置保证）。同意授予、撤回与
 * 预占裁决均先锁同一裁决域父行，按事务提交顺序裁决。已建预占固化同意快照，撤回与
 * 类别修改均不影响其回执结算。</p>
 *
 * <p>所有写操作以 requestId 为全局幂等键：同键同参重放原成功结果，异参 409；
 * 业务失败随事务回滚，不占幂等键。</p>
 */
@Service
public class ExposureServiceImpl implements ExposureService {

    static final int RESERVATION_TTL_MILLIS = 60_000;
    static final int MAX_BATCH_VISITORS = 200;
    static final String DEFAULT_CATEGORY = "default";

    /** 并发同键竞争时等待胜出事务提交的最大时长。 */
    private static final long IDEMPOTENT_WAIT_MILLIS = 10_000L;

    private final Clock clock;
    private final CampaignRepository campaignRepository;
    private final ReservationRepository reservationRepository;
    private final LedgerRepository ledgerRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ConsentRepository consentRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public ExposureServiceImpl(Clock clock,
                               CampaignRepository campaignRepository,
                               ReservationRepository reservationRepository,
                               LedgerRepository ledgerRepository,
                               IdempotencyRepository idempotencyRepository,
                               ConsentRepository consentRepository,
                               ObjectMapper objectMapper,
                               TransactionTemplate txTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.consentRepository = consentRepository;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
    }

    @Override
    public CampaignResponse createCampaign(CreateCampaignRequest request) {
        String category = request.category() == null || request.category().isBlank()
                ? DEFAULT_CATEGORY : request.category();
        int silenceStart = request.silenceStartSec() == null ? 0 : request.silenceStartSec();
        int silenceEnd = request.silenceEndSec() == null ? 0 : request.silenceEndSec();
        long minInterval = request.minIntervalMillis() == null ? 0L : request.minIntervalMillis();
        String fingerprint = request.campaignId() + "|" + request.dailyTotalCap() + "|"
                + request.perVisitorDailyCap() + "|" + category + "|" + silenceStart + "|"
                + silenceEnd + "|" + minInterval;
        return runIdempotent(request.requestId(), Operation.CREATE_CAMPAIGN, fingerprint,
                CampaignResponse.class, guard -> {
                    guard.checkReplay();
                    if (campaignRepository.findById(request.campaignId()).isPresent()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                FailureReason.CAMPAIGN_ALREADY_EXISTS,
                                "campaign already exists: " + request.campaignId());
                    }
                    Campaign campaign = new Campaign(
                            request.campaignId(),
                            request.dailyTotalCap(),
                            request.perVisitorDailyCap(),
                            category,
                            1,
                            silenceStart,
                            silenceEnd,
                            minInterval,
                            clock.millis());
                    try {
                        campaignRepository.insert(campaign);
                    } catch (DuplicateKeyException duplicateCampaign) {
                        // 并发创建同一 campaignId：明确返回 409，而非误报幂等键冲突
                        throw new ApiException(HttpStatus.CONFLICT,
                                FailureReason.CAMPAIGN_ALREADY_EXISTS,
                                "campaign already exists: " + request.campaignId());
                    }
                    return CampaignResponse.from(campaign);
                });
    }

    @Override
    public CampaignResponse updateCategory(String campaignId, UpdateCategoryRequest request) {
        return runIdempotent(request.requestId(), Operation.UPDATE_CAMPAIGN_CATEGORY,
                campaignId + "|" + request.category(), CampaignResponse.class, guard -> {
                    Campaign current = campaignRepository.lockById(campaignId)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    FailureReason.NOT_FOUND,
                                    "campaign not found: " + campaignId));
                    guard.checkReplay();
                    if (current.category().equals(request.category())) {
                        // 类别未变化：幂等返回现状，不递增版本
                        return CampaignResponse.from(current);
                    }
                    boolean updated = campaignRepository.updateCategory(
                            campaignId, request.category(), current.version());
                    if (!updated) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                FailureReason.CAMPAIGN_VERSION_CONFLICT,
                                "campaign version changed concurrently: " + campaignId);
                    }
                    // 旧同意不迁移：不触碰任何 visitor_consent 行
                    return CampaignResponse.from(
                            campaignRepository.lockById(campaignId).orElseThrow());
                });
    }

    @Override
    public ConsentResponse grantConsent(GrantConsentRequest request) {
        ConsentDecision decision = parseDecision(request.decision());
        if (request.effectiveEndUtc() <= request.effectiveStartUtc()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, FailureReason.INVALID_REQUEST,
                    "effectiveEndUtc must be greater than effectiveStartUtc");
        }
        String fingerprint = request.visitorId() + "|" + request.category() + "|" + decision.name()
                + "|" + request.consentVersion() + "|" + request.effectiveStartUtc()
                + "|" + request.effectiveEndUtc();
        return runIdempotent(request.requestId(), Operation.GRANT_CONSENT, fingerprint,
                ConsentResponse.class, guard -> {
                    long now = clock.millis();
                    String visitorId = request.visitorId();
                    String category = request.category();

                    // 锁裁决域父行：同 (访客, 类别) 的授予/撤回/预占按提交顺序串行化
                    consentRepository.ensureScope(visitorId, category, now);
                    consentRepository.lockScope(visitorId, category);
                    guard.checkReplay();

                    long maxVersion = consentRepository.maxVersion(visitorId, category);
                    if (request.consentVersion() <= maxVersion) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                FailureReason.CONSENT_VERSION_CONFLICT,
                                "consent version must be greater than " + maxVersion
                                        + " for visitor " + visitorId + " category " + category);
                    }

                    long start = request.effectiveStartUtc();
                    long end = request.effectiveEndUtc();
                    List<Consent> active = consentRepository.lockActiveIntervals(visitorId, category);
                    for (Consent existing : active) {
                        boolean overlap = existing.effectiveStartUtc() < end
                                && existing.effectiveEndUtc() > start;
                        if (!overlap) {
                            continue;
                        }
                        if (existing.decision() == ConsentDecision.DENY
                                && existing.consentVersion() >= request.consentVersion()) {
                            // 理论不可达（版本已强制递增），保留显式保护语义
                            throw new ApiException(HttpStatus.CONFLICT,
                                    FailureReason.CONSENT_DENY_PROTECTED,
                                    "lower version cannot override an effective DENY");
                        }
                        boolean extendsLeft = existing.effectiveStartUtc() < start;
                        boolean extendsRight = existing.effectiveEndUtc() > end;
                        if (extendsLeft && extendsRight) {
                            // 旧区间完整包裹新区间：截为左右两个同版本残片
                            consentRepository.insert(fragment(existing, newConsentId(),
                                    existing.effectiveStartUtc(), start, now));
                            consentRepository.insert(fragment(existing, newConsentId(),
                                    end, existing.effectiveEndUtc(), now));
                            consentRepository.markSuperseded(existing.consentId());
                        } else if (extendsLeft) {
                            consentRepository.updateEnd(existing.consentId(), start);
                        } else if (extendsRight) {
                            consentRepository.updateStart(existing.consentId(), end);
                        } else {
                            consentRepository.markSuperseded(existing.consentId());
                        }
                    }

                    Consent consent = new Consent(
                            newConsentId(), visitorId, category, decision,
                            request.consentVersion(), start, end,
                            ConsentStatus.ACTIVE, now, null);
                    consentRepository.insert(consent);
                    return ConsentResponse.from(consent);
                });
    }

    @Override
    public ConsentResponse withdrawConsent(String consentId, WithdrawConsentRequest request) {
        return runIdempotent(request.requestId(), Operation.WITHDRAW_CONSENT,
                consentId, ConsentResponse.class, guard -> {
                    Consent consent = consentRepository.findById(consentId)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    FailureReason.NOT_FOUND,
                                    "consent not found: " + consentId));
                    long now = clock.millis();
                    // 与授予/预占同一裁决域，撤回按提交顺序对之后的预占生效
                    consentRepository.ensureScope(consent.visitorId(), consent.category(), now);
                    consentRepository.lockScope(consent.visitorId(), consent.category());
                    guard.checkReplay();
                    Consent locked = consentRepository.lockById(consentId)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    FailureReason.NOT_FOUND,
                                    "consent not found: " + consentId));
                    if (locked.status() != ConsentStatus.ACTIVE) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                FailureReason.CONSENT_NOT_ACTIVE,
                                "consent is " + locked.status() + ", cannot withdraw: " + consentId);
                    }
                    if (!consentRepository.compareAndSetWithdrawn(consentId, now)) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                FailureReason.CONSENT_NOT_ACTIVE,
                                "consent state changed concurrently: " + consentId);
                    }
                    return ConsentResponse.from(
                            consentRepository.lockById(consentId).orElseThrow());
                });
    }

    @Override
    public List<ConsentResponse> queryConsents(String visitorId, String category) {
        return consentRepository.findAll(visitorId, category).stream()
                .map(ConsentResponse::from)
                .toList();
    }

    @Override
    public ReservationResponse apply(ApplyExposureRequest request) {
        long now = clock.millis();
        LocalDate utcDate = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(now), ZoneOffset.UTC);
        return runIdempotent(request.requestId(), Operation.APPLY,
                applyFingerprint(request.campaignId(), request.visitorId(), utcDate),
                ReservationResponse.class,
                guard -> createReservations(guard, request.campaignId(),
                        List.of(request.visitorId()), now).get(0));
    }

    @Override
    public BatchApplyResponse batchApply(BatchApplyRequest request) {
        long now = clock.millis();
        LocalDate utcDate = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(now), ZoneOffset.UTC);
        List<String> visitors = request.visitorIds().stream()
                .filter(v -> v != null && !v.isBlank())
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        if (visitors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, FailureReason.INVALID_REQUEST,
                    "visitorIds must not be empty");
        }
        if (visitors.size() > MAX_BATCH_VISITORS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, FailureReason.INVALID_REQUEST,
                    "batch supports at most " + MAX_BATCH_VISITORS + " distinct visitors");
        }
        String fp = batchFingerprint(request.campaignId(), visitors, utcDate);
        return runIdempotent(request.requestId(), Operation.BATCH_APPLY, fp,
                BatchApplyResponse.class, guard -> {
                    List<ReservationResponse> reservations =
                            createReservations(guard, request.campaignId(), visitors, now);
                    Campaign campaign = requireCampaign(request.campaignId());
                    return new BatchApplyResponse(campaign.campaignId(), campaign.version(),
                            campaign.category(), now, reservations);
                });
    }

    @Override
    public ReservationResponse confirm(String reservationId, ReservationActionRequest request) {
        String fingerprint = reservationId;
        return runIdempotent(request.requestId(), Operation.CONFIRM, fingerprint,
                ReservationResponse.class, guard -> transition(guard, reservationId, true));
    }

    @Override
    public ReservationResponse cancel(String reservationId, ReservationActionRequest request) {
        String fingerprint = reservationId;
        return runIdempotent(request.requestId(), Operation.CANCEL, fingerprint,
                ReservationResponse.class, guard -> transition(guard, reservationId, false));
    }

    @Override
    public ReservationResponse getReservation(String reservationId) {
        return txTemplate.execute(status -> {
            Reservation locked = reservationRepository.lockById(reservationId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            FailureReason.NOT_FOUND,
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
    public EvaluationResponse evaluate(String campaignId, String visitorId) {
        return txTemplate.execute(status -> {
            long now = clock.millis();
            Campaign campaign = campaignRepository.lockById(campaignId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            FailureReason.NOT_FOUND,
                            "campaign not found: " + campaignId));
            settleExpired(campaignId, now);

            consentRepository.ensureScope(visitorId, campaign.category(), now);
            consentRepository.lockScope(visitorId, campaign.category());
            Optional<Consent> consent = resolveConsent(visitorId, campaign.category(), now);
            Long hitVersion = consent.map(Consent::consentVersion).orElse(null);
            String reason = null;
            if (consent.isEmpty() || consent.get().decision() != ConsentDecision.ALLOW) {
                reason = FailureReason.CONSENT_DENIED;
            } else if (inSilenceWindow(campaign, now)) {
                reason = FailureReason.SILENCE_PERIOD;
            } else if (hitsFrequencyLimit(campaign, visitorId, now)) {
                reason = FailureReason.FREQUENCY_LIMIT;
            } else {
                LocalDate utcDate = LocalDate.ofInstant(
                        java.time.Instant.ofEpochMilli(now), ZoneOffset.UTC);
                int usedTotal = ledgerRepository.getUsedTotal(campaignId, utcDate);
                int usedVisitor = ledgerRepository.getUsedVisitor(campaignId, visitorId, utcDate);
                if (usedTotal + 1 > campaign.dailyTotalCap()
                        || usedVisitor + 1 > campaign.perVisitorDailyCap()) {
                    reason = FailureReason.BUDGET_EXHAUSTED;
                }
            }
            return new EvaluationResponse(campaignId, campaign.version(), campaign.category(),
                    visitorId, now, reason == null, reason, hitVersion);
        });
    }

    // ---- 预占联合裁决（作用域末尾） ----

    /**
     * 在当前事务内按固定顺序（同意域 → 公告 → 过期结算 → 静默 → 冷却 → 两级预算）
     * 为一组已去重访客创建预占。任一访客失败整批抛出、事务回滚，不留半成品。
     */
    private List<ReservationResponse> createReservations(ReplayGuard guard, String campaignId,
                                                         List<String> sortedVisitors, long now) {
        LocalDate utcDate = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(now), ZoneOffset.UTC);
        Campaign campaign = campaignRepository.lockById(campaignId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        FailureReason.NOT_FOUND,
                        "campaign not found: " + campaignId));

        // 1) 先按字典序锁全部裁决域，避免跨事务死锁
        for (String visitorId : sortedVisitors) {
            consentRepository.ensureScope(visitorId, campaign.category(), now);
            consentRepository.lockScope(visitorId, campaign.category());
        }
        // 取得全部串行化锁后再检测同键胜出者，避免在过期快照上继续执行
        guard.checkReplay();

        // 2) 结算该公告相关过期预占并释放额度（reservation -> ledger 锁序与 confirm/cancel 一致）
        settleExpired(campaign.campaignId(), now);

        // 3) 逐访客做不产生写入的前置裁决：同意、静默、冷却
        List<Consent> hitConsents = new ArrayList<>(sortedVisitors.size());
        for (String visitorId : sortedVisitors) {
            Optional<Consent> consent = resolveConsent(visitorId, campaign.category(), now);
            if (consent.isEmpty() || consent.get().decision() != ConsentDecision.ALLOW) {
                throw new ApiException(HttpStatus.FORBIDDEN, FailureReason.CONSENT_DENIED,
                        "consent denied for visitor " + visitorId
                                + " category " + campaign.category() + " at " + now);
            }
            if (inSilenceWindow(campaign, now)) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, FailureReason.SILENCE_PERIOD,
                        "request within silence window of campaign " + campaignId);
            }
            if (hitsFrequencyLimit(campaign, visitorId, now)) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, FailureReason.FREQUENCY_LIMIT,
                        "frequency limit hit for visitor " + visitorId);
            }
            hitConsents.add(consent.get());
        }

        // 4) 按所有访客最终账目联合预校验预算：先总账后逐访客账（访客已按字典序）
        ledgerRepository.ensureTotalRow(campaignId, utcDate);
        int usedTotal = ledgerRepository.lockUsedTotal(campaignId, utcDate);
        if (usedTotal + sortedVisitors.size() > campaign.dailyTotalCap()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, FailureReason.BUDGET_EXHAUSTED,
                    "exposure total quota exhausted for campaign " + campaignId);
        }
        for (String visitorId : sortedVisitors) {
            ledgerRepository.ensureVisitorRow(campaignId, visitorId, utcDate);
            int usedVisitor = ledgerRepository.lockUsedVisitor(campaignId, visitorId, utcDate);
            if (usedVisitor + 1 > campaign.perVisitorDailyCap()) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, FailureReason.BUDGET_EXHAUSTED,
                        "exposure visitor quota exhausted for visitor " + visitorId);
            }
        }

        // 5) 全部通过后统一占用额度并固化同意快照写预占单
        ledgerRepository.addTotal(campaignId, utcDate, sortedVisitors.size());
        List<ReservationResponse> responses = new ArrayList<>(sortedVisitors.size());
        for (int i = 0; i < sortedVisitors.size(); i++) {
            String visitorId = sortedVisitors.get(i);
            ledgerRepository.addVisitor(campaignId, visitorId, utcDate, 1);
            Consent consent = hitConsents.get(i);
            Reservation reservation = new Reservation(
                    newReservationId(),
                    campaignId,
                    visitorId,
                    java.sql.Date.valueOf(utcDate),
                    ReservationStatus.RESERVED,
                    campaign.category(),
                    consent.consentId(),
                    consent.consentVersion(),
                    ConsentDecision.ALLOW,
                    now,
                    now + RESERVATION_TTL_MILLIS,
                    null);
            reservationRepository.insert(reservation);
            responses.add(ReservationResponse.from(reservation));
        }
        return responses;
    }

    /**
     * 单个申请的 requestKey 指纹：含公告、活动版本、访客、类别、请求时刻所属 UTC 日与频控字段。
     * 时刻以 UTC 日粒度入纹，使同一请求在当日内重放返回最初判定；跨 UTC 日或任一业务参数
     * 变化（如类别修改导致版本变化）均判异参 409。
     */
    private String applyFingerprint(String campaignId, String visitorId, LocalDate utcDate) {
        Campaign campaign = requireCampaign(campaignId);
        return campaignId + "|" + campaign.version() + "|" + campaign.category() + "|"
                + visitorId + "|" + utcDate + "|" + campaign.silenceStartSec() + "|"
                + campaign.silenceEndSec() + "|" + campaign.minIntervalMillis();
    }

    /** 批量申请指纹：在单申请字段基础上纳入去重排序后的全部访客。 */
    private String batchFingerprint(String campaignId, List<String> sortedVisitors, LocalDate utcDate) {
        return applyFingerprint(campaignId, String.join(",", sortedVisitors), utcDate);
    }

    /**
     * 解析某裁决域在指定时刻命中的 ACTIVE 区间。时间轴区间互不重叠，命中至多一条；
     * 已撤回（WITHDRAWN）与被覆盖（SUPERSEDED）记录不参与。
     */
    private Optional<Consent> resolveConsent(String visitorId, String category, long atUtc) {
        return consentRepository.lockActiveIntervals(visitorId, category).stream()
                .filter(c -> c.effectiveStartUtc() <= atUtc && c.effectiveEndUtc() > atUtc)
                .reduce((first, second) -> second);
    }

    /**
     * 判断时刻是否落在静默窗口 [startSec, endSec) 内；起终点相等表示不启用，
     * 起点大于终点表示窗口跨 UTC 午夜。
     */
    private boolean inSilenceWindow(Campaign campaign, long nowUtc) {
        int start = campaign.silenceStartSec();
        int end = campaign.silenceEndSec();
        if (start == end) {
            return false;
        }
        int secondOfDay = OffsetDateTime
                .ofInstant(java.time.Instant.ofEpochMilli(nowUtc), ZoneOffset.UTC)
                .toLocalTime().toSecondOfDay();
        if (start < end) {
            return secondOfDay >= start && secondOfDay < end;
        }
        return secondOfDay >= start || secondOfDay < end;
    }

    /**
     * 冷却频控：若同访客在 (now - minInterval, now] 内存在仍有效（RESERVED/CONFIRMED）的
     * 曝光预占则命中。CANCELLED/EXPIRED 不算有效曝光。使用只读读取：同访客的新建预占
     * 已由裁决域父行锁串行化，无需对预占行加锁（避免与 confirm 的 reservation→ledger 锁序反转）。
     */
    private boolean hitsFrequencyLimit(Campaign campaign, String visitorId, long now) {
        long minInterval = campaign.minIntervalMillis();
        if (minInterval <= 0) {
            return false;
        }
        List<Reservation> recent = reservationRepository.findVisitorReservationsAfter(
                campaign.campaignId(), visitorId, now - minInterval);
        return recent.stream().anyMatch(r -> r.status() == ReservationStatus.RESERVED
                || r.status() == ReservationStatus.CONFIRMED);
    }

    // ---- 既有预占状态机与过期结算 ----

    /** 幂等操作类型，同时标识存储响应的反序列化类型。 */
    private enum Operation {
        CREATE_CAMPAIGN,
        UPDATE_CAMPAIGN_CATEGORY,
        GRANT_CONSENT,
        WITHDRAW_CONSENT,
        APPLY,
        BATCH_APPLY,
        CONFIRM,
        CANCEL
    }

    /**
     * 携带胜出事务已提交响应的内部控制信号：同键等待者在串行锁上阻塞到胜出者提交后，
     * 用它短路业务执行并直接重放最初结果。
     */
    private static final class ReplayWinnerException extends RuntimeException {
        private final transient Object value;

        ReplayWinnerException(Object value) {
            super(null, null, false, false);
            this.value = value;
        }
    }

    /**
     * 重放守卫：业务动作在取得串行化锁（裁决域父行/目标行）后调用 {@link #checkReplay}，
     * 若同键胜出事务已提交则直接重放其结果，避免在过期快照上继续执行而误报业务错误。
     * 胜出记录的操作类型或指纹不符时按异参重放返回 409。
     */
    private final class ReplayGuard {
        private final String requestId;
        private final Operation operation;
        private final String fingerprint;

        ReplayGuard(String requestId, Operation operation, String fingerprint) {
            this.requestId = requestId;
            this.operation = operation;
            this.fingerprint = fingerprint;
        }

        void checkReplay() {
            idempotencyRepository.findById(requestId).ifPresent(record -> {
                if (!record.operation().equals(operation.name())
                        || !record.requestFingerprint().equals(fingerprint)) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            FailureReason.IDEMPOTENCY_CONFLICT,
                            "idempotency key reused with different parameters");
                }
                throw new ReplayWinnerException(record.responseJson());
            });
        }
    }

    /**
     * 在事务内执行业务并维护幂等记录；业务变更与去重结果原子提交。
     * 并发同键插入冲突时等待胜出事务提交后重放其结果。业务动作须在取得自身串行化锁后
     * 调用守卫的 checkReplay，以处理“先通过无记录检查、后在锁上等到胜出者提交”的竞态。
     */
    private <T> T runIdempotent(String requestId, Operation operation, String fingerprint,
                                Class<T> responseType, java.util.function.Function<ReplayGuard, T> action) {
        long deadline = System.currentTimeMillis() + IDEMPOTENT_WAIT_MILLIS;
        ReplayGuard guard = new ReplayGuard(requestId, operation, fingerprint);
        while (true) {
            try {
                return txTemplate.execute(status -> {
                    var existing = idempotencyRepository.lockById(requestId);
                    if (existing.isPresent()) {
                        return replay(existing.get(), operation, fingerprint, responseType);
                    }
                    T result = action.apply(guard);
                    idempotencyRepository.insert(new IdempotencyRecord(
                            requestId, operation.name(), fingerprint, writeJson(result)),
                            clock.millis());
                    return result;
                });
            } catch (ReplayWinnerException winner) {
                // 等待者在串行锁上等到同键胜出事务提交：直接重放最初结果，不再执行业务
                return deserialize((String) winner.value, responseType);
            } catch (DuplicateKeyException duplicate) {
                // 同键并发事务抢先插入（可能尚未提交），等待后重放
                if (System.currentTimeMillis() >= deadline) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            FailureReason.IDEMPOTENCY_CONFLICT,
                            "concurrent idempotency key conflict: " + requestId);
                }
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                            FailureReason.INTERNAL_ERROR, "interrupted");
                }
            }
        }
    }

    private <T> T replay(IdempotencyRecord record, Operation operation, String fingerprint,
                         Class<T> responseType) {
        if (!record.operation().equals(operation.name())
                || !record.requestFingerprint().equals(fingerprint)) {
            throw new ApiException(HttpStatus.CONFLICT, FailureReason.IDEMPOTENCY_CONFLICT,
                    "idempotency key reused with different parameters");
        }
        return deserialize(record.responseJson(), responseType);
    }

    private <T> T deserialize(String json, Class<T> responseType) {
        try {
            return objectMapper.readValue(json, responseType);
        } catch (Exception e) {
            throw new IllegalStateException("failed to replay idempotent response", e);
        }
    }

    /**
     * 确认/取消状态机。调用前已持幂等键；行锁 + CAS 保证并发只有一个终态。
     *
     * @param isConfirm true=确认，false=取消
     */
    private ReservationResponse transition(ReplayGuard guard, String reservationId, boolean isConfirm) {
        long now = clock.millis();
        Reservation current = reservationRepository.lockById(reservationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        FailureReason.NOT_FOUND,
                        "reservation not found: " + reservationId));
        // 同键并发等待者在预占行锁上等到胜出者提交后，直接重放最初结果
        guard.checkReplay();

        // 仅结算本单（本事务已持其行锁，加锁顺序保持为 预占单 -> 总账 -> 访客账，避免死锁）
        expireIfDue(current, now);
        current = reservationRepository.lockById(reservationId).orElseThrow();

        if (current.status() == ReservationStatus.RESERVED) {
            // 结算后仍为 RESERVED 说明 now < expiresAt，确认严格要求在到期时刻之前
            if (isConfirm) {
                if (!reservationRepository.compareAndSetStatus(
                        reservationId, ReservationStatus.RESERVED, ReservationStatus.CONFIRMED, now)) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            FailureReason.RESERVATION_STATE_CONFLICT,
                            "reservation state changed concurrently");
                }
            } else {
                if (!reservationRepository.compareAndSetStatus(
                        reservationId, ReservationStatus.RESERVED, ReservationStatus.CANCELLED, now)) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            FailureReason.RESERVATION_STATE_CONFLICT,
                            "reservation state changed concurrently");
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
                        FailureReason.RESERVATION_STATE_CONFLICT,
                        "reservation is " + current.status() + ", cannot "
                                + (isConfirm ? "confirm" : "cancel"));
            }
            // 重复同类终态操作：返回原状态，不重复释放额度
        } else {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    FailureReason.INTERNAL_ERROR,
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
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, FailureReason.NOT_FOUND,
                        "campaign not found: " + campaignId));
    }

    private ConsentDecision parseDecision(String raw) {
        try {
            return ConsentDecision.valueOf(raw);
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(HttpStatus.BAD_REQUEST, FailureReason.INVALID_REQUEST,
                    "decision must be ALLOW or DENY");
        }
    }

    /** 以旧版本同意为模板生成一段同版本残片（覆盖截断时保留未被覆盖的区间）。 */
    private Consent fragment(Consent base, String consentId, long start, long end, long now) {
        return new Consent(consentId, base.visitorId(), base.category(), base.decision(),
                base.consentVersion(), start, end, ConsentStatus.ACTIVE, now, null);
    }

    private String newConsentId() {
        return "csm_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String newReservationId() {
        return "rsv_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }
}
