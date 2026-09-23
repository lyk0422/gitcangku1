package com.example.starter.exposure.exposure;

import com.example.starter.exposure.domain.Campaign;
import com.example.starter.exposure.domain.ExposureReceipt;
import com.example.starter.exposure.domain.Reservation;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.domain.SnapshotItemStatus;
import com.example.starter.exposure.domain.Withdrawal;
import com.example.starter.exposure.domain.WithdrawalItem;
import com.example.starter.exposure.domain.WithdrawalStatus;
import com.example.starter.exposure.repo.CampaignRepository;
import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.exposure.repo.LedgerRepository;
import com.example.starter.exposure.repo.ReceiptRepository;
import com.example.starter.exposure.repo.ReservationRepository;
import com.example.starter.exposure.repo.WithdrawalItemRepository;
import com.example.starter.exposure.repo.WithdrawalRepository;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReceiptRequest;
import com.example.starter.exposure.web.ReceiptResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SettleWithdrawalRequest;
import com.example.starter.exposure.web.WithdrawCampaignRequest;
import com.example.starter.exposure.web.WithdrawalItemResponse;
import com.example.starter.exposure.web.WithdrawalResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 公告曝光频控业务服务实现。
 *
 * <p>所有写操作以 requestId 为全局幂等键：同键同参重放原成功结果，异参 409；
 * 业务失败随事务回滚，不占幂等键。所有操作与额度查询先结算相关过期预占，
 * 不依赖后台定时器。终态竞争由行锁 + 状态 CAS 保证只允许一个终态。</p>
 *
 * <p>版本撤回：撤回在公告行锁内原子完成——禁止该版本新预占（申请与撤回在同一
 * 公告行上串行），并把全部 PENDING 预占冻结为 SETTLING 快照；已确认曝光不回退。
 * 回执/到期/结算对同一预占的终态竞争同样由行锁 + CAS 保证唯一终态，
 * 快照项决议与预占终态在同一事务提交，公告/访客计数守恒。</p>
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
    private final WithdrawalItemRepository withdrawalItemRepository;
    private final ReceiptRepository receiptRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public ExposureServiceImpl(Clock clock,
                               CampaignRepository campaignRepository,
                               ReservationRepository reservationRepository,
                               LedgerRepository ledgerRepository,
                               IdempotencyRepository idempotencyRepository,
                               WithdrawalRepository withdrawalRepository,
                               WithdrawalItemRepository withdrawalItemRepository,
                               ReceiptRepository receiptRepository,
                               ObjectMapper objectMapper,
                               TransactionTemplate txTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.reservationRepository = reservationRepository;
        this.ledgerRepository = ledgerRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.withdrawalRepository = withdrawalRepository;
        this.withdrawalItemRepository = withdrawalItemRepository;
        this.receiptRepository = receiptRepository;
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
                            1,
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
    public ReservationResponse apply(ApplyExposureRequest request) {
        String fingerprint = request.campaignId() + "|" + request.visitorId();
        return runIdempotent(request.requestId(), Operation.APPLY, fingerprint,
                ReservationResponse.class, () -> {
                    long now = clock.millis();
                    LocalDate utcDate = LocalDate.now(clock);
                    // 公告行锁：与撤回在公告行上串行，保证撤回原子禁止新预占
                    Campaign campaign = campaignRepository.lockById(request.campaignId())
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "campaign not found: " + request.campaignId()));

                    // 当前版本已被撤回：禁止新预占
                    if (withdrawalRepository.existsByCampaignAndVersion(
                            campaign.campaignId(), campaign.version())) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "campaign version withdrawn: " + campaign.campaignId()
                                        + " v" + campaign.version());
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
                            campaign.version(),
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
    public WithdrawalResponse withdraw(WithdrawCampaignRequest request) {
        String fingerprint = request.withdrawalKey() + "|" + request.campaignId() + "|"
                + request.campaignVersion() + "|" + request.cutoffAt();
        return runIdempotent(request.requestId(), Operation.WITHDRAW, fingerprint,
                WithdrawalResponse.class, () -> {
                    long now = clock.millis();
                    // 公告行锁：与申请串行，撤回后该版本不再产生新预占
                    Campaign campaign = campaignRepository.lockById(request.campaignId())
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "campaign not found: " + request.campaignId()));
                    if (campaign.version() != request.campaignVersion()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "campaign version mismatch: current " + campaign.version()
                                        + ", requested " + request.campaignVersion());
                    }
                    if (withdrawalRepository.existsByCampaignAndVersion(
                            campaign.campaignId(), campaign.version())) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "campaign version already withdrawn: " + campaign.campaignId()
                                        + " v" + campaign.version());
                    }

                    Withdrawal withdrawal = new Withdrawal(
                            request.withdrawalKey(),
                            campaign.campaignId(),
                            campaign.version(),
                            request.cutoffAt(),
                            WithdrawalStatus.SETTLING,
                            now,
                            null);
                    try {
                        withdrawalRepository.insert(withdrawal);
                    } catch (DuplicateKeyException duplicate) {
                        // withdrawalKey 或 (campaign,version) 并发唯一冲突
                        throw new ApiException(HttpStatus.CONFLICT,
                                "withdrawal key already exists: " + request.withdrawalKey());
                    }

                    // 冻结全部 PENDING(RESERVED) 预占为 SETTLING 快照；已确认曝光不回退
                    List<Reservation> pending = reservationRepository.lockReservedByCampaignVersion(
                            campaign.campaignId(), campaign.version());
                    List<WithdrawalItemResponse> items = new ArrayList<>();
                    for (Reservation reservation : pending) {
                        WithdrawalItem item = new WithdrawalItem(
                                withdrawal.withdrawalKey(),
                                reservation.reservationId(),
                                reservation.campaignVersion(),
                                reservation.visitorId(),
                                reservation.createdAtUtc(),
                                reservation.expiresAtUtc(),
                                SnapshotItemStatus.SETTLING,
                                null,
                                null);
                        withdrawalItemRepository.insert(item);
                        items.add(WithdrawalItemResponse.from(item));
                    }
                    return WithdrawalResponse.from(withdrawal, items);
                });
    }

    @Override
    public ReceiptResponse submitReceipt(ReceiptRequest request) {
        String fingerprint = request.receiptKey() + "|" + request.reservationId() + "|"
                + request.occurredAt();
        return runIdempotent(request.requestId(), Operation.RECEIPT, fingerprint,
                ReceiptResponse.class, () -> {
                    long now = clock.millis();

                    // receiptKey 唯一：同键同参重放原决议，异参 409
                    var replayed = replayReceiptIfExists(request);
                    if (replayed.isPresent()) {
                        return replayed.get();
                    }

                    Reservation reservation = reservationRepository.lockById(request.reservationId())
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "reservation not found: " + request.reservationId()));
                    // 持预占行锁后重查：并发同键回执已提交时重放其决议，而非误判冲突
                    var replayedAfterLock = replayReceiptIfExists(request);
                    if (replayedAfterLock.isPresent()) {
                        return replayedAfterLock.get();
                    }
                    WithdrawalItem item = withdrawalItemRepository
                            .findByReservationId(request.reservationId())
                            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                                    "reservation is not in a settling snapshot: "
                                            + request.reservationId()));
                    if (item.status() != SnapshotItemStatus.SETTLING) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "snapshot item already " + item.status() + ": "
                                        + request.reservationId());
                    }
                    Withdrawal withdrawal = withdrawalRepository.findByKey(item.withdrawalKey())
                            .orElseThrow(() -> new IllegalStateException(
                                    "withdrawal missing: " + item.withdrawalKey()));

                    // 仅 occurredAt 不早于预占时刻、早于截点，且提交时未过到期时刻才可确认
                    boolean confirmable = request.occurredAt() >= item.reservedAtUtc()
                            && request.occurredAt() < withdrawal.cutoffAtUtc()
                            && now < item.expiresAtUtc();
                    SnapshotItemStatus decision = confirmable
                            ? SnapshotItemStatus.CONFIRMED : SnapshotItemStatus.REJECTED;
                    ReservationStatus target = confirmable
                            ? ReservationStatus.CONFIRMED : ReservationStatus.REJECTED;

                    if (reservation.status() != ReservationStatus.RESERVED) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "reservation is " + reservation.status()
                                        + ", cannot decide receipt");
                    }
                    if (!reservationRepository.compareAndSetStatus(
                            reservation.reservationId(), ReservationStatus.RESERVED, target, now)) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "reservation state changed concurrently");
                    }
                    if (!confirmable) {
                        // REJECTED 释放两级额度；固定顺序先总账后访客账
                        ledgerRepository.releaseTotal(
                                reservation.campaignId(), reservation.utcDate().toLocalDate());
                        ledgerRepository.releaseVisitor(
                                reservation.campaignId(), reservation.visitorId(),
                                reservation.utcDate().toLocalDate());
                    }
                    if (!withdrawalItemRepository.decideIfSettling(
                            item.reservationId(), decision, now,
                            confirmable ? "RECEIPT_CONFIRMED" : "RECEIPT_REJECTED")) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "snapshot item decided concurrently");
                    }
                    ExposureReceipt receipt = new ExposureReceipt(
                            request.receiptKey(), request.reservationId(),
                            request.occurredAt(), decision, now);
                    receiptRepository.insert(receipt);
                    withdrawalRepository.completeIfAllTerminal(withdrawal.withdrawalKey(), now);
                    return ReceiptResponse.from(receipt);
                });
    }

    @Override
    public WithdrawalResponse settle(String withdrawalKey, SettleWithdrawalRequest request) {
        List<String> submittedKeys = request.items().stream()
                .map(key -> key.reservationId() + ":" + key.campaignVersion())
                .sorted()
                .toList();
        String fingerprint = withdrawalKey + "|" + String.join(",", submittedKeys);
        return runIdempotent(request.requestId(), Operation.SETTLE, fingerprint,
                WithdrawalResponse.class, () -> {
                    long now = clock.millis();
                    Withdrawal withdrawal = withdrawalRepository.findByKey(withdrawalKey)
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                    "withdrawal not found: " + withdrawalKey));
                    List<WithdrawalItem> allItems =
                            withdrawalItemRepository.findByWithdrawalKey(withdrawalKey);

                    // 提交的预占版本集合必须与快照完全一致
                    Set<String> expected = new HashSet<>();
                    for (WithdrawalItem item : allItems) {
                        expected.add(item.reservationId() + ":" + item.campaignVersion());
                    }
                    if (!expected.equals(new HashSet<>(submittedKeys))) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "settlement set does not match snapshot: " + withdrawalKey);
                    }

                    List<WithdrawalItem> settling = allItems.stream()
                            .filter(item -> item.status() == SnapshotItemStatus.SETTLING)
                            .toList();
                    // 仍存在可合法确认的项：保持 PENDING，整体不变更，返回 409（不猜测结果）
                    for (WithdrawalItem item : settling) {
                        if (item.reservedAtUtc() < withdrawal.cutoffAtUtc()
                                && now < item.expiresAtUtc()) {
                            throw new ApiException(HttpStatus.CONFLICT,
                                    "snapshot item still confirmable: " + item.reservationId());
                        }
                    }
                    // 全部未回执项已无法合法确认：按相同时间规则释放
                    for (WithdrawalItem item : settling) {
                        releaseUnconfirmable(item, now);
                    }
                    withdrawalRepository.completeIfAllTerminal(withdrawalKey, now);
                    return loadWithdrawal(withdrawalKey);
                });
    }

    @Override
    public WithdrawalResponse getWithdrawal(String withdrawalKey) {
        // 只读查询：不加锁、不触发任何状态变更
        return loadWithdrawal(withdrawalKey);
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
            // 撤回快照内的预占只能由回执/到期/结算决议，确认/取消不得绕过截点规则
            var snapshotItem = withdrawalItemRepository.findByReservationId(reservationId);
            if (snapshotItem.isPresent()
                    && snapshotItem.get().status() == SnapshotItemStatus.SETTLING) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "reservation is in a settling snapshot, use receipt: " + reservationId);
            }
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
     * 未到期或已非 RESERVED 则不做任何变更。属于撤回快照的预占同步决议快照项，
     * 并在快照全部终态后收口撤回单。
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
                withdrawalItemRepository.findByReservationId(reservation.reservationId())
                        .ifPresent(item -> {
                            if (item.status() == SnapshotItemStatus.SETTLING
                                    && withdrawalItemRepository.decideIfSettling(
                                            item.reservationId(), SnapshotItemStatus.EXPIRED,
                                            now, "EXPIRED")) {
                                withdrawalRepository.completeIfAllTerminal(
                                        item.withdrawalKey(), now);
                            }
                        });
            }
        }
    }

    /**
     * 若 receiptKey 已存在：同参返回原决议（重放），异参抛 409；不存在返回 empty。
     */
    private java.util.Optional<ReceiptResponse> replayReceiptIfExists(ReceiptRequest request) {
        var existing = receiptRepository.findByKey(request.receiptKey());
        if (existing.isEmpty()) {
            return java.util.Optional.empty();
        }
        ExposureReceipt stored = existing.get();
        if (!stored.reservationId().equals(request.reservationId())
                || stored.occurredAtUtc() != request.occurredAt()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "receipt key reused with different parameters: " + request.receiptKey());
        }
        return java.util.Optional.of(ReceiptResponse.from(stored));
    }

    /**
     * 结算释放一条已无法合法确认的未回执快照项：已到期记 EXPIRED，
     * 无合法 occurredAt 区间（预占时刻不早于截点）记 REJECTED；
     * 释放两级额度并决议快照项。并发回执/到期抢先终态时整体回滚，由调用方重试。
     */
    private void releaseUnconfirmable(WithdrawalItem item, long now) {
        boolean expired = now >= item.expiresAtUtc();
        ReservationStatus reservationTarget = expired
                ? ReservationStatus.EXPIRED : ReservationStatus.REJECTED;
        SnapshotItemStatus itemTarget = expired
                ? SnapshotItemStatus.EXPIRED : SnapshotItemStatus.REJECTED;

        Reservation reservation = reservationRepository.lockById(item.reservationId())
                .orElseThrow(() -> new IllegalStateException(
                        "reservation missing: " + item.reservationId()));
        if (reservation.status() != ReservationStatus.RESERVED) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "reservation state changed concurrently: " + item.reservationId());
        }
        if (!reservationRepository.compareAndSetStatus(
                item.reservationId(), ReservationStatus.RESERVED, reservationTarget, now)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "reservation state changed concurrently: " + item.reservationId());
        }
        LocalDate utcDate = reservation.utcDate().toLocalDate();
        ledgerRepository.releaseTotal(reservation.campaignId(), utcDate);
        ledgerRepository.releaseVisitor(reservation.campaignId(), reservation.visitorId(), utcDate);
        if (!withdrawalItemRepository.decideIfSettling(
                item.reservationId(), itemTarget, now,
                expired ? "SETTLED_EXPIRED" : "SETTLED_NO_VALID_OCCURRENCE")) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "snapshot item decided concurrently: " + item.reservationId());
        }
    }

    /**
     * 读取撤回单及快照项并组装视图（只读）。
     */
    private WithdrawalResponse loadWithdrawal(String withdrawalKey) {
        Withdrawal withdrawal = withdrawalRepository.findByKey(withdrawalKey)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "withdrawal not found: " + withdrawalKey));
        List<WithdrawalItemResponse> items = withdrawalItemRepository
                .findByWithdrawalKey(withdrawalKey)
                .stream()
                .map(WithdrawalItemResponse::from)
                .toList();
        return WithdrawalResponse.from(withdrawal, items);
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
