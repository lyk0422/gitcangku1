package com.example.starter.exposure.budget;

import com.example.starter.exposure.budget.domain.BudgetCampaign;
import com.example.starter.exposure.budget.domain.BudgetReservation;
import com.example.starter.exposure.budget.domain.NormalizedTransfer;
import com.example.starter.exposure.budget.repo.BudgetCampaignRepository;
import com.example.starter.exposure.budget.repo.BudgetReservationRepository;
import com.example.starter.exposure.budget.repo.BudgetTransferRepository;
import com.example.starter.exposure.budget.repo.BudgetTransferRepository.TransferRecord;
import com.example.starter.exposure.budget.repo.BudgetTransferSnapshotRepository;
import com.example.starter.exposure.budget.web.BudgetApplyRequest;
import com.example.starter.exposure.budget.web.BudgetCampaignResponse;
import com.example.starter.exposure.budget.web.BudgetReservationActionRequest;
import com.example.starter.exposure.budget.web.BudgetReservationResponse;
import com.example.starter.exposure.budget.web.BudgetTransferActivateRequest;
import com.example.starter.exposure.budget.web.BudgetTransferActivateResponse;
import com.example.starter.exposure.budget.web.BudgetTransferItem;
import com.example.starter.exposure.budget.web.BudgetTransferPreviewRequest;
import com.example.starter.exposure.budget.web.BudgetTransferPreviewResponse;
import com.example.starter.exposure.budget.web.CampaignLedgerResponse;
import com.example.starter.exposure.budget.web.CreateBudgetCampaignRequest;
import com.example.starter.exposure.budget.web.TransferEvidenceResponse;
import com.example.starter.exposure.budget.web.TransferEvidenceResponse.Snapshot;
import com.example.starter.exposure.domain.ReservationStatus;
import com.example.starter.exposure.repo.IdempotencyRepository;
import com.example.starter.exposure.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.exposure.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 多活动曝光预算闭环转移服务实现。
 *
 * <p>预算恒等式对每个活动成立：总预算 = 可转余额 + 在途预占 + 已确认曝光。
 * 预占归属在创建时冻结，预算转移不改挂预占，迟到回执始终归原活动。
 * 激活在单事务内整批重读、校验、更新并冻结证据，不逐条转移；
 * 写操作以 requestId 为全局幂等键，transferKey 全局唯一。</p>
 */
@Service
public class BudgetServiceImpl implements BudgetService {

    static final int RESERVATION_TTL_MILLIS = 60_000;

    /** 并发同键竞争时等待胜出事务提交的最大时长。 */
    private static final long IDEMPOTENT_WAIT_MILLIS = 10_000L;

    private final Clock clock;
    private final BudgetCampaignRepository campaignRepository;
    private final BudgetReservationRepository reservationRepository;
    private final BudgetTransferRepository transferRepository;
    private final BudgetTransferSnapshotRepository snapshotRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public BudgetServiceImpl(Clock clock,
                             BudgetCampaignRepository campaignRepository,
                             BudgetReservationRepository reservationRepository,
                             BudgetTransferRepository transferRepository,
                             BudgetTransferSnapshotRepository snapshotRepository,
                             IdempotencyRepository idempotencyRepository,
                             ObjectMapper objectMapper,
                             TransactionTemplate txTemplate) {
        this.clock = clock;
        this.campaignRepository = campaignRepository;
        this.reservationRepository = reservationRepository;
        this.transferRepository = transferRepository;
        this.snapshotRepository = snapshotRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
    }

    @Override
    public BudgetCampaignResponse createCampaign(CreateBudgetCampaignRequest request) {
        if (request.windowEndUtc() <= request.windowStartUtc()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "window end must be greater than window start");
        }
        String fingerprint = request.campaignId() + "|" + request.tenantId() + "|"
                + request.windowStartUtc() + "|" + request.windowEndUtc() + "|"
                + request.audienceRule() + "|" + request.budget();
        return runIdempotent(request.requestId(), Operation.BUDGET_CREATE_CAMPAIGN, fingerprint,
                BudgetCampaignResponse.class, () -> {
                    if (campaignRepository.findById(request.campaignId()).isPresent()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "campaign already exists: " + request.campaignId());
                    }
                    BudgetCampaign campaign = new BudgetCampaign(
                            request.campaignId(),
                            request.tenantId(),
                            request.windowStartUtc(),
                            request.windowEndUtc(),
                            request.audienceRule(),
                            request.budget(),
                            1L,
                            clock.millis());
                    try {
                        campaignRepository.insert(campaign);
                    } catch (DuplicateKeyException duplicateCampaign) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "campaign already exists: " + request.campaignId());
                    }
                    return BudgetCampaignResponse.from(campaign);
                });
    }

    @Override
    public BudgetReservationResponse apply(BudgetApplyRequest request) {
        String fingerprint = request.campaignId() + "|" + request.visitorId();
        return runIdempotent(request.requestId(), Operation.BUDGET_APPLY, fingerprint,
                BudgetReservationResponse.class, () -> {
                    long now = clock.millis();
                    BudgetCampaign campaign = requireCampaign(request.campaignId());

                    // 行锁活动账本并先结算其过期预占，保证按最新预算与在途数校验
                    BudgetCampaign locked = campaignRepository.lockById(campaign.campaignId()).orElseThrow();
                    settleExpired(locked.campaignId(), now);
                    long inflight = reservationRepository.countReserved(locked.campaignId());
                    long confirmed = reservationRepository.countConfirmed(locked.campaignId());
                    if (inflight + confirmed + 1 > locked.budget()) {
                        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                                "transferable budget exhausted for campaign " + locked.campaignId());
                    }

                    String reservationId = UUID.randomUUID().toString().replace("-", "");
                    BudgetReservation reservation = new BudgetReservation(
                            reservationId,
                            locked.campaignId(),
                            request.visitorId(),
                            ReservationStatus.RESERVED,
                            now,
                            now + RESERVATION_TTL_MILLIS,
                            null);
                    reservationRepository.insert(reservation);
                    return BudgetReservationResponse.from(reservation);
                });
    }

    @Override
    public BudgetReservationResponse confirm(String reservationId, BudgetReservationActionRequest request) {
        return runIdempotent(request.requestId(), Operation.BUDGET_CONFIRM, reservationId,
                BudgetReservationResponse.class, () -> transition(reservationId, true));
    }

    @Override
    public BudgetReservationResponse cancel(String reservationId, BudgetReservationActionRequest request) {
        return runIdempotent(request.requestId(), Operation.BUDGET_CANCEL, reservationId,
                BudgetReservationResponse.class, () -> transition(reservationId, false));
    }

    @Override
    public BudgetReservationResponse getReservation(String reservationId) {
        return txTemplate.execute(status -> {
            BudgetReservation locked = reservationRepository.lockById(reservationId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "reservation not found: " + reservationId));
            expireIfDue(locked, clock.millis());
            return BudgetReservationResponse.from(
                    reservationRepository.lockById(reservationId).orElseThrow());
        });
    }

    @Override
    public BudgetTransferPreviewResponse preview(BudgetTransferPreviewRequest request) {
        long now = clock.millis();
        NormalizedPlan plan = normalize(request.details());
        Map<String, BudgetCampaign> campaigns = loadCampaigns(plan.endpointIds());
        TransferGroup group = requireSameGroup(campaigns.values().stream().toList());

        List<CampaignLedgerResponse> ledgers = new ArrayList<>();
        for (String campaignId : plan.endpointIds()) {
            BudgetCampaign campaign = campaigns.get(campaignId);
            long confirmed = reservationRepository.countConfirmed(campaignId);
            long inflight = countUnexpiredReserved(campaignId, now);
            long transferable = campaign.budget() - inflight - confirmed;
            long outgoing = plan.outgoing().getOrDefault(campaignId, 0L);
            if (outgoing > transferable) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "transferable balance insufficient for campaign " + campaignId);
            }
            long delta = plan.delta().getOrDefault(campaignId, 0L);
            long budgetAfter = campaign.budget() + delta;
            ledgers.add(new CampaignLedgerResponse(
                    campaignId,
                    campaign.version() + 1L,
                    budgetAfter,
                    confirmed,
                    inflight,
                    budgetAfter - inflight - confirmed));
        }
        return new BudgetTransferPreviewResponse(ledgers);
    }

    @Override
    public BudgetTransferActivateResponse activate(BudgetTransferActivateRequest request) {
        NormalizedPlan plan = normalize(request.details());
        // 规范化明细最多 50 条、双方编号可达 64 字符，明文指纹可能超过列宽；先规范化再做 SHA-256
        String canonical = request.transferKey() + "|" + plan.canonicalDetails()
                + "|" + plan.canonicalVersions();
        String fingerprint = "BUDGET_TRANSFER|" + sha256(canonical);
        return runIdempotent(request.requestId(), Operation.BUDGET_TRANSFER, fingerprint,
                BudgetTransferActivateResponse.class, () -> {
                    long now = clock.millis();

                    if (transferRepository.findByTransferKey(request.transferKey()).isPresent()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "transfer key already used: " + request.transferKey());
                    }

                    // 固定加锁顺序（campaignId 升序）一次性锁定完整活动集合，不逐条转移
                    List<BudgetCampaign> lockedCampaigns =
                            campaignRepository.lockByIds(plan.endpointIds());
                    Map<String, BudgetCampaign> campaigns = new LinkedHashMap<>();
                    for (BudgetCampaign campaign : lockedCampaigns) {
                        campaigns.put(campaign.campaignId(), campaign);
                    }
                    if (campaigns.size() != plan.endpointIds().size()) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "one or more endpoint campaigns do not exist");
                    }

                    TransferGroup group = requireSameGroup(lockedCampaigns);

                    // 窗口已开始则不允许激活（左闭：now 达到起点即视为已开始）
                    if (now >= group.windowStartUtc()) {
                        throw new ApiException(HttpStatus.CONFLICT,
                                "delivery window already started, cannot activate transfer");
                    }

                    // 锁定全部端点活动的在途预占并结算过期，得到阻塞并发回执/释放下的一致在途快照
                    Map<String, Long> inflightMap = new TreeMap<>();
                    Map<String, Long> confirmedMap = new TreeMap<>();
                    for (BudgetCampaign campaign : lockedCampaigns) {
                        reservationRepository.lockReservedByCampaign(campaign.campaignId());
                        settleExpired(campaign.campaignId(), now);
                        inflightMap.put(campaign.campaignId(),
                                reservationRepository.countReserved(campaign.campaignId()));
                        confirmedMap.put(campaign.campaignId(),
                                reservationRepository.countConfirmed(campaign.campaignId()));
                    }

                    // 版本与可转余额校验
                    for (String campaignId : plan.endpointIds()) {
                        BudgetCampaign campaign = campaigns.get(campaignId);
                        long expectedVersion = plan.expectedVersions().get(campaignId);
                        if (campaign.version() != expectedVersion) {
                            throw new ApiException(HttpStatus.CONFLICT,
                                    "campaign version changed concurrently: " + campaignId
                                            + " expected=" + expectedVersion
                                            + " actual=" + campaign.version());
                        }
                        long inflight = inflightMap.get(campaignId);
                        long confirmed = confirmedMap.get(campaignId);
                        long transferable = campaign.budget() - inflight - confirmed;
                        long outgoing = plan.outgoing().getOrDefault(campaignId, 0L);
                        if (outgoing > transferable) {
                            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                    "transferable balance insufficient for campaign " + campaignId);
                        }
                    }

                    // 整批更新预算并逐活动增版（净增量为 0 的闭环参与者同样增版）
                    Map<String, Long> budgetAfterMap = new TreeMap<>();
                    for (BudgetCampaign campaign : lockedCampaigns) {
                        String campaignId = campaign.campaignId();
                        long delta = plan.delta().getOrDefault(campaignId, 0L);
                        long budgetAfter = campaign.budget() + delta;
                        if (budgetAfter < 0) {
                            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                    "post-transfer budget must be non-negative: " + campaignId);
                        }
                        boolean updated = campaignRepository.compareAndSetBudget(
                                campaignId, campaign.version(), delta, campaign.version() + 1L);
                        if (!updated) {
                            throw new ApiException(HttpStatus.CONFLICT,
                                    "campaign ledger changed concurrently: " + campaignId);
                        }
                        budgetAfterMap.put(campaignId, budgetAfter);
                    }

                    long totalBefore = lockedCampaigns.stream().mapToLong(BudgetCampaign::budget).sum();
                    long totalAfter = budgetAfterMap.values().stream().mapToLong(Long::longValue).sum();
                    if (totalBefore != totalAfter) {
                        throw new IllegalStateException("global budget must be conserved, before="
                                + totalBefore + " after=" + totalAfter);
                    }

                    // 冻结规范化明细与前后账本快照
                    List<BudgetTransferActivateResponse.FrozenDetail> frozenDetails = plan.transfers().stream()
                            .map(t -> new BudgetTransferActivateResponse.FrozenDetail(
                                    t.sourceCampaignId(), t.targetCampaignId(), t.amount()))
                            .toList();
                    String detailsJson = transferRepository.writeDetails(frozenDetails);
                    transferRepository.insert(new TransferRecord(
                            request.transferKey(),
                            request.requestId(),
                            group.tenantId(),
                            group.windowStartUtc(),
                            group.windowEndUtc(),
                            group.audienceRule(),
                            detailsJson,
                            "ACTIVATED",
                            now), now);

                    List<CampaignLedgerResponse> ledgers = new ArrayList<>();
                    for (BudgetCampaign campaign : lockedCampaigns) {
                        String campaignId = campaign.campaignId();
                        long budgetAfter = budgetAfterMap.get(campaignId);
                        long inflight = inflightMap.get(campaignId);
                        long confirmed = confirmedMap.get(campaignId);
                        snapshotRepository.insert(request.transferKey(), new Snapshot(
                                campaignId,
                                campaign.version(),
                                campaign.version() + 1L,
                                campaign.budget(),
                                budgetAfter,
                                confirmed,
                                inflight));
                        ledgers.add(new CampaignLedgerResponse(
                                campaignId,
                                campaign.version() + 1L,
                                budgetAfter,
                                confirmed,
                                inflight,
                                budgetAfter - inflight - confirmed));
                    }

                    return new BudgetTransferActivateResponse(
                            request.transferKey(), request.requestId(), frozenDetails, ledgers);
                });
    }

    @Override
    public TransferEvidenceResponse evidence(String transferKey) {
        return txTemplate.execute(status -> {
            TransferRecord record = transferRepository.findByTransferKey(transferKey)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "transfer not found: " + transferKey));
            List<Snapshot> snapshots = snapshotRepository.findByTransferKey(transferKey);
            return new TransferEvidenceResponse(
                    record.transferKey(),
                    record.requestId(),
                    record.tenantId(),
                    record.status(),
                    record.activatedAtUtc(),
                    transferRepository.parseDetails(record.detailsJson()),
                    snapshots);
        });
    }

    // ---- 内部辅助（作用域末尾） ----

    /** 幂等操作类型，同时标识存储响应的反序列化类型。 */
    private enum Operation {
        BUDGET_CREATE_CAMPAIGN,
        BUDGET_APPLY,
        BUDGET_CONFIRM,
        BUDGET_CANCEL,
        BUDGET_TRANSFER
    }

    /** 端点活动的同组身份：同租户、同窗口、同受众规则。 */
    private record TransferGroup(String tenantId, long windowStartUtc, long windowEndUtc,
                                 String audienceRule) {
    }

    /**
     * 规范化后的转移计划。
     *
     * @param transfers         按（源,目标）升序的去重求和明细
     * @param endpointIds       全部端点活动编号，升序
     * @param outgoing          每活动总转出量
     * @param delta             每活动净预算增量（转入 - 转出）
     * @param expectedVersions  每活动期望版本
     * @param canonicalDetails  明细的规范化指纹串（换序等价）
     * @param canonicalVersions 版本的规范化指纹串
     */
    private record NormalizedPlan(
            List<NormalizedTransfer> transfers,
            List<String> endpointIds,
            Map<String, Long> outgoing,
            Map<String, Long> delta,
            Map<String, Long> expectedVersions,
            String canonicalDetails,
            String canonicalVersions
    ) {
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
                    Optional<IdempotencyRecord> existing = idempotencyRepository.lockById(requestId);
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
     * 规范化转移明细：按（源, 目标）求和；校验无自转、各活动期望版本一致；
     * 产出稳定排序的明细、端点集合、转出量、净增量与幂等指纹串。
     */
    private NormalizedPlan normalize(List<BudgetTransferItem> items) {
        record Pair(String source, String target) {
        }
        Map<Pair, Long> aggregated = new TreeMap<>(
                Comparator.comparing(Pair::source).thenComparing(Pair::target));
        Map<String, Long> expectedVersions = new TreeMap<>();

        for (BudgetTransferItem item : items) {
            if (item.sourceCampaignId().equals(item.targetCampaignId())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "self transfer is not allowed: " + item.sourceCampaignId());
            }
            Pair pair = new Pair(item.sourceCampaignId(), item.targetCampaignId());
            aggregated.merge(pair, item.amount(), Long::sum);
            requireConsistentVersion(expectedVersions,
                    item.sourceCampaignId(), item.sourceExpectedVersion());
            requireConsistentVersion(expectedVersions,
                    item.targetCampaignId(), item.targetExpectedVersion());
        }

        List<NormalizedTransfer> transfers = new ArrayList<>();
        Map<String, Long> outgoing = new TreeMap<>();
        Map<String, Long> delta = new TreeMap<>();
        for (Map.Entry<Pair, Long> entry : aggregated.entrySet()) {
            Pair pair = entry.getKey();
            long amount = entry.getValue();
            transfers.add(new NormalizedTransfer(pair.source(), pair.target(), amount));
            outgoing.merge(pair.source(), amount, Long::sum);
            delta.merge(pair.source(), -amount, Long::sum);
            delta.merge(pair.target(), amount, Long::sum);
        }

        Set<String> endpointSet = expectedVersions.keySet();
        List<String> endpointIds = new ArrayList<>(endpointSet);

        String canonicalDetails = transfers.stream()
                .map(t -> t.sourceCampaignId() + ">" + t.targetCampaignId() + ":" + t.amount())
                .collect(Collectors.joining(","));
        String canonicalVersions = expectedVersions.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue())
                .collect(Collectors.joining(","));

        return new NormalizedPlan(transfers, endpointIds, outgoing, delta, expectedVersions,
                canonicalDetails, canonicalVersions);
    }

    /** 同一活动在不同明细中给出的期望版本必须一致，否则 422。 */
    private void requireConsistentVersion(Map<String, Long> expectedVersions,
                                          String campaignId, long version) {
        Long previous = expectedVersions.get(campaignId);
        if (previous != null && previous != version) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "inconsistent expected version for campaign " + campaignId
                            + ": " + previous + " vs " + version);
        }
        expectedVersions.put(campaignId, version);
    }

    /** 非加锁加载全部端点活动；缺失返回 422（预览），激活另以加锁集合复核。 */
    private Map<String, BudgetCampaign> loadCampaigns(List<String> endpointIds) {
        Map<String, BudgetCampaign> campaigns = new LinkedHashMap<>();
        for (String campaignId : endpointIds) {
            BudgetCampaign campaign = campaignRepository.findById(campaignId)
                    .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "endpoint campaign not found: " + campaignId));
            campaigns.put(campaignId, campaign);
        }
        return campaigns;
    }

    /** 校验端点活动必须同租户、同窗口、同受众规则。 */
    private TransferGroup requireSameGroup(List<BudgetCampaign> campaigns) {
        BudgetCampaign first = campaigns.get(0);
        for (BudgetCampaign campaign : campaigns) {
            if (!campaign.tenantId().equals(first.tenantId())
                    || campaign.windowStartUtc() != first.windowStartUtc()
                    || campaign.windowEndUtc() != first.windowEndUtc()
                    || !campaign.audienceRule().equals(first.audienceRule())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "transfers require same tenant, window and audience rule; mismatch at "
                                + campaign.campaignId());
            }
        }
        return new TransferGroup(first.tenantId(), first.windowStartUtc(),
                first.windowEndUtc(), first.audienceRule());
    }

    /**
     * 确认/取消状态机。调用前已持幂等键；预占归属不变，行锁 + CAS 保证并发只有一个终态。
     *
     * @param isConfirm true=确认，false=取消
     */
    private BudgetReservationResponse transition(String reservationId, boolean isConfirm) {
        long now = clock.millis();
        BudgetReservation current = reservationRepository.lockById(reservationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "reservation not found: " + reservationId));

        expireIfDue(current, now);
        current = reservationRepository.lockById(reservationId).orElseThrow();

        if (current.status() == ReservationStatus.RESERVED) {
            ReservationStatus target = isConfirm
                    ? ReservationStatus.CONFIRMED
                    : ReservationStatus.CANCELLED;
            if (!reservationRepository.compareAndSetStatus(reservationId,
                    ReservationStatus.RESERVED, target, now)) {
                throw new ApiException(HttpStatus.CONFLICT, "reservation state changed concurrently");
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
            // 重复同类终态操作：返回原状态，不重复变更
        } else {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "unexpected reservation status: " + current.status());
        }

        return BudgetReservationResponse.from(
                reservationRepository.lockById(reservationId).orElseThrow());
    }

    /**
     * 结算某活动当前已到期（now &gt;= expiresAt）但仍为 RESERVED 的预占：
     * 行锁查出后逐个 CAS 为 EXPIRED。在途/已确认数量直接由预占状态派生，无需额外账目。
     */
    private void settleExpired(String campaignId, long now) {
        List<BudgetReservation> expired = reservationRepository.lockExpiredReserved(campaignId, now);
        for (BudgetReservation reservation : expired) {
            expireIfDue(reservation, now);
        }
    }

    /**
     * 若传入预占单（调用方已持其行锁）已到期，则 CAS 转 EXPIRED；
     * 未到期或已非 RESERVED 则不做任何变更。
     */
    private void expireIfDue(BudgetReservation reservation, long now) {
        if (reservation.status() == ReservationStatus.RESERVED
                && now >= reservation.expiresAtUtc()) {
            reservationRepository.compareAndSetStatus(
                    reservation.reservationId(),
                    ReservationStatus.RESERVED,
                    ReservationStatus.EXPIRED,
                    now);
        }
    }

    /** 预览专用：不写库，直接统计尚未到期（expiresAt &gt; now）的在途预占数。 */
    private long countUnexpiredReserved(String campaignId, long now) {
        return reservationRepository.countUnexpiredReserved(campaignId, now);
    }

    private BudgetCampaign requireCampaign(String campaignId) {
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

    /** 计算规范化串的 SHA-256 十六进制摘要，避免长指纹超过幂等列宽。 */
    private static String sha256(String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
