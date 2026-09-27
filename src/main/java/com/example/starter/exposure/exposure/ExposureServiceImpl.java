package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.AttributionReceipt;
import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.CampaignWindowVersion;
import com.example.starter.exposure.domain.ReceiptResult;
import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.repo.AttributionReceiptRepository;
import com.example.starter.exposure.repo.CampaignRepository;
import com.example.starter.exposure.repo.CampaignWindowVersionRepository;
import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.exposure.repo.LedgerRepository;
import com.example.starter.exposure.repo.ReservationRepository;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignDiagnosticsResponse;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReceiptBatchRequest;
import com.example.starter.exposure.web.ReceiptBatchResponse;
import com.example.starter.exposure.web.ReceiptItemResult;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationDetailResponse;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpdateWindowRequest;
import com.example.starter.exposure.web.WindowVersionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 公告曝光频控与归因窗口业务服务实现。
 *
 * <p>所有写操作以 requestId 为全局幂等键：同键同参重放原成功结果的首次完整快照，
 * 异参 409；业务失败随事务回滚，不占幂等键。确认、取消、过期与回执在同一事务内
 * 按提交顺序裁决，行锁 + 状态 CAS 保证同一预占最终只结算一次。</p>
 *
 * <p>归因窗口在预占创建时快照（分钟数 + 版本号），公告后续调整窗口只追加不可变版本，
 * 不重算历史归因。回执区间左闭右开 [预占时刻, 预占时刻+窗口)；窗口内 RESERVED
 * 预占计入 ATTRIBUTED 并结算（额度持续占用），窗口外仅记录 LATE_RECEIPT，
 * 不二次确认、不释放配额。</p>
 */
@Service
public class ExposureServiceImpl implements ExposureService {

    static final int RESERVATION_TTL_MILLIS = 60_000;

    /** 归因窗口每毫秒换算。 */
    private static final long MILLIS_PER_MINUTE = 60_000L;

    /** 诊断查询近期回执上限保护。 */
    private static final int DIAGNOSTICS_MAX_LIMIT = 1000;

    /** 并发同键竞争时等待胜出事务提交的最大时长。 */
    private static final long IDEMPOTENT_WAIT_MILLIS = 10_000L;

    private final Clock clock;
    private final CampaignRepository campaignRepository;
    private final CampaignWindowVersionRepository windowVersionRepository;
    private final ReservationRepository reservationRepository;
    private final LedgerRepository ledgerRepository;
    private final AttributionReceiptRepository receiptRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;
    private final JdbcTemplate jdbcTemplate;

    public ExposureServiceImpl(Clock clock,
                               CampaignRepository campaignRepository,
                               CampaignWindowVersionRepository windowVersionRepository,
                               ReservationRepository reservationRepository,
                               LedgerRepository ledgerRepository,
                               AttributionReceiptRepository receiptRepository,
                               IdempotencyRepository idempotencyRepository,
                               ObjectMapper objectMapper,
                               TransactionTemplate txTemplate,
                               JdbcTemplate jdbcTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.windowVersionRepository = windowVersionRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.receiptRepository = receiptRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public CampaignResponse createCampaign(CreateCampaignRequest request) {
        String fingerprint = FingerprintNormalizer.createCampaign(objectMapper,
                request.campaignId(), request.dailyTotalCap(), request.perVisitorDailyCap(),
                request.attributionWindowMinutes(), request.operatorId());
        return runIdempotent(request.requestId(), Operation.CREATE_CAMPAIGN, fingerprint,
                CampaignResponse.class, () -> {
                    if (campaignRepository.findById(request.campaignId()).isPresent()) {
                        throw new ApiException(HttpStatus.CONFLICT, "CAMPAIGN_ALREADY_EXISTS",
                                "campaign already exists: " + request.campaignId());
                    }
                    long now = clock.millis();
                    Campaign campaign = new Campaign(
                            request.campaignId(),
                            request.dailyTotalCap(),
                            request.perVisitorDailyCap(),
                            request.attributionWindowMinutes(),
                            1,
                            now);
                    try {
                        campaignRepository.insert(campaign);
                    } catch (DuplicateKeyException duplicateCampaign) {
                        // 并发创建同一 campaignId：明确返回 409，而非误报幂等键冲突
                        throw new ApiException(HttpStatus.CONFLICT, "CAMPAIGN_ALREADY_EXISTS",
                                "campaign already exists: " + request.campaignId());
                    }
                    // V1 与公告同时生效；创建操作者不强制，允许为 null
                    windowVersionRepository.insert(new CampaignWindowVersion(
                            campaign.campaignId(), 1,
                            campaign.attributionWindowMinutes(), request.operatorId(), now));
                    return CampaignResponse.from(campaign);
                });
    }

    @Override
    public CampaignResponse updateWindow(String campaignId, UpdateWindowRequest request) {
        String fingerprint = FingerprintNormalizer.updateWindow(objectMapper,
                campaignId, request.operatorId(), request.attributionWindowMinutes());
        return runIdempotent(request.requestId(), Operation.UPDATE_WINDOW, fingerprint,
                CampaignResponse.class, () -> {
                    long now = clock.millis();
                    Campaign current = campaignRepository.lockById(campaignId)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "CAMPAIGN_NOT_FOUND", "campaign not found: " + campaignId));
                    if (current.attributionWindowMinutes() == request.attributionWindowMinutes()) {
                        throw new ApiException(HttpStatus.CONFLICT, "WINDOW_UNCHANGED",
                                "attribution window minutes unchanged at "
                                        + request.attributionWindowMinutes() + " for campaign " + campaignId);
                    }
                    int newVersion = current.windowVersion() + 1;
                    Campaign updated = new Campaign(
                            current.campaignId(),
                            current.dailyTotalCap(),
                            current.perVisitorDailyCap(),
                            request.attributionWindowMinutes(),
                            newVersion,
                            current.createdAtUtc());
                    campaignRepository.updateWindow(campaignId,
                            request.attributionWindowMinutes(), newVersion);
                    windowVersionRepository.insert(new CampaignWindowVersion(
                            campaignId, newVersion, request.attributionWindowMinutes(),
                            request.operatorId(), now));
                    return CampaignResponse.from(updated);
                });
    }

    @Override
    public ReservationResponse apply(ApplyExposureRequest request) {
        String fingerprint = FingerprintNormalizer.apply(objectMapper,
                request.campaignId(), request.visitorId());
        return runIdempotent(request.requestId(), Operation.APPLY, fingerprint,
                ReservationResponse.class, () -> {
                    long now = clock.millis();
                    LocalDate utcDate = LocalDate.now(clock);
                    Campaign campaign = requireCampaign(request.campaignId());

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
                        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "QUOTA_EXHAUSTED",
                                "exposure quota exhausted for campaign " + campaign.campaignId());
                    }
                    ledgerRepository.addTotal(campaign.campaignId(), utcDate, 1);
                    ledgerRepository.addVisitor(campaign.campaignId(), request.visitorId(), utcDate, 1);

                    String reservationId = UUID.randomUUID().toString().replace("-", "");
                    // 快照当前归因窗口分钟数与版本；后续公告改窗口不影响本单
                    Reservation reservation = new Reservation(
                            reservationId,
                            campaign.campaignId(),
                            request.visitorId(),
                            java.sql.Date.valueOf(utcDate),
                            ReservationStatus.RESERVED,
                            now,
                            now + RESERVATION_TTL_MILLIS,
                            null,
                            campaign.attributionWindowMinutes(),
                            campaign.windowVersion(),
                            null,
                            null);
                    reservationRepository.insert(reservation);
                    return ReservationResponse.from(reservation);
                });
    }

    @Override
    public ReservationResponse confirm(String reservationId, ReservationActionRequest request) {
        String fingerprint = FingerprintNormalizer.reservationAction(
                objectMapper, Operation.CONFIRM.name(), reservationId);
        return runIdempotent(request.requestId(), Operation.CONFIRM, fingerprint,
                ReservationResponse.class, () -> transition(reservationId, true));
    }

    @Override
    public ReservationResponse cancel(String reservationId, ReservationActionRequest request) {
        String fingerprint = FingerprintNormalizer.reservationAction(
                objectMapper, Operation.CANCEL.name(), reservationId);
        return runIdempotent(request.requestId(), Operation.CANCEL, fingerprint,
                ReservationResponse.class, () -> transition(reservationId, false));
    }

    @Override
    public ReservationResponse getReservation(String reservationId) {
        return txTemplate.execute(status -> {
            Reservation locked = reservationRepository.lockById(reservationId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "RESERVATION_NOT_FOUND", "reservation not found: " + reservationId));
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
    public ReceiptBatchResponse submitReceipts(ReceiptBatchRequest request) {
        String fingerprint = FingerprintNormalizer.receiptBatch(objectMapper, request);
        return runIdempotent(request.requestId(), Operation.SUBMIT_RECEIPTS, fingerprint,
                ReceiptBatchResponse.class, () -> adjudicateReceipts(request));
    }

    @Override
    public ReservationDetailResponse getReservationDetail(String reservationId) {
        // 严格只读：无行锁、不结算过期、不写任何状态
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "RESERVATION_NOT_FOUND", "reservation not found: " + reservationId));
        long windowStart = reservation.createdAtUtc();
        long windowEnd = windowStart
                + (long) reservation.attributionWindowMinutes() * MILLIS_PER_MINUTE;
        List<ReceiptItemResult> receipts = receiptRepository.findByReservationOrdered(reservationId)
                .stream().map(this::toItemResult).toList();
        return new ReservationDetailResponse(
                ReservationResponse.from(reservation),
                reservation.attributionWindowMinutes(),
                windowStart,
                windowEnd,
                receipts);
    }

    @Override
    public List<ReceiptItemResult> listReceipts(String reservationId) {
        // 严格只读：预占不存在返回 404，存在则返回其全部不可变回执
        if (reservationRepository.findById(reservationId).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND,
                    "RESERVATION_NOT_FOUND", "reservation not found: " + reservationId);
        }
        return receiptRepository.findByReservationOrdered(reservationId)
                .stream().map(this::toItemResult).toList();
    }

    @Override
    public List<WindowVersionResponse> listWindowVersions(String campaignId) {
        requireCampaign(campaignId);
        return windowVersionRepository.findByCampaignOrdered(campaignId)
                .stream().map(WindowVersionResponse::from).toList();
    }

    @Override
    public CampaignDiagnosticsResponse getCampaignDiagnostics(String campaignId, int recentLimit) {
        if (recentLimit < 1 || recentLimit > DIAGNOSTICS_MAX_LIMIT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RECENT_LIMIT",
                    "recentLimit must be within 1 and " + DIAGNOSTICS_MAX_LIMIT);
        }
        // 严格只读：不触发过期结算，RESERVED 按当前实际状态统计
        Campaign campaign = requireCampaign(campaignId);
        long now = clock.millis();

        Map<String, Integer> reservationCounts = new LinkedHashMap<>();
        for (ReservationStatus status : ReservationStatus.values()) {
            reservationCounts.put(status.name(), 0);
        }
        jdbcCountByStatus(campaignId, reservationCounts);
        int totalReservations = reservationCounts.values().stream().mapToInt(Integer::intValue).sum();

        Map<String, Integer> receiptCounts = new LinkedHashMap<>();
        for (ReceiptResult result : ReceiptResult.values()) {
            receiptCounts.put(result.name(), 0);
        }
        jdbcCountByReceiptResult(campaignId, receiptCounts);
        int totalReceipts = receiptCounts.values().stream().mapToInt(Integer::intValue).sum();
        double attributedRatio = totalReceipts == 0 ? 0.0
                : (double) receiptCounts.get(ReceiptResult.ATTRIBUTED.name()) / totalReceipts;

        List<ReceiptItemResult> recent = receiptRepository.findByCampaignOrdered(campaignId, recentLimit)
                .stream().map(this::toItemResult).toList();
        return new CampaignDiagnosticsResponse(
                campaignId,
                campaign.windowVersion(),
                campaign.attributionWindowMinutes(),
                now,
                reservationCounts,
                totalReservations,
                receiptCounts,
                totalReceipts,
                attributedRatio,
                recent);
    }

    // ---- 内部辅助（作用域末尾） ----

    /** 幂等操作类型，同时标识存储响应的反序列化类型。 */
    private enum Operation {
        CREATE_CAMPAIGN,
        UPDATE_WINDOW,
        APPLY,
        CONFIRM,
        CANCEL,
        SUBMIT_RECEIPTS
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
                                    "IDEMPOTENCY_KEY_MISMATCH",
                                    "idempotency key reused with different parameters: " + requestId);
                        }
                        try {
                            // 按首次完整快照重放，读取不改状态
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
                            "IDEMPOTENCY_KEY_CONFLICT",
                            "concurrent idempotency key conflict: " + requestId);
                }
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, null, "interrupted");
                }
            }
        }
    }

    /**
     * 批量回执整批裁决：先在同一事务内按预占编号升序加锁并核对整批（存在性、归属、
     * 回执时刻、批内重复），全部通过后逐条裁决并写入不可变流水，最后一次提交。
     * 任一失败抛出 422，整批回滚不留半成品，也不占用幂等键。
     */
    private ReceiptBatchResponse adjudicateReceipts(ReceiptBatchRequest request) {
        long receivedAt = clock.millis();

        // 规范化对象集合：按预占编号升序，排序稳定、与提交顺序无关
        List<ReceiptBatchRequest.ReceiptItem> sorted = new ArrayList<>(request.receipts());
        sorted.sort((a, b) -> a.reservationId().compareTo(b.reservationId()));

        // 批内同一预占重复：整批 422
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i - 1).reservationId().equals(sorted.get(i).reservationId())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DUPLICATE_RESERVATION_IN_BATCH",
                        "reservation appears more than once in batch: "
                                + sorted.get(i).reservationId());
            }
        }

        // 阶段一：按固定顺序加锁并核对整批最终状态（同时结算已到期预占）
        List<LockedItem> lockedItems = new ArrayList<>(sorted.size());
        for (ReceiptBatchRequest.ReceiptItem item : sorted) {
            Reservation reservation = reservationRepository.lockById(item.reservationId())
                    .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "RESERVATION_NOT_FOUND",
                            "reservation not found: " + item.reservationId()));
            if (!reservation.campaignId().equals(item.campaignId())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CAMPAIGN_MISMATCH",
                        "reservation " + item.reservationId() + " belongs to campaign "
                                + reservation.campaignId() + " but receipt declared "
                                + item.campaignId());
            }
            if (!reservation.visitorId().equals(item.visitorId())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VISITOR_MISMATCH",
                        "reservation " + item.reservationId() + " belongs to visitor "
                                + reservation.visitorId() + " but receipt declared "
                                + item.visitorId());
            }
            if (item.receiptAtUtc() < reservation.createdAtUtc()) {
                // 回执业务时刻早于预占时刻：结构非法，整批 422，不产生流水
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RECEIPT_BEFORE_RESERVATION",
                        "receiptAtUtc " + item.receiptAtUtc() + " is before reservation createdAtUtc "
                                + reservation.createdAtUtc() + " for reservation "
                                + item.reservationId()
                                + " (delta " + (item.receiptAtUtc() - reservation.createdAtUtc())
                                + "ms)");
            }
            // 过期按提交顺序先行结算（本事务持行锁）；失败整体回滚
            expireIfDue(reservation, receivedAt);
            Reservation refreshed = reservationRepository.lockById(item.reservationId()).orElseThrow();
            // 已取消预占的回执语义冲突：整批 422，不结算也不产生流水
            if (refreshed.status() == ReservationStatus.CANCELLED) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RESERVATION_CANCELLED",
                        "reservation already cancelled, receipt not accepted: "
                                + item.reservationId());
            }
            lockedItems.add(new LockedItem(item, refreshed));
        }

        // 阶段二：逐条裁决并落不可变流水；行锁 + CAS 保证同一预占只归因一次
        List<ReceiptItemResult> results = new ArrayList<>(lockedItems.size());
        int attributed = 0;
        int late = 0;
        int alreadyAttributed = 0;
        int alreadyConfirmed = 0;

        for (LockedItem locked : lockedItems) {
            ReceiptBatchRequest.ReceiptItem item = locked.item();
            Reservation reservation = locked.reservation();
            long windowStart = reservation.createdAtUtc();
            long windowEnd = windowStart
                    + (long) reservation.attributionWindowMinutes() * MILLIS_PER_MINUTE;
            long receiptAt = item.receiptAtUtc();
            boolean withinWindow = receiptAt >= windowStart && receiptAt < windowEnd;

            ReceiptResult result;
            String lateReason = null;
            Long firstAttributedAtUtc = null;

            switch (reservation.status()) {
                case RESERVED -> {
                    if (withinWindow) {
                        // 窗口内：计入归因并结算，额度持续占用（无账目变动），仅 CAS 成功者归因。
                        // 本事务已持该行锁且批内无重复预占，CAS 失败只可能是内部状态损坏。
                        boolean won = reservationRepository.compareAndSetReservedToAttributed(
                                reservation.reservationId(), receiptAt, receivedAt,
                                request.requestId());
                        if (!won) {
                            throw new IllegalStateException(
                                    "reserved reservation changed within locked transaction: "
                                            + reservation.reservationId());
                        }
                        result = ReceiptResult.ATTRIBUTED;
                        attributed++;
                    } else {
                        // 窗口外：仅记录，不二次确认、不释放配额，预占保持 RESERVED 直至到期
                        result = ReceiptResult.LATE_RECEIPT;
                        lateReason = "AT_OR_AFTER_WINDOW_END";
                        late++;
                    }
                }
                case ATTRIBUTED -> {
                    result = ReceiptResult.ALREADY_ATTRIBUTED;
                    firstAttributedAtUtc = reservation.attributedAtUtc();
                    alreadyAttributed++;
                }
                case CONFIRMED -> {
                    result = ReceiptResult.ALREADY_CONFIRMED;
                    alreadyConfirmed++;
                }
                case EXPIRED -> {
                    result = ReceiptResult.LATE_RECEIPT;
                    lateReason = "RESERVATION_EXPIRED";
                    late++;
                }
                default -> throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "UNEXPECTED_STATUS", "unexpected reservation status: "
                        + reservation.status());
            }
            // CANCELLED 已在阶段一整批核对时拒绝（422），不会进入裁决

            AttributionReceipt receipt = new AttributionReceipt(
                    UUID.randomUUID().toString().replace("-", ""),
                    request.requestId(),
                    request.operatorId(),
                    reservation.campaignId(),
                    reservation.reservationId(),
                    reservation.visitorId(),
                    reservation.windowVersion(),
                    reservation.attributionWindowMinutes(),
                    windowStart,
                    windowEnd,
                    receiptAt,
                    receivedAt,
                    result,
                    firstAttributedAtUtc,
                    lateReason);
            receiptRepository.insert(receipt);
            results.add(toItemResult(receipt));
        }

        int total = sorted.size();
        return new ReceiptBatchResponse(
                request.requestId(),
                request.operatorId(),
                receivedAt,
                total,
                attributed,
                late,
                alreadyAttributed,
                alreadyConfirmed,
                total == 0 ? 0.0 : (double) attributed / total,
                results);
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
                        "RESERVATION_NOT_FOUND", "reservation not found: " + reservationId));

        // 仅结算本单（本事务已持其行锁，加锁顺序保持为 预占单 -> 总账 -> 访客账，避免死锁）
        expireIfDue(current, now);
        current = reservationRepository.lockById(reservationId).orElseThrow();

        if (current.status() == ReservationStatus.RESERVED) {
            // 结算后仍为 RESERVED 说明 now < expiresAt，确认严格要求在到期时刻之前
            ReservationStatus target = isConfirm ? ReservationStatus.CONFIRMED : ReservationStatus.CANCELLED;
            if (!reservationRepository.compareAndSetStatus(
                    reservationId, ReservationStatus.RESERVED, target, now)) {
                throw new ApiException(HttpStatus.CONFLICT, "RESERVATION_STATE_CHANGED",
                        "reservation state changed concurrently");
            }
            if (!isConfirm) {
                // 取消释放两级额度；固定顺序先总账后访客账
                ledgerRepository.releaseTotal(current.campaignId(), current.utcDate().toLocalDate());
                ledgerRepository.releaseVisitor(
                        current.campaignId(), current.visitorId(), current.utcDate().toLocalDate());
            }
        } else if (isTerminal(current.status())) {
            boolean sameTerminal = isConfirm
                    ? current.status() == ReservationStatus.CONFIRMED
                    : current.status() == ReservationStatus.CANCELLED;
            if (!sameTerminal) {
                throw new ApiException(HttpStatus.CONFLICT, "RESERVATION_TERMINAL_STATE",
                        "reservation is " + current.status() + ", cannot "
                                + (isConfirm ? "confirm" : "cancel"));
            }
            // 重复同类终态操作：返回原状态，不重复释放额度
        } else {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "UNEXPECTED_STATUS",
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

    private boolean isTerminal(ReservationStatus status) {
        return status == ReservationStatus.CONFIRMED
                || status == ReservationStatus.ATTRIBUTED
                || status == ReservationStatus.CANCELLED
                || status == ReservationStatus.EXPIRED;
    }

    private Campaign requireCampaign(String campaignId) {
        return campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "CAMPAIGN_NOT_FOUND", "campaign not found: " + campaignId));
    }

    private void jdbcCountByStatus(String campaignId, Map<String, Integer> target) {
        // RowCallbackHandler 逐行回调（回调内 ResultSet 已定位到当前行，不能再 next）
        jdbcTemplate.query("SELECT status, COUNT(*) FROM exposure_reservation "
                        + "WHERE campaign_id = ? GROUP BY status",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        target.put(rs.getString(1), rs.getInt(2)),
                campaignId);
    }

    private void jdbcCountByReceiptResult(String campaignId, Map<String, Integer> target) {
        jdbcTemplate.query("SELECT result, COUNT(*) FROM attribution_receipt "
                        + "WHERE campaign_id = ? GROUP BY result",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        target.put(rs.getString(1), rs.getInt(2)),
                campaignId);
    }

    private ReceiptItemResult toItemResult(AttributionReceipt receipt) {
        boolean withinWindow = receipt.receiptAtUtc() >= receipt.windowStartUtc()
                && receipt.receiptAtUtc() < receipt.windowEndUtc();
        return new ReceiptItemResult(
                receipt.reservationId(),
                receipt.campaignId(),
                receipt.visitorId(),
                receipt.result().name(),
                receipt.windowVersion(),
                receipt.attributionWindowMinutes(),
                receipt.windowStartUtc(),
                receipt.windowEndUtc(),
                receipt.receiptAtUtc(),
                withinWindow,
                receipt.receiptAtUtc() - receipt.windowStartUtc(),
                receipt.windowEndUtc() - receipt.receiptAtUtc(),
                receipt.attributedAtUtc(),
                receipt.lateReason());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }

    /** 阶段一加锁核对后的条目与预占快照。 */
    private record LockedItem(ReceiptBatchRequest.ReceiptItem item, Reservation reservation) {
    }
}
