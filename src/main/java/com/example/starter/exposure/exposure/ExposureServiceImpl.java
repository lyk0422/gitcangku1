package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.domain.SuppressionInterval;
import com.example.starter.exposure.domain.SuppressionIntervalStatus;
import com.example.starter.exposure.repo.CampaignRepository;
import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.exposure.repo.LedgerRepository;
import com.example.starter.exposure.repo.ReservationRepository;
import com.example.starter.exposure.repo.SuppressionIntervalRepository;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.ApplyResult;
import com.example.starter.exposure.web.BatchUpdateSuppressionListRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.CreateSuppressionIntervalRequest;
import com.example.starter.exposure.web.EndSuppressionIntervalRequest;
import com.example.starter.exposure.web.IdempotentRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SuppressedResponse;
import com.example.starter.exposure.web.SuppressionIntervalResponse;
import com.example.starter.exposure.web.SuppressionListResponse;
import com.example.starter.exposure.web.VisitorSuppressionStatusResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 公告曝光频控与访客抑制名单业务服务实现。
 *
 * <p>所有写操作以 requestId 为全局幂等键：同键同参重放原成功结果，异参 409；
 * 业务失败随事务回滚，不占幂等键。所有操作与额度查询先结算相关过期预占，
 * 不依赖后台定时器。终态竞争由行锁 + 状态 CAS 保证只允许一个终态。</p>
 *
 * <p>抑制名单：当前时刻命中生效区间（UTC 左闭右开）时曝光申请返回 SUPPRESSED，
 * 不创建预占、不扣频次或预算。曝光裁决与名单变更都先持公告行锁，
 * 名单变更、预占、回执和撤回并发按事务提交顺序裁决。已创建的预占不因新增抑制回滚，
 * 后续回执仍按既有规则结算。</p>
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
    private final SuppressionIntervalRepository suppressionRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public ExposureServiceImpl(Clock clock,
                               CampaignRepository campaignRepository,
                               ReservationRepository reservationRepository,
                               LedgerRepository ledgerRepository,
                               IdempotencyRepository idempotencyRepository,
                               SuppressionIntervalRepository suppressionRepository,
                               ObjectMapper objectMapper,
                               TransactionTemplate txTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.suppressionRepository = suppressionRepository;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
    }

    @Override
    public CampaignResponse createCampaign(CreateCampaignRequest request) {
        String fingerprint = request.campaignId() + "|" + request.dailyTotalCap() + "|"
                + request.perVisitorDailyCap();
        return runIdempotent(request.requestId(), Operation.CREATE_CAMPAIGN, () -> fingerprint,
                CampaignResponse.class, () -> {
                    if (campaignRepository.findById(request.campaignId()).isPresent()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "campaign already exists: " + request.campaignId());
                    }
                    Campaign campaign = new Campaign(
                            request.campaignId(),
                            request.dailyTotalCap(),
                            request.perVisitorDailyCap(),
                            0L,
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
    public ApplyResult apply(ApplyExposureRequest request) {
        return runIdempotent(request.requestId(), Operation.APPLY,
                () -> applyFingerprint(request),
                ApplyResult.class, () -> {
                    long now = clock.millis();
                    LocalDate utcDate = LocalDate.now(clock);
                    Campaign campaign = requireCampaignLocked(request.campaignId());

                    // 命中抑制名单：返回 SUPPRESSED，不创建预占、不结算、不扣频次或预算
                    Optional<SuppressionInterval> matched = suppressionRepository.findMatched(
                            campaign.campaignId(), request.visitorId(), now);
                    if (matched.isPresent()) {
                        SuppressionInterval interval = matched.get();
                        return new SuppressedResponse(
                                campaign.campaignId(),
                                request.visitorId(),
                                request.placementId(),
                                SuppressedResponse.STATUS,
                                SuppressedResponse.REASON,
                                interval.intervalId(),
                                interval.validFromUtc(),
                                interval.validUntilUtc(),
                                now);
                    }

                    // 先结算该公告相关过期预占并释放额度
                    settleExpired(campaign.campaignId(), now);

                    // 固定加锁顺序：公告当日总账 -> 访客当日账，避免死锁
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
                            null);
                    reservationRepository.insert(reservation);
                    return ReservationResponse.from(reservation);
                });
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
    public SuppressionIntervalResponse createSuppressionInterval(String campaignId,
                                                                 CreateSuppressionIntervalRequest request) {
        String fingerprint = campaignId + "|" + request.visitorId() + "|"
                + request.validFromUtc() + "|" + request.validUntilUtc();
        return runIdempotent(request.requestId(), Operation.CREATE_SUPPRESSION,
                lockedFingerprint(campaignId, fingerprint),
                SuppressionIntervalResponse.class, () -> {
                    long now = clock.millis();
                    Campaign campaign = requireCampaignLocked(campaignId);
                    requireValidRange(request.validFromUtc(), request.validUntilUtc());

                    // 与同一访客现有生效区间（ACTIVE/EARLY_ENDED）重叠则 409
                    for (SuppressionInterval existing
                            : suppressionRepository.lockEffectiveByCampaign(campaignId)) {
                        if (existing.visitorId().equals(request.visitorId())
                                && existing.overlaps(request.validFromUtc(), request.validUntilUtc())) {
                            throw new ApiException(HttpStatus.CONFLICT,
                                    "suppression interval overlaps existing interval "
                                            + existing.intervalId() + " for visitor " + request.visitorId());
                        }
                    }

                    SuppressionInterval interval = new SuppressionInterval(
                            newIntervalId(),
                            campaign.campaignId(),
                            request.visitorId(),
                            request.validFromUtc(),
                            request.validUntilUtc(),
                            null,
                            SuppressionIntervalStatus.ACTIVE,
                            now,
                            null);
                    suppressionRepository.insert(interval);
                    campaignRepository.bumpVersion(campaignId);
                    return SuppressionIntervalResponse.from(interval);
                });
    }

    @Override
    public SuppressionListResponse batchUpdateSuppressionList(String campaignId,
                                                              BatchUpdateSuppressionListRequest request) {
        return runIdempotent(request.requestId(), Operation.BATCH_UPDATE_SUPPRESSION,
                lockedFingerprint(campaignId, batchFingerprint(campaignId, request)),
                SuppressionListResponse.class, () -> {
                    long now = clock.millis();
                    requireCampaignLocked(campaignId);

                    // 乐观锁：版本不一致 409，名单不变（事务回滚）
                    if (!campaignRepository.compareAndBumpVersion(campaignId, request.expectedVersion())) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "campaign version mismatch, expected " + request.expectedVersion());
                    }

                    // 先校验完整最终区间集合：现有生效区间 + 本批全部区间，任一重叠或起止非法整批 422
                    Map<String, List<long[]>> rangesByVisitor = new HashMap<>();
                    for (SuppressionInterval existing
                            : suppressionRepository.lockEffectiveByCampaign(campaignId)) {
                        rangesByVisitor.computeIfAbsent(existing.visitorId(), key -> new ArrayList<>())
                                .add(new long[]{existing.validFromUtc(), existing.validUntilUtc()});
                    }
                    List<SuppressionInterval> toInsert = new ArrayList<>();
                    for (BatchUpdateSuppressionListRequest.Item item : request.items()) {
                        requireValidRange(item.validFromUtc(), item.validUntilUtc());
                        List<long[]> ranges = rangesByVisitor.computeIfAbsent(
                                item.visitorId(), key -> new ArrayList<>());
                        for (long[] range : ranges) {
                            if (overlaps(range[0], range[1], item.validFromUtc(), item.validUntilUtc())) {
                                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                        "batch item overlaps existing interval for visitor "
                                                + item.visitorId());
                            }
                        }
                        ranges.add(new long[]{item.validFromUtc(), item.validUntilUtc()});
                        toInsert.add(new SuppressionInterval(
                                newIntervalId(),
                                campaignId,
                                item.visitorId(),
                                item.validFromUtc(),
                                item.validUntilUtc(),
                                null,
                                SuppressionIntervalStatus.ACTIVE,
                                now,
                                null));
                    }
                    toInsert.forEach(suppressionRepository::insert);

                    long version = campaignRepository.findById(campaignId).orElseThrow().version();
                    return new SuppressionListResponse(campaignId, version,
                            listHistory(campaignId, null));
                });
    }

    @Override
    public SuppressionIntervalResponse deleteSuppressionInterval(String campaignId, String intervalId,
                                                                 IdempotentRequest request) {
        return runIdempotent(request.requestId(), Operation.DELETE_SUPPRESSION,
                lockedFingerprint(campaignId, intervalId),
                SuppressionIntervalResponse.class, () -> {
                    long now = clock.millis();
                    requireCampaignLocked(campaignId);
                    SuppressionInterval interval = requireIntervalLocked(campaignId, intervalId);

                    // EARLY_ENDED/DELETED 为不可变记录
                    if (interval.status() != SuppressionIntervalStatus.ACTIVE) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "suppression interval is " + interval.status() + ", cannot delete");
                    }
                    // 已经开始的区间不可删除，只能提前结束
                    if (now >= interval.validFromUtc()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "suppression interval already started, use end instead of delete");
                    }
                    if (!suppressionRepository.markDeleted(intervalId, now)) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "suppression interval state changed concurrently");
                    }
                    campaignRepository.bumpVersion(campaignId);
                    return SuppressionIntervalResponse.from(
                            suppressionRepository.findById(intervalId).orElseThrow());
                });
    }

    @Override
    public SuppressionIntervalResponse endSuppressionInterval(String campaignId, String intervalId,
                                                              EndSuppressionIntervalRequest request) {
        String fingerprint = intervalId + "|" + request.endAtUtc();
        return runIdempotent(request.requestId(), Operation.END_SUPPRESSION,
                lockedFingerprint(campaignId, fingerprint),
                SuppressionIntervalResponse.class, () -> {
                    long now = clock.millis();
                    requireCampaignLocked(campaignId);
                    SuppressionInterval interval = requireIntervalLocked(campaignId, intervalId);

                    if (interval.status() != SuppressionIntervalStatus.ACTIVE) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "suppression interval is " + interval.status() + ", cannot end");
                    }
                    // 未开始的区间不可提前结束，应删除
                    if (now < interval.validFromUtc()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "suppression interval not started yet, use delete instead of end");
                    }
                    // 结束时刻不得早于当前时刻
                    if (request.endAtUtc() < now) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "endAtUtc must not be earlier than current time");
                    }
                    // 提前结束必须早于当前生效结束时刻
                    if (request.endAtUtc() >= interval.validUntilUtc()) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "endAtUtc must be earlier than current validUntilUtc");
                    }
                    long originalUntil = interval.originalValidUntilUtc() != null
                            ? interval.originalValidUntilUtc()
                            : interval.validUntilUtc();
                    boolean terminal = now >= request.endAtUtc();
                    if (!suppressionRepository.shorten(intervalId, request.endAtUtc(),
                            originalUntil, terminal)) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "suppression interval state changed concurrently");
                    }
                    campaignRepository.bumpVersion(campaignId);
                    return SuppressionIntervalResponse.from(
                            suppressionRepository.findById(intervalId).orElseThrow());
                });
    }

    @Override
    public VisitorSuppressionStatusResponse getVisitorSuppressionStatus(String campaignId,
                                                                        String visitorId) {
        return txTemplate.execute(status -> {
            requireCampaign(campaignId);
            long now = clock.millis();
            Optional<SuppressionInterval> matched = suppressionRepository.findMatched(
                    campaignId, visitorId, now);
            if (matched.isPresent()) {
                return new VisitorSuppressionStatusResponse(
                        campaignId, visitorId, true, SuppressedResponse.REASON,
                        SuppressionIntervalResponse.from(matched.get()), now);
            }
            return new VisitorSuppressionStatusResponse(
                    campaignId, visitorId, false, null, null, now);
        });
    }

    @Override
    public SuppressionListResponse listSuppressionIntervals(String campaignId, String visitorId) {
        return txTemplate.execute(status -> {
            Campaign campaign = requireCampaign(campaignId);
            return new SuppressionListResponse(campaignId, campaign.version(),
                    listHistory(campaignId, visitorId));
        });
    }

    // ---- 内部辅助（作用域末尾） ----

    /** 幂等操作类型，同时标识存储响应的反序列化类型。 */
    private enum Operation {
        CREATE_CAMPAIGN,
        APPLY,
        CONFIRM,
        CANCEL,
        CREATE_SUPPRESSION,
        BATCH_UPDATE_SUPPRESSION,
        DELETE_SUPPRESSION,
        END_SUPPRESSION
    }

    /**
     * 曝光申请幂等指纹：活动版本、访客、展示位、请求时刻与全部频控影响字段。
     * 在事务内持公告行锁读取版本，保证指纹与裁决基于同一名单快照。
     */
    private String applyFingerprint(ApplyExposureRequest request) {
        Campaign campaign = campaignRepository.lockById(request.campaignId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "campaign not found: " + request.campaignId()));
        return request.campaignId() + "|" + campaign.version() + "|" + request.visitorId() + "|"
                + request.placementId() + "|" + request.requestAtUtc() + "|"
                + campaign.dailyTotalCap() + "|" + campaign.perVisitorDailyCap();
    }

    /**
     * 批量更新幂等指纹：公告、期望版本与规范化（排序后）的完整区间集合。
     */
    private String batchFingerprint(String campaignId, BatchUpdateSuppressionListRequest request) {
        List<String> items = request.items().stream()
                .map(item -> item.visitorId() + ":" + item.validFromUtc() + ":" + item.validUntilUtc())
                .sorted()
                .toList();
        return campaignId + "|" + request.expectedVersion() + "|" + String.join(",", items);
    }

    /**
     * 构造在事务内先持公告行锁再返回指纹的供应器：将幂等检查与名单变更序列化到
     * 公告行锁上，保证并发同键事务在胜出者提交后才检查幂等记录，从而重放而非误判冲突。
     */
    private Supplier<String> lockedFingerprint(String campaignId, String fingerprint) {
        return () -> {
            requireCampaignLocked(campaignId);
            return fingerprint;
        };
    }

    /**
     * 在事务内执行业务并维护幂等记录；业务变更与去重结果原子提交。
     * 并发同键插入冲突时等待胜出事务提交后重放其结果。
     *
     * @param fingerprintSupplier 在事务内求值的请求参数指纹（不含 requestId）
     */
    private <T> T runIdempotent(String requestId, Operation operation, Supplier<String> fingerprintSupplier,
                                Class<T> responseType, Supplier<T> action) {
        long deadline = System.currentTimeMillis() + IDEMPOTENT_WAIT_MILLIS;
        while (true) {
            try {
                return txTemplate.execute(status -> {
                    String fingerprint = fingerprintSupplier.get();
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
     *
     * @param isConfirm true=确认，false=取消
     */
    private ReservationResponse transition(String reservationId, boolean isConfirm) {
        long now = clock.millis();
        Reservation current = reservationRepository.lockById(reservationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "reservation not found: " + reservationId));

        // 仅结算本单（本事务已持其行锁，加锁顺序保持为 预占单 -> 总账 -> 访客账，避免死锁）
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
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "campaign not found: " + campaignId));
    }

    /** 行锁读取公告；不存在返回 404。 */
    private Campaign requireCampaignLocked(String campaignId) {
        return campaignRepository.lockById(campaignId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "campaign not found: " + campaignId));
    }

    /** 行锁读取区间并校验归属公告；不存在或不属于该公告返回 404。 */
    private SuppressionInterval requireIntervalLocked(String campaignId, String intervalId) {
        SuppressionInterval interval = suppressionRepository.lockById(intervalId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "suppression interval not found: " + intervalId));
        if (!interval.campaignId().equals(campaignId)) {
            throw new ApiException(HttpStatus.NOT_FOUND,
                    "suppression interval not found: " + intervalId);
        }
        return interval;
    }

    /** 区间起止合法性：左闭右开要求起始严格小于结束，否则 422。 */
    private void requireValidRange(long validFromUtc, long validUntilUtc) {
        if (validFromUtc >= validUntilUtc) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "validFromUtc must be earlier than validUntilUtc");
        }
    }

    /** 左闭右开区间重叠判定。 */
    private static boolean overlaps(long aFrom, long aUntil, long bFrom, long bUntil) {
        return aFrom < bUntil && bFrom < aUntil;
    }

    private List<SuppressionIntervalResponse> listHistory(String campaignId, String visitorId) {
        return suppressionRepository.findHistory(campaignId, visitorId).stream()
                .map(SuppressionIntervalResponse::from)
                .toList();
    }

    private String newIntervalId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }
}
