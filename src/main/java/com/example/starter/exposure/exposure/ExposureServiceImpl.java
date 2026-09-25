package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.Consent;
import com.example.starter.exposure.domain.ConsentDecision;
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
import com.example.starter.exposure.web.DecisionPreviewResponse;
import com.example.starter.exposure.web.ErrorCodes;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SubmitConsentRequest;
import com.example.starter.exposure.web.UpdateCategoryRequest;
import com.example.starter.exposure.web.WithdrawConsentRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 公告曝光频控与同意联合裁决业务服务实现。
 *
 * <p>创建预占时严格按 同意 → 静默时段 → 访客频控 → 公告总预算 顺序裁决：
 * 缺少 ALLOW 或命中 DENY 返回 {@code CONSENT_DENIED}，命中静默返回 {@code SILENT_HOURS}，
 * 二者均不创建预占、不触碰频次与预算账目；额度不足返回 {@code QUOTA_EXHAUSTED}。
 * 同意决定与版本在预占创建时固化，撤回同意与活动类别变更均不影响已建预占。</p>
 *
 * <p>批量预占在单事务内按所有访客的最终账目一次性预校验，任一失败整批回滚。
 * 所有写操作以 requestId 为全局幂等键：指纹含活动版本、访客、类别与频控字段，
 * 首次裁决时刻随幂等记录固化；同键重放返回最初判定，异参 409，失败不占键。</p>
 */
@Service
public class ExposureServiceImpl implements ExposureService {

    static final int RESERVATION_TTL_MILLIS = 60_000;

    /** 并发同键竞争时等待胜出事务提交的最大时长。 */
    private static final long IDEMPOTENT_WAIT_MILLIS = 10_000L;

    private final Clock clock;
    private final CampaignRepository campaignRepository;
    private final ConsentRepository consentRepository;
    private final ReservationRepository reservationRepository;
    private final LedgerRepository ledgerRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public ExposureServiceImpl(Clock clock,
                               CampaignRepository campaignRepository,
                               ConsentRepository consentRepository,
                               ReservationRepository reservationRepository,
                               LedgerRepository ledgerRepository,
                               IdempotencyRepository idempotencyRepository,
                               ObjectMapper objectMapper,
                               TransactionTemplate txTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.consentRepository = consentRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
    }

    @Override
    public CampaignResponse createCampaign(CreateCampaignRequest request) {
        validateSilentWindow(request.silentStartMinute(), request.silentEndMinute());
        String fingerprint = request.campaignId() + "|" + request.dailyTotalCap() + "|"
                + request.perVisitorDailyCap() + "|" + normalize(request.category()) + "|"
                + request.silentStartMinute() + "|" + request.silentEndMinute();
        return runIdempotent(request.requestId(), Operation.CREATE_CAMPAIGN, () -> fingerprint,
                CampaignResponse.class, () -> {
                    if (campaignRepository.findById(request.campaignId()).isPresent()) {
                        throw conflict("campaign already exists: " + request.campaignId());
                    }
                    Campaign campaign = new Campaign(
                            request.campaignId(),
                            request.dailyTotalCap(),
                            request.perVisitorDailyCap(),
                            clock.millis(),
                            blankToNull(request.category()),
                            1,
                            request.silentStartMinute(),
                            request.silentEndMinute());
                    try {
                        campaignRepository.insert(campaign);
                    } catch (DuplicateKeyException duplicateCampaign) {
                        // 并发创建同一 campaignId：明确返回 409，而非误报幂等键冲突
                        throw conflict("campaign already exists: " + request.campaignId());
                    }
                    return CampaignResponse.from(campaign);
                });
    }

    @Override
    public CampaignResponse updateCategory(String campaignId, UpdateCategoryRequest request) {
        if (!campaignId.equals(request.campaignId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST,
                    "campaign id in path does not match request body");
        }
        String fingerprint = request.campaignId() + "|" + request.newCategory();
        return runIdempotent(request.requestId(), Operation.UPDATE_CATEGORY, () -> fingerprint,
                CampaignResponse.class, () -> {
                    Campaign current = campaignRepository.lockById(request.campaignId())
                            .orElseThrow(() -> notFound(
                                    "campaign not found: " + request.campaignId()));
                    // 旧同意绑定旧类别，不迁移；版本 +1 使新类别预占必须重新取得同意
                    Campaign updated = new Campaign(
                            current.campaignId(),
                            current.dailyTotalCap(),
                            current.perVisitorDailyCap(),
                            current.createdAtUtc(),
                            request.newCategory(),
                            current.version() + 1,
                            current.silentStartMinute(),
                            current.silentEndMinute());
                    campaignRepository.updateCategory(
                            updated.campaignId(), updated.category(), updated.version());
                    return CampaignResponse.from(updated);
                });
    }

    @Override
    public ReservationResponse apply(ApplyExposureRequest request) {
        List<BatchApplyRequest.BatchApplyItem> items = List.of(
                new BatchApplyRequest.BatchApplyItem(request.campaignId(), request.visitorId()));
        return runIdempotent(request.requestId(), Operation.APPLY,
                () -> lockedApplyFingerprint(items), ReservationResponse.class,
                () -> ReservationResponse.from(createReservations(items).get(0)));
    }

    @Override
    public BatchApplyResponse batchApply(BatchApplyRequest request) {
        List<BatchApplyRequest.BatchApplyItem> items = request.items();
        return runIdempotent(request.requestId(), Operation.BATCH_APPLY,
                () -> lockedApplyFingerprint(items), BatchApplyResponse.class,
                () -> new BatchApplyResponse(createReservations(items).stream()
                        .map(ReservationResponse::from).toList()));
    }

    @Override
    public ReservationResponse confirm(String reservationId, ReservationActionRequest request) {
        return runIdempotent(request.requestId(), Operation.CONFIRM, () -> reservationId,
                ReservationResponse.class, () -> transition(reservationId, true));
    }

    @Override
    public ReservationResponse cancel(String reservationId, ReservationActionRequest request) {
        return runIdempotent(request.requestId(), Operation.CANCEL, () -> reservationId,
                ReservationResponse.class, () -> transition(reservationId, false));
    }

    @Override
    public ReservationResponse getReservation(String reservationId) {
        return txTemplate.execute(status -> {
            Reservation locked = reservationRepository.lockById(reservationId)
                    .orElseThrow(() -> notFound("reservation not found: " + reservationId));
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
    public ConsentResponse submitConsent(SubmitConsentRequest request) {
        Long requestedEnd = request.effectiveEndUtc();
        if (requestedEnd != null && requestedEnd <= request.effectiveStartUtc()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.CONSENT_INVALID_RANGE,
                    "effective end must be later than effective start (left-closed right-open)");
        }
        String fingerprint = request.visitorId() + "|" + request.category() + "|"
                + request.decision() + "|" + request.consentVersion() + "|"
                + request.effectiveStartUtc() + "|" + requestedEnd;
        return runIdempotent(request.requestId(), Operation.SUBMIT_CONSENT, () -> fingerprint,
                ConsentResponse.class, () -> {
                    // 先持 访客+类别 互斥量，串行化同维度提交，杜绝并发绕过重叠检查
                    consentRepository.lockMutex(request.visitorId(), request.category());
                    List<Consent> overlapping = consentRepository.lockOverlapping(
                            request.visitorId(), request.category(),
                            request.effectiveStartUtc(), requestedEnd);
                    ConsentDecision decision = ConsentDecision.valueOf(request.decision());
                    for (Consent existing : overlapping) {
                        if (existing.consentVersion() == request.consentVersion()) {
                            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONSENT_CONFLICT,
                                    "consent interval of same version overlaps existing interval: "
                                            + existing.consentId());
                        }
                        // DENY 不可被低版本覆盖：低版本 ALLOW 与高版本 DENY 重叠时拒绝
                        if (decision == ConsentDecision.ALLOW
                                && existing.decision() == ConsentDecision.DENY
                                && request.consentVersion() < existing.consentVersion()) {
                            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONSENT_CONFLICT,
                                    "lower version ALLOW cannot override higher version DENY: "
                                            + existing.consentId());
                        }
                    }
                    Consent consent = new Consent(
                            UUID.randomUUID().toString().replace("-", ""),
                            request.visitorId(),
                            request.category(),
                            decision,
                            request.consentVersion(),
                            request.effectiveStartUtc(),
                            requestedEnd,
                            clock.millis());
                    consentRepository.insert(consent);
                    return ConsentResponse.from(consent);
                });
    }

    @Override
    public ConsentResponse withdrawConsent(String consentId, WithdrawConsentRequest request) {
        return runIdempotent(request.requestId(), Operation.WITHDRAW_CONSENT, () -> consentId,
                ConsentResponse.class, () -> {
                    Consent consent = consentRepository.lockById(consentId)
                            .orElseThrow(() -> notFound("consent not found: " + consentId));
                    long now = clock.millis();
                    if (consent.effectiveEndUtc() != null && consent.effectiveEndUtc() <= now) {
                        throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONSENT_CONFLICT,
                                "consent interval already expired: " + consentId);
                    }
                    Consent result;
                    if (consent.effectiveStartUtc() >= now) {
                        // 尚未生效（含恰在起点的同毫秒）：直接删除，任何预占都不可能固化过该区间
                        consentRepository.deleteById(consentId);
                        result = new Consent(consent.consentId(), consent.visitorId(),
                                consent.category(), consent.decision(), consent.consentVersion(),
                                consent.effectiveStartUtc(), now, consent.createdAtUtc());
                    } else {
                        // 生效中：终点截断为撤回时刻（左闭右开），只影响之后的预占
                        consentRepository.truncateEnd(consentId, now);
                        result = new Consent(consent.consentId(), consent.visitorId(),
                                consent.category(), consent.decision(), consent.consentVersion(),
                                consent.effectiveStartUtc(), now, consent.createdAtUtc());
                    }
                    return ConsentResponse.from(result);
                });
    }

    @Override
    public List<ConsentResponse> queryConsents(String visitorId, String category) {
        return consentRepository.findByVisitorAndCategory(visitorId, category).stream()
                .map(ConsentResponse::from).toList();
    }

    @Override
    public DecisionPreviewResponse previewDecision(String campaignId, String visitorId) {
        return txTemplate.execute(status -> {
            Campaign campaign = campaignRepository.lockById(campaignId)
                    .orElseThrow(() -> notFound("campaign not found: " + campaignId));
            long now = clock.millis();
            LocalDate utcDate = LocalDate.now(clock);
            settleExpired(campaignId, now);

            String decisionName = null;
            Integer consentVersion = null;
            String reason = null;

            // 1. 同意（只读无锁查询）
            if (campaign.category() != null) {
                List<Consent> effective = consentRepository.findEffectiveAt(
                        visitorId, campaign.category(), now);
                if (effective.isEmpty()) {
                    reason = ErrorCodes.CONSENT_DENIED;
                } else {
                    Consent highest = effective.get(effective.size() - 1);
                    decisionName = highest.decision().name();
                    consentVersion = highest.consentVersion();
                    if (highest.decision() == ConsentDecision.DENY) {
                        reason = ErrorCodes.CONSENT_DENIED;
                    }
                }
            }

            // 2. 静默时段
            if (reason == null && inSilentWindow(campaign, now)) {
                reason = ErrorCodes.SILENT_HOURS;
            }

            int usedTotal = ledgerRepository.getUsedTotal(campaignId, utcDate);
            int usedVisitor = ledgerRepository.getUsedVisitor(campaignId, visitorId, utcDate);

            // 3/4. 访客频控与公告总预算
            if (reason == null
                    && (usedVisitor + 1 > campaign.perVisitorDailyCap()
                    || usedTotal + 1 > campaign.dailyTotalCap())) {
                reason = ErrorCodes.QUOTA_EXHAUSTED;
            }
            if (reason == null) {
                reason = DecisionPreviewResponse.ALLOWED;
            }

            return new DecisionPreviewResponse(
                    campaignId, visitorId, campaign.category(), campaign.version(), now,
                    DecisionPreviewResponse.ALLOWED.equals(reason), reason, decisionName,
                    consentVersion, usedTotal, usedVisitor);
        });
    }

    // ---- 内部辅助（作用域末尾） ----

    /** 幂等操作类型，同时标识存储响应的反序列化类型。 */
    private enum Operation {
        CREATE_CAMPAIGN,
        APPLY,
        BATCH_APPLY,
        CONFIRM,
        CANCEL,
        SUBMIT_CONSENT,
        WITHDRAW_CONSENT,
        UPDATE_CATEGORY
    }

    /** 同意裁决结论；公告未启用同意裁决时不产生该对象。 */
    private record ConsentVerdict(ConsentDecision decision, int version) {
    }

    /** 同意维度键：访客 + 活动类别；强类型避免 ID 含分隔符时拼接歧义。 */
    private record ConsentKey(String visitorId, String category)
            implements Comparable<ConsentKey> {
        @Override
        public int compareTo(ConsentKey other) {
            int byVisitor = visitorId.compareTo(other.visitorId);
            return byVisitor != 0 ? byVisitor : category.compareTo(other.category);
        }
    }

    /** 账目键：总额账目 visitorId 为 null。 */
    private record LedgerKey(String campaignId, String visitorId, LocalDate utcDate) {
    }

    /**
     * 预占指纹（必须在事务内、持幂等键后调用）：先按公告编号排序锁定全部涉及公告行，
     * 再基于与后续裁决相同的公告快照拼接 活动版本、访客、类别、时刻依据与频控字段，
     * 杜绝与类别修改并发时出现“指纹用旧版本、裁决用新版本”的不一致。
     * “时刻”为首次裁决时刻，由 {@link #runIdempotent} 插入幂等记录时附加并固化。
     */
    private String lockedApplyFingerprint(List<BatchApplyRequest.BatchApplyItem> items) {
        TreeSet<String> campaignIds = new TreeSet<>();
        for (BatchApplyRequest.BatchApplyItem item : items) {
            campaignIds.add(item.campaignId());
        }
        TreeMap<String, Campaign> locked = new TreeMap<>();
        for (String campaignId : campaignIds) {
            locked.put(campaignId, campaignRepository.lockById(campaignId)
                    .orElseThrow(() -> notFound("campaign not found: " + campaignId)));
        }
        StringBuilder sb = new StringBuilder("APPLY-BATCH|").append(items.size());
        for (BatchApplyRequest.BatchApplyItem item : items) {
            Campaign campaign = locked.get(item.campaignId());
            sb.append(';')
                    .append("APPLY|").append(campaign.campaignId())
                    .append("|v").append(campaign.version())
                    .append('|').append(item.visitorId())
                    .append("|cat=").append(normalize(campaign.category()))
                    .append("|cap=").append(campaign.dailyTotalCap())
                    .append("|vcap=").append(campaign.perVisitorDailyCap());
        }
        return sb.toString();
    }

    /**
     * 在单事务内完成一批预占（单条申请即批量大小为 1）。
     * 固定锁序 公告行 → 同意互斥量/区间行 → 总账行 → 访客账行；
     * 先按全部条目的最终账目预校验，任一同意/静默/频控/预算失败抛异常，
     * 整批回滚，不留任何预占与账目增量。
     */
    private List<Reservation> createReservations(List<BatchApplyRequest.BatchApplyItem> items) {
        long now = clock.millis();
        LocalDate utcDate = LocalDate.now(clock);

        // 1. 固定顺序锁定全部涉及公告并结算其过期预占
        TreeMap<String, Campaign> campaigns = new TreeMap<>();
        for (BatchApplyRequest.BatchApplyItem item : items) {
            campaigns.put(item.campaignId(), requireCampaign(item.campaignId()));
        }
        for (String campaignId : new ArrayList<>(campaigns.keySet())) {
            Campaign locked = campaignRepository.lockById(campaignId)
                    .orElseThrow(() -> notFound("campaign not found: " + campaignId));
            campaigns.put(campaignId, locked);
            settleExpired(campaignId, now);
        }

        // 2. 同意裁决：按 (访客,类别) 去重排序加锁，同键结论在本批内复用
        TreeMap<ConsentKey, ConsentVerdict> verdicts = new TreeMap<>();
        TreeSet<ConsentKey> consentKeys = new TreeSet<>();
        for (BatchApplyRequest.BatchApplyItem item : items) {
            Campaign campaign = campaigns.get(item.campaignId());
            if (campaign.category() != null) {
                consentKeys.add(new ConsentKey(item.visitorId(), campaign.category()));
            }
        }
        for (ConsentKey key : consentKeys) {
            verdicts.put(key, resolveConsent(key.visitorId(), key.category(), now));
        }

        // 3. 逐条裁决静默并汇总两级账目最终增量；此阶段尚未触碰账目行
        Map<LedgerKey, Integer> totalDelta = new HashMap<>();
        Map<LedgerKey, Integer> visitorDelta = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            BatchApplyRequest.BatchApplyItem item = items.get(i);
            Campaign campaign = campaigns.get(item.campaignId());
            if (campaign.category() != null
                    && !verdicts.containsKey(
                            new ConsentKey(item.visitorId(), campaign.category()))) {
                // 理论上不会到达：resolveConsent 对缺失 ALLOW/DENY 已直接抛错
                throw consentDenied("batch item " + i + " visitor " + item.visitorId()
                        + " lacks ALLOW consent for category " + campaign.category());
            }
            if (inSilentWindow(campaign, now)) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.SILENT_HOURS,
                        "batch item " + i + " campaign " + campaign.campaignId()
                                + " is within silent hours at request time");
            }
            totalDelta.merge(new LedgerKey(campaign.campaignId(), null, utcDate), 1, Integer::sum);
            visitorDelta.merge(
                    new LedgerKey(campaign.campaignId(), item.visitorId(), utcDate), 1, Integer::sum);
        }

        // 4. 固定锁序锁定账目行：先全部公告总账（公告编号序），再全部访客账（公告+访客序）。
        //    ensure（可能插入新行并持锁）与 FOR UPDATE 均按同一确定性顺序，杜绝跨事务死锁。
        TreeSet<LedgerKey> totalKeys = new TreeSet<>(
                Comparator.comparing(LedgerKey::campaignId).thenComparing(k -> k.utcDate().toString()));
        totalKeys.addAll(totalDelta.keySet());
        TreeSet<LedgerKey> visitorKeys = new TreeSet<>(
                Comparator.comparing(LedgerKey::campaignId)
                        .thenComparing(LedgerKey::visitorId)
                        .thenComparing(k -> k.utcDate().toString()));
        visitorKeys.addAll(visitorDelta.keySet());
        for (LedgerKey key : totalKeys) {
            ledgerRepository.ensureTotalRow(key.campaignId(), utcDate);
        }
        for (LedgerKey key : visitorKeys) {
            ledgerRepository.ensureVisitorRow(key.campaignId(), key.visitorId(), utcDate);
        }
        TreeMap<LedgerKey, Integer> lockedTotal = new TreeMap<>(
                Comparator.comparing(LedgerKey::campaignId).thenComparing(k -> k.utcDate().toString()));
        for (LedgerKey key : totalKeys) {
            lockedTotal.put(key, ledgerRepository.lockUsedTotal(key.campaignId(), utcDate));
        }
        TreeMap<LedgerKey, Integer> lockedVisitor = new TreeMap<>(
                Comparator.comparing(LedgerKey::campaignId)
                        .thenComparing(LedgerKey::visitorId)
                        .thenComparing(k -> k.utcDate().toString()));
        for (LedgerKey key : visitorKeys) {
            lockedVisitor.put(key,
                    ledgerRepository.lockUsedVisitor(key.campaignId(), key.visitorId(), utcDate));
        }

        // 5. 按最终账目一次性校验容量，任一超限整批失败（尚未写入任何增量）
        for (Map.Entry<LedgerKey, Integer> entry : lockedTotal.entrySet()) {
            Campaign campaign = campaigns.get(entry.getKey().campaignId());
            int finalUsed = entry.getValue() + totalDelta.get(entry.getKey());
            if (finalUsed > campaign.dailyTotalCap()) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.QUOTA_EXHAUSTED,
                        "daily total quota exhausted for campaign " + campaign.campaignId()
                                + " (final " + finalUsed + " > cap " + campaign.dailyTotalCap() + ")");
            }
        }
        for (Map.Entry<LedgerKey, Integer> entry : lockedVisitor.entrySet()) {
            Campaign campaign = campaigns.get(entry.getKey().campaignId());
            int finalUsed = entry.getValue() + visitorDelta.get(entry.getKey());
            if (finalUsed > campaign.perVisitorDailyCap()) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.QUOTA_EXHAUSTED,
                        "per-visitor daily quota exhausted for campaign " + campaign.campaignId()
                                + " visitor " + entry.getKey().visitorId()
                                + " (final " + finalUsed + " > cap "
                                + campaign.perVisitorDailyCap() + ")");
            }
        }

        // 6. 全部通过：写入增量账目并创建预占（固化同意快照）
        List<Reservation> created = new ArrayList<>();
        for (BatchApplyRequest.BatchApplyItem item : items) {
            Campaign campaign = campaigns.get(item.campaignId());
            ledgerRepository.addTotal(campaign.campaignId(), utcDate, 1);
            ledgerRepository.addVisitor(campaign.campaignId(), item.visitorId(), utcDate, 1);

            ConsentVerdict verdict = campaign.category() == null
                    ? null
                    : verdicts.get(new ConsentKey(item.visitorId(), campaign.category()));
            Reservation reservation = new Reservation(
                    UUID.randomUUID().toString().replace("-", ""),
                    campaign.campaignId(),
                    item.visitorId(),
                    java.sql.Date.valueOf(utcDate),
                    ReservationStatus.RESERVED,
                    now,
                    now + RESERVATION_TTL_MILLIS,
                    null,
                    verdict == null ? null : verdict.decision(),
                    verdict == null ? null : verdict.version());
            reservationRepository.insert(reservation);
            created.add(reservation);
        }
        return created;
    }

    /**
     * 裁决某访客某类别在指定时刻的有效同意：命中区间按版本升序取最高版本，
     * 最高版本为 DENY 或没有任何生效区间时抛 {@code CONSENT_DENIED}（不区分缺 ALLOW 与 DENY，
     * 均不创建预占、不扣频次预算；DENY 场景消息中带版本便于排查）。
     * 本方法以行锁读取同意区间，调用方已持有相关公告行锁。
     */
    private ConsentVerdict resolveConsent(String visitorId, String category, long atUtc) {
        List<Consent> effective = consentRepository.lockEffectiveAt(visitorId, category, atUtc);
        if (effective.isEmpty()) {
            throw consentDenied("visitor " + visitorId
                    + " has no effective ALLOW consent for category " + category + " at request time");
        }
        Consent highest = effective.get(effective.size() - 1);
        if (highest.decision() == ConsentDecision.DENY) {
            throw consentDenied("visitor " + visitorId + " consent DENY (version "
                    + highest.consentVersion() + ") for category " + category);
        }
        return new ConsentVerdict(ConsentDecision.ALLOW, highest.consentVersion());
    }

    /**
     * 判断时刻（epoch 毫秒，UTC）是否落在公告静默时段内。
     * 窗口 [start, end) 按 UTC 日内分钟左闭右开；end &le; start 表示跨 UTC 午夜。
     */
    private boolean inSilentWindow(Campaign campaign, long atUtc) {
        Integer start = campaign.silentStartMinute();
        Integer end = campaign.silentEndMinute();
        if (start == null || end == null) {
            return false;
        }
        ZonedDateTime zoned = Instant.ofEpochMilli(atUtc).atZone(ZoneOffset.UTC);
        int minute = zoned.getHour() * 60 + zoned.getMinute();
        if (end <= start) {
            return minute >= start || minute < end;
        }
        return minute >= start && minute < end;
    }

    /**
     * 在事务内执行业务并维护幂等记录；业务变更与去重结果原子提交。
     * 并发同键插入冲突时等待胜出事务提交后重放其结果。
     *
     * <p>指纹在事务内、持幂等键后由 {@code fingerprintSupplier} 计算（预占场景此时已锁公告行，
     * 与裁决使用同一快照）；存储指纹附加首次裁决时刻（|at=&lt;millis&gt;），
     * 重放比较只比对稳定前缀，从而同键跨时刻重放仍返回最初裁决。</p>
     */
    private <T> T runIdempotent(String requestId, Operation operation,
                                Supplier<String> fingerprintSupplier,
                                Class<T> responseType, Supplier<T> action) {
        long deadline = System.currentTimeMillis() + IDEMPOTENT_WAIT_MILLIS;
        while (true) {
            try {
                return txTemplate.execute(status -> {
                    var existing = idempotencyRepository.lockById(requestId);
                    String fingerprint = fingerprintSupplier.get();
                    if (existing.isPresent()) {
                        IdempotencyRecord record = existing.get();
                        if (!record.operation().equals(operation.name())
                                || !stableFingerprint(record.requestFingerprint())
                                .equals(stableFingerprint(fingerprint))) {
                            throw conflict(
                                    "idempotency key reused with different parameters: " + requestId);
                        }
                        try {
                            return objectMapper.readValue(record.responseJson(), responseType);
                        } catch (Exception e) {
                            throw new IllegalStateException("failed to replay idempotent response", e);
                        }
                    }
                    T result = action.get();
                    long decidedAt = clock.millis();
                    idempotencyRepository.insert(new IdempotencyRecord(
                            requestId, operation.name(),
                            fingerprint + "|at=" + decidedAt, writeJson(result)), decidedAt);
                    return result;
                });
            } catch (DuplicateKeyException duplicate) {
                // 同键并发事务抢先插入（可能尚未提交），等待后重放
                if (System.currentTimeMillis() >= deadline) {
                    throw conflict("concurrent idempotency key conflict: " + requestId);
                }
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                            "interrupted");
                }
            }
        }
    }

    /** 剥离指纹中由服务端固化的首次裁决时刻后缀，只保留稳定业务参数用于异参判定。 */
    private static String stableFingerprint(String stored) {
        int idx = stored.lastIndexOf("|at=");
        return idx >= 0 ? stored.substring(0, idx) : stored;
    }

    /**
     * 确认/取消状态机。调用前已持幂等键；行锁 + CAS 保证并发只有一个终态。
     *
     * @param isConfirm true=确认，false=取消
     */
    private ReservationResponse transition(String reservationId, boolean isConfirm) {
        long now = clock.millis();
        Reservation current = reservationRepository.lockById(reservationId)
                .orElseThrow(() -> notFound("reservation not found: " + reservationId));

        // 仅结算本单（本事务已持其行锁，加锁顺序保持为 预占单 -> 总账 -> 访客账，避免死锁）
        expireIfDue(current, now);
        current = reservationRepository.lockById(reservationId).orElseThrow();

        if (current.status() == ReservationStatus.RESERVED) {
            // 结算后仍为 RESERVED 说明 now < expiresAt，确认严格要求在到期时刻之前
            if (isConfirm) {
                if (!reservationRepository.compareAndSetStatus(
                        reservationId, ReservationStatus.RESERVED, ReservationStatus.CONFIRMED, now)) {
                    throw conflict("reservation state changed concurrently");
                }
            } else {
                if (!reservationRepository.compareAndSetStatus(
                        reservationId, ReservationStatus.RESERVED, ReservationStatus.CANCELLED, now)) {
                    throw conflict("reservation state changed concurrently");
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
                throw conflict("reservation is " + current.status() + ", cannot "
                        + (isConfirm ? "confirm" : "cancel"));
            }
            // 重复同类终态操作：返回原状态，不重复释放额度
        } else {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
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
                .orElseThrow(() -> notFound("campaign not found: " + campaignId));
    }

    private void validateSilentWindow(Integer start, Integer end) {
        if (start == null && end == null) {
            return;
        }
        if (start == null || end == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST,
                    "silentStartMinute and silentEndMinute must be provided together");
        }
        // start: 0-1439；end: 1-1440；相等（零长度窗口）无意义，拒绝
        if (start.equals(end)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST,
                    "silent window must not be empty (start == end)");
        }
    }

    private static ApiException consentDenied(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.CONSENT_DENIED, message);
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONFLICT, message);
    }

    private static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, message);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }
}
