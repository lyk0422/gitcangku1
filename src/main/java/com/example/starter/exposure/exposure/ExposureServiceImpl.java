package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.ItemDecision;
import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.domain.SnapshotItem;
import com.example.starter.exposure.domain.Withdrawal;
import com.example.starter.exposure.domain.WithdrawalStatus;
import com.example.starter.exposure.repo.CampaignRepository;
import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.exposure.repo.LedgerRepository;
import com.example.starter.exposure.repo.ReservationRepository;
import com.example.starter.exposure.repo.SnapshotItemRepository;
import com.example.starter.exposure.repo.WithdrawalRepository;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReceiptRequest;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SettleWithdrawalRequest;
import com.example.starter.exposure.web.SnapshotItemResponse;
import com.example.starter.exposure.web.WithdrawCampaignRequest;
import com.example.starter.exposure.web.WithdrawalResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 公告曝光频控业务服务实现。
 *
 * <p>所有写操作以 requestId 为全局幂等键：同键同参重放原成功结果，异参 409；
 * 业务失败随事务回滚，不占幂等键。所有操作与额度查询先结算相关过期预占，
 * 不依赖后台定时器。终态竞争由行锁 + 状态 CAS 保证只允许一个终态。</p>
 *
 * <p>版本撤回：撤回事务持公告行锁，原子标记禁止新预占，并把全部在途预占
 * CAS 为 SETTLING 同时插入冻结快照项。快照项的终态决议（回执确认/驳回、
 * 到期释放、显式结算）统一按 撤回单 → 快照项 → 预占单 → 账目 的顺序加锁，
 * 每项只允许一个终态；全部快照项终态后撤回单转 COMPLETED。</p>
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
    private final WithdrawalRepository withdrawalRepository;
    private final SnapshotItemRepository snapshotItemRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public ExposureServiceImpl(Clock clock,
                               CampaignRepository campaignRepository,
                               ReservationRepository reservationRepository,
                               LedgerRepository ledgerRepository,
                               IdempotencyRepository idempotencyRepository,
                               WithdrawalRepository withdrawalRepository,
                               SnapshotItemRepository snapshotItemRepository,
                               ObjectMapper objectMapper,
                               TransactionTemplate txTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.withdrawalRepository = withdrawalRepository;
        this.snapshotItemRepository = snapshotItemRepository;
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
                            clock.millis(),
                            1,
                            null);
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
    public ReservationResponse apply(ApplyExposureRequest request) {
        String fingerprint = request.campaignId() + "|" + request.visitorId();
        return runIdempotent(request.requestId(), Operation.APPLY, fingerprint,
                ReservationResponse.class, () -> {
                    long now = clock.millis();
                    LocalDate utcDate = LocalDate.now(clock);
                    // 公告行锁：与撤回互斥，撤回后原子禁止新预占
                    Campaign campaign = campaignRepository.lockById(request.campaignId())
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "campaign not found: " + request.campaignId()));
                    if (campaign.withdrawnAtUtc() != null) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "campaign withdrawn, no new reservations: " + campaign.campaignId());
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
            long now = clock.millis();
            Reservation probe = reservationRepository.findById(reservationId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "reservation not found: " + reservationId));
            if (probe.status() == ReservationStatus.SETTLING) {
                // SETTLING 到期释放须按 撤回单 -> 快照项 -> 预占单 顺序加锁，不能持预占行锁反序加锁
                settleSettlingIfExpired(reservationId, now);
            } else {
                Reservation locked = reservationRepository.lockById(reservationId).orElseThrow();
                expireIfDue(locked, now);
            }
            return ReservationResponse.from(
                    reservationRepository.findById(reservationId).orElseThrow());
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
    public WithdrawalResponse withdraw(WithdrawCampaignRequest request) {
        String fingerprint = request.withdrawalKey() + "|" + request.campaignId() + "|"
                + request.campaignVersion() + "|" + request.cutoffAtUtc();
        return runIdempotent(request.requestId(), Operation.WITHDRAW, fingerprint,
                WithdrawalResponse.class, () -> {
                    long now = clock.millis();
                    // 公告行锁：串行化并发撤回与新预占
                    Campaign campaign = campaignRepository.lockById(request.campaignId())
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "campaign not found: " + request.campaignId()));

                    // withdrawalKey 业务键：同参重放原结果，异参 409
                    var existing = withdrawalRepository.findByKey(request.withdrawalKey());
                    if (existing.isPresent()) {
                        Withdrawal w = existing.get();
                        if (w.campaignId().equals(request.campaignId())
                                && w.campaignVersion() == request.campaignVersion()
                                && w.cutoffAtUtc() == request.cutoffAtUtc()) {
                            return buildWithdrawalResponse(w);
                        }
                        throw new ApiException(HttpStatus.CONFLICT,
                                "withdrawal key reused with different parameters: "
                                        + request.withdrawalKey());
                    }
                    if (campaign.withdrawnAtUtc() != null) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "campaign already withdrawn: " + campaign.campaignId());
                    }
                    if (campaign.currentVersion() != request.campaignVersion()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "stale campaign version: expected " + campaign.currentVersion()
                                        + " but got " + request.campaignVersion());
                    }

                    // 先结算已到期 RESERVED（走 EXPIRED 而非快照），再冻结剩余在途
                    settleExpired(campaign.campaignId(), now);

                    if (!campaignRepository.markWithdrawn(campaign.campaignId(), now)) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "campaign withdrawn concurrently: " + campaign.campaignId());
                    }

                    List<Reservation> inflight =
                            reservationRepository.lockReservedByCampaign(campaign.campaignId());
                    WithdrawalStatus status =
                            inflight.isEmpty() ? WithdrawalStatus.COMPLETED : WithdrawalStatus.SETTLING;
                    Withdrawal withdrawal = new Withdrawal(
                            request.withdrawalKey(),
                            campaign.campaignId(),
                            request.campaignVersion(),
                            request.cutoffAtUtc(),
                            status,
                            now,
                            inflight.isEmpty() ? now : null);
                    withdrawalRepository.insert(withdrawal);

                    for (Reservation r : inflight) {
                        // 已持预占行锁，CAS 必定成功；失败说明实现被破坏
                        if (!reservationRepository.markSettling(r.reservationId())) {
                            throw new IllegalStateException(
                                    "reservation changed while locked: " + r.reservationId());
                        }
                        snapshotItemRepository.insert(new SnapshotItem(
                                r.reservationId(),
                                withdrawal.withdrawalKey(),
                                campaign.campaignId(),
                                request.campaignVersion(),
                                r.visitorId(),
                                r.createdAtUtc(),
                                r.expiresAtUtc(),
                                ItemDecision.PENDING,
                                null,
                                null,
                                null));
                    }
                    return buildWithdrawalResponse(withdrawal);
                });
    }

    @Override
    public SnapshotItemResponse receipt(String reservationId, ReceiptRequest request) {
        String fingerprint = reservationId + "|" + request.receiptKey() + "|" + request.occurredAtUtc();
        return runIdempotent(request.requestId(), Operation.RECEIPT, fingerprint,
                SnapshotItemResponse.class, () -> {
                    long now = clock.millis();

                    // receiptKey 全局唯一：已被其他预占占用则 409（唯一约束兜底）
                    var byReceiptKey = snapshotItemRepository.findByReceiptKey(request.receiptKey());
                    if (byReceiptKey.isPresent()
                            && !byReceiptKey.get().reservationId().equals(reservationId)) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "receipt key already used: " + request.receiptKey());
                    }

                    // 无锁探测取得撤回键，随后按 撤回单 -> 快照项 -> 预占单 顺序加锁
                    SnapshotItem probe = snapshotItemRepository.findByReservationId(reservationId)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "snapshot item not found for reservation: " + reservationId));
                    Withdrawal withdrawal = withdrawalRepository.lockByKey(probe.withdrawalKey())
                            .orElseThrow(() -> new IllegalStateException(
                                    "withdrawal missing: " + probe.withdrawalKey()));
                    SnapshotItem item = snapshotItemRepository.lockByReservationId(reservationId)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "snapshot item not found for reservation: " + reservationId));

                    if (item.decision() != ItemDecision.PENDING) {
                        // 业务键重放：同回执键同发生时刻返回原决议，否则 409
                        if (request.receiptKey().equals(item.receiptKey())
                                && Objects.equals(request.occurredAtUtc(), item.occurredAtUtc())) {
                            return SnapshotItemResponse.from(item);
                        }
                        throw new ApiException(HttpStatus.CONFLICT,
                                "snapshot item already decided: " + reservationId);
                    }

                    Reservation reservation = reservationRepository.lockById(reservationId)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "reservation not found: " + reservationId));

                    // 合法确认：occurredAt 早于截点、不早于预占时刻、提交时未过到期时刻
                    boolean confirmable = request.occurredAtUtc() < withdrawal.cutoffAtUtc()
                            && request.occurredAtUtc() >= item.reservedAtUtc()
                            && now < item.expiresAtUtc();
                    if (confirmable) {
                        if (!reservationRepository.compareAndSetStatus(reservationId,
                                ReservationStatus.SETTLING, ReservationStatus.CONFIRMED, now)) {
                            throw new ApiException(HttpStatus.CONFLICT,
                                    "reservation state changed concurrently");
                        }
                        snapshotItemRepository.decideWithReceipt(reservationId,
                                ItemDecision.CONFIRMED, request.receiptKey(),
                                request.occurredAtUtc(), now);
                        // 确认继续消耗原频控，账目不变
                    } else {
                        if (!reservationRepository.compareAndSetStatus(reservationId,
                                ReservationStatus.SETTLING, ReservationStatus.REJECTED, now)) {
                            throw new ApiException(HttpStatus.CONFLICT,
                                    "reservation state changed concurrently");
                        }
                        snapshotItemRepository.decideWithReceipt(reservationId,
                                ItemDecision.REJECTED, request.receiptKey(),
                                request.occurredAtUtc(), now);
                        // 驳回释放两级额度；固定顺序先总账后访客账
                        LocalDate utcDate = reservation.utcDate().toLocalDate();
                        ledgerRepository.releaseTotal(reservation.campaignId(), utcDate);
                        ledgerRepository.releaseVisitor(
                                reservation.campaignId(), reservation.visitorId(), utcDate);
                    }

                    completeWithdrawalIfDone(withdrawal.withdrawalKey(), now);
                    return SnapshotItemResponse.from(
                            snapshotItemRepository.lockByReservationId(reservationId).orElseThrow());
                });
    }

    @Override
    public WithdrawalResponse settle(String withdrawalKey, SettleWithdrawalRequest request) {
        String fingerprint = withdrawalKey + "|" + settleSetFingerprint(request.items());
        long deadline = System.currentTimeMillis() + IDEMPOTENT_WAIT_MILLIS;
        while (true) {
            try {
                SettleOutcome outcome = txTemplate.execute(status -> {
                    var existing = idempotencyRepository.lockById(request.requestId());
                    if (existing.isPresent()) {
                        IdempotencyRecord record = existing.get();
                        if (!record.operation().equals(Operation.SETTLE.name())
                                || !record.requestFingerprint().equals(fingerprint)) {
                            throw new ApiException(HttpStatus.CONFLICT,
                                    "idempotency key reused with different parameters: "
                                            + request.requestId());
                        }
                        return SettleOutcome.completed(
                                readJson(record.responseJson(), WithdrawalResponse.class));
                    }

                    Withdrawal withdrawal = withdrawalRepository.lockByKey(withdrawalKey)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "withdrawal not found: " + withdrawalKey));
                    List<SnapshotItem> items = snapshotItemRepository.lockByWithdrawalKey(withdrawalKey);
                    verifySnapshotSet(items, request.items());

                    long now = clock.millis();
                    int stillConfirmable = 0;
                    for (SnapshotItem item : items) {
                        if (item.decision() != ItemDecision.PENDING) {
                            continue;
                        }
                        // 仍可能合法确认：存在 occurredAt 满足 预占时刻 <= occurredAt < 截点，
                        // 且当前未到到期时刻（即 reservedAt < cutoffAt 且 now < expiresAt）
                        if (item.reservedAtUtc() < withdrawal.cutoffAtUtc()
                                && now < item.expiresAtUtc()) {
                            stillConfirmable++;
                            continue;
                        }
                        rejectSettlingItem(item, now);
                    }
                    if (stillConfirmable > 0) {
                        // 无法合法确认项的释放随事务提交；但不占幂等键，允许后续重试
                        return SettleOutcome.incomplete(stillConfirmable);
                    }
                    withdrawalRepository.markCompleted(withdrawalKey, now);
                    WithdrawalResponse response = buildWithdrawalResponse(
                            withdrawalRepository.lockByKey(withdrawalKey).orElseThrow());
                    idempotencyRepository.insert(new IdempotencyRecord(
                            request.requestId(), Operation.SETTLE.name(),
                            fingerprint, writeJson(response)), now);
                    return SettleOutcome.completed(response);
                });
                if (outcome.incomplete()) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "settlement incomplete: " + outcome.stillConfirmable()
                                    + " item(s) may still be legally confirmed");
                }
                return outcome.response();
            } catch (DuplicateKeyException duplicate) {
                if (System.currentTimeMillis() >= deadline) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "concurrent idempotency key conflict: " + request.requestId());
                }
                sleepQuietly();
            }
        }
    }

    @Override
    public WithdrawalResponse getWithdrawal(String withdrawalKey) {
        // 只读查询：不加锁、不触发任何到期结算或状态变更
        Withdrawal withdrawal = withdrawalRepository.findByKey(withdrawalKey)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "withdrawal not found: " + withdrawalKey));
        return buildWithdrawalResponse(withdrawal);
    }

    // ---- 内部辅助（作用域末尾） ----

    /** 幂等操作类型，同时标识存储响应的反序列化类型。 */
    private enum Operation {
        CREATE_CAMPAIGN,
        APPLY,
        CONFIRM,
        CANCEL,
        WITHDRAW,
        RECEIPT,
        SETTLE
    }

    /** 显式结算的事务内结果：完成（含响应）或仍有可合法确认项（提交释放但不占键）。 */
    private record SettleOutcome(boolean incomplete, int stillConfirmable, WithdrawalResponse response) {
        static SettleOutcome completed(WithdrawalResponse response) {
            return new SettleOutcome(false, 0, response);
        }

        static SettleOutcome incomplete(int stillConfirmable) {
            return new SettleOutcome(true, stillConfirmable, null);
        }
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
                        return readJson(record.responseJson(), responseType);
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
                sleepQuietly();
            }
        }
    }

    /**
     * 确认/取消状态机。调用前已持幂等键；行锁 + CAS 保证并发只有一个终态。
     * SETTLING 预占禁止普通确认/取消，须走快照回执。
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

        if (current.status() == ReservationStatus.SETTLING) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "reservation is SETTLING under withdrawal, use receipt: " + reservationId);
        }
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
                || current.status() == ReservationStatus.EXPIRED
                || current.status() == ReservationStatus.REJECTED) {
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
     * 结算某公告当前已到期（now &gt;= expiresAt）的预占：
     * RESERVED 逐个 CAS 为 EXPIRED 并释放额度；SETTLING 按快照项顺序决议为 REJECTED。
     */
    private void settleExpired(String campaignId, long now) {
        List<Reservation> expired = reservationRepository.lockExpiredReserved(campaignId, now);
        for (Reservation reservation : expired) {
            expireIfDue(reservation, now);
        }
        // SETTLING 到期项：无锁扫描线索后逐项按 撤回单 -> 快照项 -> 预占单 顺序加锁决议
        for (Reservation hint : reservationRepository.findExpiredSettling(campaignId, now)) {
            settleSettlingIfExpired(hint.reservationId(), now);
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

    /**
     * SETTLING 预占的到期释放：按 撤回单 -> 快照项 -> 预占单 -> 账目 顺序加锁，
     * 仅当快照项仍 PENDING 且预占仍 SETTLING 且已到期时决议为 REJECTED 并释放额度。
     */
    private void settleSettlingIfExpired(String reservationId, long now) {
        var probe = snapshotItemRepository.findByReservationId(reservationId);
        if (probe.isEmpty() || probe.get().decision() != ItemDecision.PENDING) {
            return;
        }
        String withdrawalKey = probe.get().withdrawalKey();
        withdrawalRepository.lockByKey(withdrawalKey)
                .orElseThrow(() -> new IllegalStateException("withdrawal missing: " + withdrawalKey));
        SnapshotItem item = snapshotItemRepository.lockByReservationId(reservationId).orElseThrow();
        if (item.decision() != ItemDecision.PENDING || now < item.expiresAtUtc()) {
            return;
        }
        rejectSettlingItem(item, now);
        completeWithdrawalIfDone(withdrawalKey, now);
    }

    /**
     * 把仍 PENDING 的快照项决议为 REJECTED：预占 CAS SETTLING -> REJECTED，
     * 快照项 CAS PENDING -> REJECTED，并释放两级额度。调用方须已持快照项行锁。
     */
    private void rejectSettlingItem(SnapshotItem item, long now) {
        Reservation reservation = reservationRepository.lockById(item.reservationId())
                .orElseThrow(() -> new IllegalStateException(
                        "reservation missing: " + item.reservationId()));
        if (reservation.status() != ReservationStatus.SETTLING) {
            return;
        }
        if (!reservationRepository.compareAndSetStatus(item.reservationId(),
                ReservationStatus.SETTLING, ReservationStatus.REJECTED, now)) {
            return;
        }
        snapshotItemRepository.decideWithoutReceipt(item.reservationId(), ItemDecision.REJECTED, now);
        LocalDate utcDate = reservation.utcDate().toLocalDate();
        ledgerRepository.releaseTotal(reservation.campaignId(), utcDate);
        ledgerRepository.releaseVisitor(reservation.campaignId(), reservation.visitorId(), utcDate);
    }

    /** 全部快照项终态后把撤回单 CAS 为 COMPLETED。 */
    private void completeWithdrawalIfDone(String withdrawalKey, long now) {
        if (snapshotItemRepository.countPending(withdrawalKey) == 0) {
            withdrawalRepository.markCompleted(withdrawalKey, now);
        }
    }

    /** 校验发布方提交的预占版本集合与快照完全一致（缺项、多项、版本不符均 409）。 */
    private void verifySnapshotSet(List<SnapshotItem> items, List<SettleWithdrawalRequest.SettleItem> submitted) {
        Map<String, Integer> actual = items.stream().collect(Collectors.toMap(
                SnapshotItem::reservationId, SnapshotItem::campaignVersion));
        if (submitted == null || submitted.size() != actual.size()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "settlement set does not match snapshot: expected " + actual.size() + " item(s)");
        }
        for (SettleWithdrawalRequest.SettleItem entry : submitted) {
            Integer version = actual.get(entry.reservationId());
            if (version == null || version != entry.campaignVersion()) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "settlement set mismatch for reservation: " + entry.reservationId());
            }
        }
    }

    /** 结算集合指纹：排序后的 预占编号:版本 序列做 SHA-256，避免超长。 */
    private String settleSetFingerprint(List<SettleWithdrawalRequest.SettleItem> items) {
        String canonical = items == null ? "" : items.stream()
                .map(i -> i.reservationId() + ":" + i.campaignVersion())
                .sorted()
                .collect(Collectors.joining(";"));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("failed to hash settlement set", e);
        }
    }

    private WithdrawalResponse buildWithdrawalResponse(Withdrawal withdrawal) {
        List<SnapshotItemResponse> items = snapshotItemRepository
                .findByWithdrawalKey(withdrawal.withdrawalKey())
                .stream()
                .map(SnapshotItemResponse::from)
                .toList();
        return WithdrawalResponse.of(withdrawal, items);
    }

    private Campaign requireCampaign(String campaignId) {
        return campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "campaign not found: " + campaignId));
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("failed to replay idempotent response", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(5L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "interrupted");
        }
    }
}
