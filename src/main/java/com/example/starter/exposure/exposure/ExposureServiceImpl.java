package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.CampaignWindow;
import com.example.starter.exposure.domain.Receipt;
import com.example.starter.exposure.domain.ReceiptResult;
import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.repo.CampaignRepository;
import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.exposure.repo.LedgerRepository;
import com.example.starter.exposure.repo.ReceiptRepository;
import com.example.starter.exposure.repo.ReservationRepository;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.AttributionDiagnosticsResponse;
import com.example.starter.exposure.web.BatchReceiptRequest;
import com.example.starter.exposure.web.BatchReceiptResponse;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReceiptItem;
import com.example.starter.exposure.web.ReceiptRequest;
import com.example.starter.exposure.web.ReceiptResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpdateWindowRequest;
import com.example.starter.exposure.web.WindowVersionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 公告曝光频控业务服务实现。
 *
 * <p>所有写操作以 requestId 为全局幂等键：同键同参重放原成功结果，异参 409；
 * 业务失败随事务回滚，不占幂等键。幂等指纹为规范化 JSON 的 SHA-256，
 * 覆盖操作者、操作类型、规范化对象集合及全部结果相关字段，不做分隔符拼接。
 * 所有操作与额度查询先结算相关过期预占，不依赖后台定时器。
 * 终态竞争由行锁 + 状态 CAS 保证只允许一个终态。</p>
 *
 * <p>归因窗口：回执上报时刻落在 [预占时刻, 预占时刻 + 窗口)（UTC，左闭右开）内
 * 计入归因并结算额度；窗口外记录 LATE_RECEIPT，不确认也不释放配额。
 * 预占单快照申请时刻的窗口版本与分钟数，回执快照裁决结果，均不可变，
 * 后续调整窗口不重算历史归因。</p>
 */
@Service
public class ExposureServiceImpl implements ExposureService {

    static final int RESERVATION_TTL_MILLIS = 60_000;

    /** 创建公告未显式指定归因窗口时使用的默认窗口（分钟）。 */
    static final int DEFAULT_ATTRIBUTION_WINDOW_MINUTES = 1440;

    /** 并发同键竞争时等待胜出事务提交的最大时长。 */
    private static final long IDEMPOTENT_WAIT_MILLIS = 10_000L;

    private final Clock clock;
    private final CampaignRepository campaignRepository;
    private final ReservationRepository reservationRepository;
    private final LedgerRepository ledgerRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ReceiptRepository receiptRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public ExposureServiceImpl(Clock clock,
                               CampaignRepository campaignRepository,
                               ReservationRepository reservationRepository,
                               LedgerRepository ledgerRepository,
                               IdempotencyRepository idempotencyRepository,
                               ReceiptRepository receiptRepository,
                               ObjectMapper objectMapper,
                               TransactionTemplate txTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.receiptRepository = receiptRepository;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
    }

    @Override
    public CampaignResponse createCampaign(CreateCampaignRequest request) {
        int windowMinutes = request.attributionWindowMinutes() != null
                ? request.attributionWindowMinutes()
                : DEFAULT_ATTRIBUTION_WINDOW_MINUTES;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("campaignId", request.campaignId());
        fields.put("dailyTotalCap", request.dailyTotalCap());
        fields.put("perVisitorDailyCap", request.perVisitorDailyCap());
        fields.put("attributionWindowMinutes", windowMinutes);
        return runIdempotent(request.requestId(), Operation.CREATE_CAMPAIGN, fingerprint(fields),
                CampaignResponse.class, () -> {
                    if (campaignRepository.findById(request.campaignId()).isPresent()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "campaign already exists: " + request.campaignId());
                    }
                    Campaign campaign = new Campaign(
                            request.campaignId(),
                            request.dailyTotalCap(),
                            request.perVisitorDailyCap(),
                            windowMinutes,
                            1,
                            clock.millis());
                    try {
                        campaignRepository.insert(campaign);
                        campaignRepository.insertWindowHistory(new CampaignWindow(
                                campaign.campaignId(), 1, windowMinutes, campaign.createdAtUtc()));
                    } catch (DuplicateKeyException duplicateCampaign) {
                        // 并发创建同一 campaignId：明确返回 409，而非误报幂等键冲突
                        throw new ApiException(HttpStatus.CONFLICT,
                                "campaign already exists: " + request.campaignId());
                    }
                    return CampaignResponse.from(campaign);
                });
    }

    @Override
    public ReservationResponse apply(ApplyExposureRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("campaignId", request.campaignId());
        fields.put("visitorId", request.visitorId());
        return runIdempotent(request.requestId(), Operation.APPLY, fingerprint(fields),
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
                            null,
                            campaign.windowVersion(),
                            campaign.attributionWindowMinutes());
                    reservationRepository.insert(reservation);
                    return ReservationResponse.from(reservation);
                });
    }

    @Override
    public ReservationResponse confirm(String reservationId, ReservationActionRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("reservationId", reservationId);
        return runIdempotent(request.requestId(), Operation.CONFIRM, fingerprint(fields),
                ReservationResponse.class, () -> transition(reservationId, true));
    }

    @Override
    public ReservationResponse cancel(String reservationId, ReservationActionRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("reservationId", reservationId);
        return runIdempotent(request.requestId(), Operation.CANCEL, fingerprint(fields),
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
    public CampaignResponse updateWindow(String campaignId, UpdateWindowRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("operator", request.operator());
        fields.put("campaignId", campaignId);
        fields.put("attributionWindowMinutes", request.attributionWindowMinutes());
        return runIdempotent(request.requestId(), Operation.UPDATE_WINDOW, fingerprint(fields),
                CampaignResponse.class, () -> {
                    Campaign current = campaignRepository.lockById(campaignId)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "campaign not found: " + campaignId));
                    int newVersion = current.windowVersion() + 1;
                    campaignRepository.updateWindow(
                            campaignId, request.attributionWindowMinutes(), newVersion);
                    // 历史版本只增不改：历史归因按各自快照裁决，不回算
                    campaignRepository.insertWindowHistory(new CampaignWindow(
                            campaignId, newVersion, request.attributionWindowMinutes(),
                            clock.millis()));
                    return CampaignResponse.from(campaignRepository.findById(campaignId).orElseThrow());
                });
    }

    @Override
    public ReceiptResponse submitReceipt(String reservationId, ReceiptRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("operator", request.operator());
        fields.put("reservationId", reservationId);
        fields.put("receiptAtUtc", request.receiptAtUtc());
        return runIdempotent(request.requestId(), Operation.RECEIPT, fingerprint(fields),
                ReceiptResponse.class, () -> ReceiptResponse.from(
                        processReceipt(reservationId, request.operator(), request.receiptAtUtc(), null)));
    }

    @Override
    public BatchReceiptResponse submitBatchReceipts(BatchReceiptRequest request) {
        // 规范化对象集合：回执项按 (reservationId, receiptAtUtc) 排序后参与指纹，
        // 同一集合不同提交顺序视为同一请求
        List<Map<String, Object>> normalizedItems = request.items().stream()
                .sorted(Comparator.comparing(ReceiptItem::reservationId)
                        .thenComparing(ReceiptItem::receiptAtUtc))
                .map(item -> {
                    Map<String, Object> entry = new LinkedHashMap<String, Object>();
                    entry.put("reservationId", item.reservationId());
                    entry.put("receiptAtUtc", item.receiptAtUtc());
                    return entry;
                })
                .toList();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("operator", request.operator());
        fields.put("campaignId", request.campaignId());
        fields.put("items", normalizedItems);
        return runIdempotent(request.requestId(), Operation.BATCH_RECEIPT, fingerprint(fields),
                BatchReceiptResponse.class, () -> {
                    requireCampaign(request.campaignId());
                    long now = clock.millis();

                    // 固定加锁顺序：按预占单编号升序逐个行锁，避免批量间死锁
                    List<String> distinctIds = request.items().stream()
                            .map(ReceiptItem::reservationId)
                            .distinct()
                            .sorted()
                            .toList();
                    Map<String, Reservation> states = new LinkedHashMap<>();
                    for (String reservationId : distinctIds) {
                        Reservation locked = reservationRepository.lockById(reservationId)
                                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                        "batch rejected: reservation not found: " + reservationId));
                        // 回执按提交顺序裁决：受理时刻已到期的预占先结算为 EXPIRED
                        expireIfDue(locked, now);
                        states.put(reservationId,
                                reservationRepository.lockById(reservationId).orElseThrow());
                    }

                    // 先核对整批最终状态：任一不满足则整批不结算、不留半成品
                    for (ReceiptItem item : request.items()) {
                        Reservation current = states.get(item.reservationId());
                        validateReceiptable(current, item.receiptAtUtc());
                        if (!current.campaignId().equals(request.campaignId())) {
                            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                    "batch rejected: reservation " + item.reservationId()
                                            + " does not belong to campaign " + request.campaignId());
                        }
                    }

                    // 整批核对通过后按提交顺序应用，同事务一次提交
                    List<ReceiptResponse> results = new ArrayList<>();
                    int attributed = 0;
                    int late = 0;
                    for (ReceiptItem item : request.items()) {
                        Reservation current = states.get(item.reservationId());
                        Receipt receipt = applyReceipt(current, item.receiptAtUtc(),
                                request.operator(), request.requestId(), now);
                        if (receipt.result() == ReceiptResult.ATTRIBUTED
                                && current.status() == ReservationStatus.RESERVED) {
                            states.put(item.reservationId(), new Reservation(
                                    current.reservationId(), current.campaignId(),
                                    current.visitorId(), current.utcDate(),
                                    ReservationStatus.CONFIRMED, current.createdAtUtc(),
                                    current.expiresAtUtc(), now,
                                    current.windowVersion(), current.windowMinutes()));
                        }
                        if (receipt.result() == ReceiptResult.ATTRIBUTED) {
                            attributed++;
                        } else {
                            late++;
                        }
                        results.add(ReceiptResponse.from(receipt));
                    }
                    return new BatchReceiptResponse(request.requestId(), request.campaignId(),
                            results.size(), attributed, late, results);
                });
    }

    @Override
    public ReceiptResponse getReceipt(String receiptId) {
        return txTemplate.execute(status -> ReceiptResponse.from(
                receiptRepository.findByReceiptId(receiptId)
                        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                "receipt not found: " + receiptId))));
    }

    @Override
    public List<ReceiptResponse> listReceipts(String reservationId) {
        return txTemplate.execute(status -> {
            // 只读查询：不结算过期、不改任何状态
            if (reservationRepository.findById(reservationId).isEmpty()) {
                throw new ApiException(HttpStatus.NOT_FOUND,
                        "reservation not found: " + reservationId);
            }
            return receiptRepository.listByReservationId(reservationId).stream()
                    .map(ReceiptResponse::from)
                    .toList();
        });
    }

    @Override
    public List<WindowVersionResponse> listWindowHistory(String campaignId) {
        return txTemplate.execute(status -> {
            if (campaignRepository.findById(campaignId).isEmpty()) {
                throw new ApiException(HttpStatus.NOT_FOUND, "campaign not found: " + campaignId);
            }
            return campaignRepository.listWindowHistory(campaignId).stream()
                    .map(WindowVersionResponse::from)
                    .toList();
        });
    }

    @Override
    public AttributionDiagnosticsResponse attributionDiagnostics(String campaignId, LocalDate utcDate) {
        return txTemplate.execute(status -> {
            Campaign campaign = campaignRepository.findById(campaignId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "campaign not found: " + campaignId));
            Long fromUtc = null;
            Long toUtc = null;
            if (utcDate != null) {
                fromUtc = utcDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
                toUtc = utcDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            }
            Map<ReceiptResult, Long> counts =
                    receiptRepository.countByResult(campaignId, fromUtc, toUtc);
            long attributed = counts.getOrDefault(ReceiptResult.ATTRIBUTED, 0L);
            long late = counts.getOrDefault(ReceiptResult.LATE_RECEIPT, 0L);
            return new AttributionDiagnosticsResponse(
                    campaignId, utcDate,
                    campaign.windowVersion(), campaign.attributionWindowMinutes(),
                    attributed, late, attributed + late);
        });
    }

    // ---- 内部辅助（作用域末尾） ----

    /** 幂等操作类型，同时标识存储响应的反序列化类型。 */
    private enum Operation {
        CREATE_CAMPAIGN,
        APPLY,
        CONFIRM,
        CANCEL,
        UPDATE_WINDOW,
        RECEIPT,
        BATCH_RECEIPT
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
     * 构建幂等指纹：规范化 JSON（键序固定、集合已排序）的 SHA-256，
     * 覆盖操作类型与全部结果相关字段，不使用未转义分隔符拼接。
     */
    private String fingerprint(Map<String, ?> fields) {
        Map<String, Object> canonical = new LinkedHashMap<>(fields);
        String json = writeJson(canonical);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(
                    digest.digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
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
     * 处理单笔回执（调用方已持幂等键与事务）。裁决顺序：
     * 不存在/已取消/回执时刻早于预占 → 422；已过期 → 409；
     * 窗口内 → ATTRIBUTED（RESERVED 则结算为 CONFIRMED）；窗口外 → LATE_RECEIPT。
     */
    private Receipt processReceipt(String reservationId, String operator,
                                   long receiptAtUtc, String batchRequestId) {
        long now = clock.millis();
        Reservation current = reservationRepository.lockById(reservationId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "receipt rejected: reservation not found: " + reservationId));
        // 回执按提交顺序裁决：受理时刻已到期的预占先结算为 EXPIRED
        expireIfDue(current, now);
        current = reservationRepository.lockById(reservationId).orElseThrow();

        validateReceiptable(current, receiptAtUtc);
        return applyReceipt(current, receiptAtUtc, operator, batchRequestId, now);
    }

    /**
     * 校验回执可受理性（单笔与批量共用）：回执时刻早于预占、已取消 → 422；已过期 → 409。
     */
    private void validateReceiptable(Reservation current, long receiptAtUtc) {
        if (receiptAtUtc < current.createdAtUtc()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "receipt rejected: receipt time " + receiptAtUtc
                            + " is earlier than reservation time " + current.createdAtUtc()
                            + " for reservation " + current.reservationId());
        }
        if (current.status() == ReservationStatus.CANCELLED) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "receipt rejected: reservation " + current.reservationId() + " is CANCELLED");
        }
        if (current.status() == ReservationStatus.EXPIRED) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "receipt rejected: reservation " + current.reservationId() + " is EXPIRED");
        }
    }

    /**
     * 应用一条已通过校验的回执：按预占快照窗口 [reservedAt, reservedAt + window)
     * （UTC，左闭右开）裁决；窗口内且仍为 RESERVED 时结算为 CONFIRMED（占用额度保持），
     * 窗口外仅记录 LATE_RECEIPT，不确认也不释放配额。回执行不可变。
     */
    private Receipt applyReceipt(Reservation current, long receiptAtUtc,
                                 String operator, String batchRequestId, long now) {
        long windowMillis = current.windowMinutes() * 60_000L;
        boolean inWindow = receiptAtUtc < current.createdAtUtc() + windowMillis;
        ReceiptResult result = inWindow ? ReceiptResult.ATTRIBUTED : ReceiptResult.LATE_RECEIPT;

        if (inWindow && current.status() == ReservationStatus.RESERVED) {
            // 同一预占最终只结算一次：CAS 保证并发下只有一个终态
            if (!reservationRepository.compareAndSetStatus(
                    current.reservationId(), ReservationStatus.RESERVED,
                    ReservationStatus.CONFIRMED, now)) {
                throw new ApiException(HttpStatus.CONFLICT, "reservation state changed concurrently");
            }
        }

        Receipt receipt = new Receipt(
                0L,
                UUID.randomUUID().toString().replace("-", ""),
                current.reservationId(),
                current.campaignId(),
                current.visitorId(),
                operator,
                receiptAtUtc,
                current.createdAtUtc(),
                current.windowVersion(),
                current.windowMinutes(),
                result,
                batchRequestId,
                now);
        long seq = receiptRepository.insert(receipt);
        return new Receipt(seq, receipt.receiptId(), receipt.reservationId(), receipt.campaignId(),
                receipt.visitorId(), receipt.operatorId(), receipt.receiptAtUtc(),
                receipt.reservedAtUtc(), receipt.windowVersion(), receipt.windowMinutes(),
                receipt.result(), receipt.batchRequestId(), receipt.createdAtUtc());
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

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }
}
