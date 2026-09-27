package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.CorrectionRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.EvaporationRateRow;
import com.example.starter.water.WaterRepository.SettlementRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.EvaporationRateResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.RateDiagnostic;
import com.example.starter.water.dto.Dtos.RateDiagnosticResponse;
import com.example.starter.water.dto.Dtos.SettlementBatchResponse;
import com.example.starter.water.dto.Dtos.SettlementItemRequest;
import com.example.starter.water.dto.Dtos.SettlementListResponse;
import com.example.starter.water.dto.Dtos.SettlementResponse;
import com.example.starter.water.dto.Dtos.SettlementResultResponse;
import com.example.starter.water.dto.Dtos.SettlementReversalResponse;
import com.example.starter.water.dto.Dtos.TransferListResponse;
import com.example.starter.water.dto.Dtos.TransferResponse;
import com.example.starter.water.dto.Dtos.UpstreamBalance;
import com.example.starter.water.dto.Dtos.WindowResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 灌区配水业务服务。
 *
 * <p>幂等：每个写命令携带 commandKey，事务内先占位插入命令行再执行业务并写回响应；
 * 同键同参重放返回首次结果，同键改参返回 409。</p>
 *
 * <p>并发：批准申请与创建/取消限供在同一事务内先对窗口行 SELECT ... FOR UPDATE，
 * 按事务提交顺序生效，保证已批准总量永不超过最终可用总量。</p>
 */
@Service
public class WaterService {

    static final String STATUS_REQUESTED = "REQUESTED";
    static final String STATUS_APPROVED = "APPROVED";
    static final String STATUS_CANCELLED = "CANCELLED";
    static final String STATUS_SETTLED = "SETTLED";

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("\\d{1,16}(\\.\\d{1,3})?");
    private static final Pattern RATE_PATTERN = Pattern.compile("0(\\.\\d{1,4})?");
    private static final Pattern KEY_PATTERN = Pattern.compile("[\\w.\\-:]{1,128}");
    private static final BigDecimal MAX_RATE = new BigDecimal("0.2000");
    private static final int SETTLEMENT_SCALE = 2;

    private final WaterRepository repository;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public WaterService(WaterRepository repository, PlatformTransactionManager transactionManager,
                        ObjectMapper objectMapper) {
        this(repository, transactionManager, objectMapper, Clock.systemUTC());
    }

    public WaterService(WaterRepository repository, PlatformTransactionManager transactionManager,
                        ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // 命令入口
    // ------------------------------------------------------------------

    /** 创建供水窗口。 */
    public WindowResponse createWindow(String commandKey, String windowKey, String channelId,
                                       String startUtc, String endUtc, String plannedVolume) {
        requireKey("commandKey", commandKey);
        requireKey("windowKey", windowKey);
        requireKey("channelId", channelId);
        long startNanos = parseInstant("startUtc", startUtc);
        long endNanos = parseInstant("endUtc", endUtc);
        if (startNanos >= endNanos) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "startUtc 必须早于 endUtc");
        }
        BigDecimal planned = parseAmount("plannedVolume", plannedVolume);
        String params = "WINDOW_CREATE|" + windowKey + "|" + channelId + "|" + startNanos + "|" + endNanos
                + "|" + planned.toPlainString();
        return runCommand("WINDOW_CREATE", commandKey, params, WindowResponse.class, () -> {
            if (repository.existsOverlappingWindow(channelId, startNanos, endNanos)) {
                throw ApiException.conflict("WINDOW_OVERLAP", "同一渠道存在时间重叠的供水窗口");
            }
            long id = repository.insertWindow(windowKey, channelId, startNanos, endNanos, planned, nowNanos());
            WindowRow row = repository.findWindowById(id);
            return toWindowResponse(row, null);
        });
    }

    /** 提交配水申请，申请人为 actor。 */
    public AllocationResponse submitAllocation(String commandKey, String allocationKey, Long windowId,
                                               String userId, String amount, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        requireKey("userId", userId);
        requireKey("X-Actor-Id", actor);
        if (windowId == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "windowId 不能为空");
        }
        BigDecimal qty = parseAmount("amount", amount);
        String params = "ALLOCATION_SUBMIT|" + allocationKey + "|" + windowId + "|" + userId + "|"
                + qty.toPlainString() + "|" + actor;
        return runCommand("ALLOCATION_SUBMIT", commandKey, params, AllocationResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            long id = repository.insertAllocation(allocationKey, windowId, userId, qty, actor, nowNanos());
            return toAllocationResponse(repository.findAllocationByKey(allocationKey));
        });
    }

    /** 批准申请：加入本申请后已批准总量不得超过当前可用总量，否则 422。 */
    public AllocationResponse approveAllocation(String commandKey, String allocationKey) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        String params = "ALLOCATION_APPROVE|" + allocationKey;
        return runCommand("ALLOCATION_APPROVE", commandKey, params, AllocationResponse.class, () -> {
            AllocationRow allocation = repository.findAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            WindowRow window = lockWindowOf(allocation);
            // 窗口锁内重读申请，避免与转让/取消并发时使用过期状态与持有额度
            allocation = repository.lockAllocationByKey(allocationKey);
            switch (allocation.status()) {
                case STATUS_CANCELLED ->
                        throw ApiException.conflict("ALLOCATION_CANCELLED", "已取消的申请不能批准");
                case STATUS_APPROVED ->
                        throw ApiException.conflict("ALLOCATION_ALREADY_APPROVED", "申请已批准，不能重复批准");
                default -> {
                }
            }
            BigDecimal available = availableTotal(window);
            BigDecimal approved = repository.sumApprovedAmount(window.id());
            if (approved.add(allocation.amount()).compareTo(available) > 0) {
                throw ApiException.quotaExceeded("批准后将超过当前可用总量 " + fmt(available));
            }
            repository.updateAllocationStatus(allocation.id(), STATUS_APPROVED, nowNanos());
            return toAllocationResponse(repository.findAllocationByKey(allocationKey));
        });
    }

    /** 取消申请：仅申请人本人可取消 REQUESTED/APPROVED，取消不可恢复并立即释放水量。 */
    public AllocationResponse cancelAllocation(String commandKey, String allocationKey, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        requireKey("X-Actor-Id", actor);
        String params = "ALLOCATION_CANCEL|" + allocationKey + "|" + actor;
        return runCommand("ALLOCATION_CANCEL", commandKey, params, AllocationResponse.class, () -> {
            AllocationRow allocation = repository.findAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            lockWindowOf(allocation);
            // 窗口锁内重读申请，避免与转让/并发取消使用过期状态
            allocation = repository.lockAllocationByKey(allocationKey);
            if (!allocation.requester().equals(actor)) {
                throw ApiException.conflict("NOT_OWNER", "只有申请人本人可以取消该申请");
            }
            if (STATUS_CANCELLED.equals(allocation.status())) {
                throw ApiException.conflict("ALLOCATION_ALREADY_CANCELLED", "申请已取消，不能重复取消");
            }
            repository.updateAllocationStatus(allocation.id(), STATUS_CANCELLED, nowNanos());
            return toAllocationResponse(repository.findAllocationByKey(allocationKey));
        });
    }

    /**
     * 同窗口额度原子转让：源申请人（actor）把自己 APPROVED 申请的额度转给同窗口、不同用水户的
     * REQUESTED 目标申请，转让额等于目标原申请水量的全部额度。
     * 源扣减、目标批准、不可变流水在同一事务完成，窗口已批准总量不变；失败回滚无任何额度变化。
     */
    public TransferResponse transferAllocation(String commandKey, String transferKey, String sourceAllocationKey,
                                               String targetAllocationKey, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("transferKey", transferKey);
        requireKey("sourceAllocationKey", sourceAllocationKey);
        requireKey("targetAllocationKey", targetAllocationKey);
        requireKey("X-Actor-Id", actor);
        String params = "TRANSFER|" + transferKey + "|" + sourceAllocationKey + "|" + targetAllocationKey + "|"
                + actor;
        return runCommand("TRANSFER", commandKey, params, TransferResponse.class, () -> {
            if (repository.findTransferByKey(transferKey) != null) {
                throw ApiException.conflict("TRANSFER_KEY_REUSED", "transferKey 已被使用: " + transferKey);
            }
            AllocationRow source = repository.findAllocationByKey(sourceAllocationKey);
            if (source == null) {
                throw ApiException.notFound("SOURCE_ALLOCATION_NOT_FOUND",
                        "转出申请不存在: " + sourceAllocationKey);
            }
            AllocationRow target = repository.findAllocationByKey(targetAllocationKey);
            if (target == null) {
                throw ApiException.notFound("TARGET_ALLOCATION_NOT_FOUND",
                        "转入申请不存在: " + targetAllocationKey);
            }
            // 先锁窗口行，与普通批准、取消、限供调整按事务提交顺序串行裁决
            lockWindowOf(source);
            // 窗口锁内再锁定源/目标申请行，得到最新状态与持有额度
            AllocationRow lockedSource = repository.lockAllocationByKey(sourceAllocationKey);
            AllocationRow lockedTarget = repository.lockAllocationByKey(targetAllocationKey);
            if (!lockedSource.requester().equals(actor)) {
                throw ApiException.conflict("NOT_OWNER", "只有转出申请人本人可以发起转让");
            }
            if (!STATUS_APPROVED.equals(lockedSource.status())) {
                throw ApiException.conflict("SOURCE_NOT_APPROVED", "转出申请必须为 APPROVED 状态");
            }
            if (!STATUS_REQUESTED.equals(lockedTarget.status())) {
                throw ApiException.conflict("TARGET_NOT_REQUESTED", "转入申请必须为 REQUESTED 状态");
            }
            if (lockedSource.windowId() != lockedTarget.windowId()) {
                throw ApiException.conflict("DIFFERENT_WINDOW", "转让双方必须属于同一供水窗口");
            }
            if (lockedSource.userId().equals(lockedTarget.userId())) {
                throw ApiException.conflict("SAME_USER", "转让双方必须属于不同用水户");
            }
            BigDecimal amount = lockedTarget.amount();
            if (lockedSource.heldAmount().compareTo(amount) < 0) {
                throw ApiException.quotaExceeded("源申请当前持有额度 " + fmt(lockedSource.heldAmount())
                        + " 不足，无法转让 " + fmt(amount));
            }
            long now = nowNanos();
            repository.decrementHeldAmount(lockedSource.id(), amount, now);
            repository.updateAllocationStatus(lockedTarget.id(), STATUS_APPROVED, now);
            try {
                repository.insertTransfer(transferKey, lockedSource.windowId(), sourceAllocationKey,
                        targetAllocationKey, amount, actor, now);
            } catch (DuplicateKeyException e) {
                // 并发复用同一 transferKey（换 commandKey）：事务回滚，额度无变化
                throw ApiException.conflict("TRANSFER_KEY_REUSED", "transferKey 已被使用: " + transferKey);
            }
            return toTransferResponse(repository.findTransferByKey(transferKey));
        });
    }

    /** 查询窗口全部转让流水（不可变），按发生顺序返回。 */
    public TransferListResponse getTransfers(long windowId) {
        WindowRow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        List<TransferResponse> transfers = repository.listTransfers(windowId).stream()
                .map(this::toTransferResponse).toList();
        return new TransferListResponse(windowId, transfers);
    }

    /** 创建限供：仅当当前已批准总量不超过拟定限供水量时允许。 */
    public CurtailmentResponse createCurtailment(String commandKey, long windowId, String volume) {
        requireKey("commandKey", commandKey);
        BigDecimal qty = parseAmount("volume", volume);
        String params = "CURTAILMENT_CREATE|" + windowId + "|" + qty.toPlainString();
        return runCommand("CURTAILMENT_CREATE", commandKey, params, CurtailmentResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            if (qty.compareTo(window.plannedVolume()) > 0) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "限供水量不能超过计划水量 " + fmt(window.plannedVolume()));
            }
            if (repository.findActiveCurtailment(windowId) != null) {
                throw ApiException.conflict("CURTAILMENT_EXISTS", "窗口已存在生效中的限供");
            }
            BigDecimal approved = repository.sumApprovedAmount(windowId);
            if (approved.compareTo(qty) > 0) {
                throw ApiException.conflict("CURTAILMENT_BELOW_APPROVED",
                        "当前已批准总量 " + fmt(approved) + " 超过拟定限供水量 " + fmt(qty));
            }
            long id = repository.insertCurtailment(windowId, qty, nowNanos());
            return toCurtailmentResponse(repository.findActiveCurtailment(windowId));
        });
    }

    /** 取消当前生效限供，恢复计划水量。 */
    public CurtailmentResponse cancelCurtailment(String commandKey, long windowId) {
        requireKey("commandKey", commandKey);
        String params = "CURTAILMENT_CANCEL|" + windowId;
        return runCommand("CURTAILMENT_CANCEL", commandKey, params, CurtailmentResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            CurtailmentRow active = repository.findActiveCurtailment(windowId);
            if (active == null) {
                throw ApiException.conflict("NO_ACTIVE_CURTAILMENT", "窗口没有生效中的限供");
            }
            repository.cancelCurtailment(active.id(), nowNanos());
            List<CurtailmentRow> all = repository.listCurtailments(windowId);
            return all.stream().filter(c -> c.id() == active.id()).findFirst()
                    .map(this::toCurtailmentResponse).orElseThrow();
        });
    }

    /** 查询窗口当前可用容量。 */
    public CapacityResponse getCapacity(long windowId) {
        WindowRow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        CurtailmentRow active = repository.findActiveCurtailment(windowId);
        BigDecimal available = active != null ? active.volume() : window.plannedVolume();
        BigDecimal approved = repository.sumApprovedAmount(windowId);
        return new CapacityResponse(window.id(), fmt(window.plannedVolume()),
                active != null ? fmt(active.volume()) : null, fmt(available), fmt(approved),
                fmt(available.subtract(approved)));
    }

    /** 查询窗口历史明细：窗口 + 全部申请 + 全部限供。 */
    public HistoryResponse getHistory(long windowId) {
        WindowRow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        CurtailmentRow active = repository.findActiveCurtailment(windowId);
        List<AllocationResponse> allocations = repository.listAllocations(windowId).stream()
                .map(this::toAllocationResponse).toList();
        List<CurtailmentResponse> curtailments = repository.listCurtailments(windowId).stream()
                .map(this::toCurtailmentResponse).toList();
        return new HistoryResponse(toWindowResponse(window, active), allocations, curtailments);
    }

    // ------------------------------------------------------------------
    // 渠道日蒸发率
    // ------------------------------------------------------------------

    /**
     * 登记或修改渠道 UTC 自然日蒸发率。首次登记版本为 1；该日首次核销前修改版本加一，
     * 当日已存在核销记录（已冻结）后修改返回 409。
     */
    public EvaporationRateResponse putEvaporationRate(String commandKey, String channelId, String dayUtc,
                                                      String rateText) {
        requireKey("commandKey", commandKey);
        requireKey("channelId", channelId);
        long dayStart = parseDay("dayUtc", dayUtc);
        BigDecimal rate = parseRate(rateText);
        String params = "RATE_PUT|" + channelId + "|" + dayStart + "|" + rate.toPlainString();
        return runCommand("RATE_PUT", commandKey, params, EvaporationRateResponse.class, () -> {
            long now = nowNanos();
            EvaporationRateRow existing = repository.lockRate(channelId, dayStart);
            if (existing != null && repository.firstSettlementNanos(channelId, dayStart) != null) {
                throw ApiException.conflict("RATE_FROZEN",
                        "渠道 " + channelId + " 在 " + dayUtc + " 已有核销，日蒸发率冻结，不能修改");
            }
            if (existing == null) {
                repository.insertRate(channelId, dayStart, rate, now);
            } else {
                repository.updateRate(existing.id(), rate, now);
            }
            EvaporationRateRow saved = repository.findRate(channelId, dayStart);
            return toRateResponse(saved, repository.firstSettlementNanos(channelId, dayStart));
        });
    }

    /** 查询渠道全部日蒸发率诊断，按 UTC 日升序，读取不改变冻结状态。 */
    public RateDiagnosticResponse getRateDiagnostic(String channelId) {
        requireKey("channelId", channelId);
        List<RateDiagnostic> rates = repository.listRates(channelId).stream()
                .map(row -> new RateDiagnostic(row.channelId(), toDayUtc(row.dayStartNanos()),
                        rateText(row.rate()), row.version(),
                        firstSettlementIso(row.channelId(), row.dayStartNanos()),
                        repository.firstSettlementNanos(row.channelId(), row.dayStartNanos()) != null))
                .toList();
        return new RateDiagnosticResponse(channelId, rates);
    }

    // ------------------------------------------------------------------
    // 送达核销
    // ------------------------------------------------------------------

    /** 单笔送达核销：上游扣减、送达、损耗同事务写入，任一前置不满足整体拒绝。 */
    public SettlementResultResponse settleDelivery(String commandKey, String settlementKey, Long windowId,
                                                   String allocationKey, String occurredUtc,
                                                   String deliveredText, String downstreamUserId,
                                                   String dayUtc, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("settlementKey", settlementKey);
        requireKey("allocationKey", allocationKey);
        requireKey("X-Actor-Id", actor);
        if (windowId == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "windowId 不能为空");
        }
        SettlementDraft draft = buildDraft(settlementKey, allocationKey, occurredUtc, deliveredText,
                downstreamUserId, dayUtc);
        String params = "SETTLEMENT_CREATE|" + settlementKey + "|" + windowId + "|" + allocationKey + "|"
                + draft.occurredNanos() + "|" + draft.delivered().toPlainString() + "|"
                + draft.dayStartNanos() + "|" + nvl(downstreamUserId) + "|" + actor;
        return runCommand("SETTLEMENT_CREATE", commandKey, params, SettlementResultResponse.class,
                () -> settleInternal(windowId, null, List.of(draft), actor).single());
    }

    /** 批量送达核销：按完整最终余额与逐笔舍入口径预校验，任一失败整批回滚。 */
    public SettlementBatchResponse settleBatch(String commandKey, String batchKey, Long windowId,
                                               List<SettlementItemRequest> items, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("batchKey", batchKey);
        requireKey("X-Actor-Id", actor);
        if (windowId == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "windowId 不能为空");
        }
        if (items == null || items.isEmpty()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "items 至少包含一笔核销明细");
        }
        List<SettlementDraft> drafts = new ArrayList<>();
        java.util.Set<String> seenKeys = new java.util.HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            SettlementItemRequest item = items.get(i);
            if (item == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "items[" + i + "] 不能为空");
            }
            requireKeyWithIndex("settlementKey", item.settlementKey(), i);
            requireKeyWithIndex("allocationKey", item.allocationKey(), i);            if (!seenKeys.add(item.settlementKey())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "SETTLEMENT_KEY_DUPLICATED",
                        "批量明细中 settlementKey 重复: " + item.settlementKey(), null, null, null, i);
            }
            drafts.add(buildDraft(item.settlementKey(), item.allocationKey(), item.occurredUtc(),
                    item.deliveredVolume(), item.downstreamUserId(), item.dayUtc(), i));
        }
        StringBuilder digest = new StringBuilder("SETTLEMENT_BATCH|").append(batchKey).append("|")
                .append(windowId).append("|").append(actor).append("|").append(items.size());
        for (SettlementDraft draft : drafts) {
            digest.append('|').append(draft.settlementKey()).append('@').append(draft.allocationKey())
                    .append('@').append(draft.occurredNanos()).append('@')
                    .append(draft.delivered().toPlainString()).append('@').append(draft.dayStartNanos())
                    .append('@').append(nvl(draft.downstreamUserId()));
        }
        return runCommand("SETTLEMENT_BATCH", commandKey, digest.toString(), SettlementBatchResponse.class,
                () -> {
                    SettlementOutcome outcome = settleInternal(windowId, batchKey, drafts, actor);
                    return outcome.batch(batchKey, windowId);
                });
    }

    /** 反向更正：只追加更正记录并回补上游持有额度，原核销证据不改写。 */
    public SettlementReversalResponse reverseSettlement(String commandKey, String correctionKey,
                                                         String settlementKey, String reason, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("correctionKey", correctionKey);
        requireKey("settlementKey", settlementKey);
        requireKey("X-Actor-Id", actor);
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "reason 不能为空");
        }
        String trimmedReason = reason.trim();
        if (trimmedReason.length() > 512) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "reason 最长 512 字符");
        }
        String params = "SETTLEMENT_REVERSAL|" + correctionKey + "|" + settlementKey + "|"
                + trimmedReason + "|" + actor;
        return runCommand("SETTLEMENT_REVERSAL", commandKey, params, SettlementReversalResponse.class, () -> {
            SettlementRow original = repository.findSettlementByKey(settlementKey);
            if (original == null) {
                throw ApiException.notFound("SETTLEMENT_NOT_FOUND", "核销记录不存在: " + settlementKey);
            }
            // 先锁窗口，与核销/转让按统一顺序串行
            lockWindowById(original.windowId());
            SettlementRow lockedOriginal = repository.lockSettlementById(original.id());
            if (repository.findCorrectionByOriginalSettlement(lockedOriginal.id()) != null) {
                throw ApiException.conflict("CORRECTION_EXISTS",
                        "核销记录已反向更正，不能重复更正: " + settlementKey);
            }
            if (repository.findCorrectionByKey(correctionKey) != null) {
                throw ApiException.conflict("CORRECTION_KEY_REUSED",
                        "correctionKey 已被使用: " + correctionKey);
            }
            AllocationRow upstream = repository.lockAllocationByKey(lockedOriginal.allocationKey());
            if (upstream == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND",
                        "上游申请不存在: " + lockedOriginal.allocationKey());
            }
            if (!STATUS_APPROVED.equals(upstream.status())) {
                throw ApiException.conflict("UPSTREAM_NOT_APPROVED",
                        "上游申请当前状态为 " + upstream.status() + "，不能回补额度: " + upstream.allocationKey());
            }
            BigDecimal restored = lockedOriginal.upstreamDeduction();
            BigDecimal afterRestore = upstream.heldAmount().add(restored);
            if (afterRestore.compareTo(upstream.amount()) > 0) {
                throw ApiException.unprocessable("RESTORE_EXCEEDS_ORIGINAL",
                        "回补后持有额度 " + fmt(afterRestore) + " 将超过原申请水量 " + fmt(upstream.amount()),
                        fmt(afterRestore), fmt(upstream.amount()),
                        fmt(afterRestore.subtract(upstream.amount())), null);
            }
            long now = nowNanos();
            try {
                repository.insertCorrection(correctionKey, lockedOriginal.id(), lockedOriginal.batchKey(),
                        lockedOriginal.windowId(), lockedOriginal.channelId(), lockedOriginal.allocationKey(),
                        lockedOriginal.downstreamUserId(), lockedOriginal.dayStartNanos(),
                        lockedOriginal.deliveredVolume(), lockedOriginal.settledDeliveredVolume(),
                        lockedOriginal.lossVolume(), lockedOriginal.upstreamDeduction(),
                        lockedOriginal.rateValue(), lockedOriginal.rateVersion(), trimmedReason, actor, now);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("CORRECTION_KEY_REUSED",
                        "correctionKey 已被使用: " + correctionKey);
            }
            repository.incrementHeldAmount(upstream.id(), restored, now);
            return toReversalResponse(repository.findCorrectionByKey(correctionKey), lockedOriginal);
        });
    }

    /** 查询窗口全部核销与更正明细，按发生顺序返回；读取不改变状态。 */
    public SettlementListResponse getWindowSettlements(long windowId) {
        WindowRow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        return buildSettlementList(windowId, repository.listSettlementsByWindow(windowId));
    }

    /** 查询某上游申请的全部核销与更正明细。 */
    public SettlementListResponse getAllocationSettlements(String allocationKey) {
        requireKey("allocationKey", allocationKey);
        AllocationRow allocation = repository.findAllocationByKey(allocationKey);
        if (allocation == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
        }
        return buildSettlementList(allocation.windowId(), repository.listSettlementsByAllocation(allocationKey));
    }

    /** 核销事务主体：锁窗口→锁日率→按主键顺序锁上游，先预校验完整最终余额再统一写入。 */
    private SettlementOutcome settleInternal(long windowId, String batchKey, List<SettlementDraft> drafts,
                                             String actor) {
        WindowRow window = lockWindowById(windowId);
        String channelId = window.channelId();

        // 先非锁定预读上游申请，校验存在性与窗口归属，避免跨窗口非法引用参与行锁形成死锁
        List<String> allocationKeys = drafts.stream().map(SettlementDraft::allocationKey).distinct().sorted()
                .toList();
        for (String key : allocationKeys) {
            AllocationRow preview = repository.findAllocationByKey(key);
            if (preview == null) {
                SettlementDraft hit = drafts.stream().filter(d -> d.allocationKey().equals(key)).findFirst()
                        .orElseThrow();
                throw notFoundWithIndex("ALLOCATION_NOT_FOUND", "上游配水申请不存在: " + key, hit.itemIndex());
            }
            if (preview.windowId() != windowId) {
                SettlementDraft hit = drafts.stream().filter(d -> d.allocationKey().equals(key)).findFirst()
                        .orElseThrow();
                throw conflictWithIndex("DIFFERENT_WINDOW",
                        "上游申请 " + key + " 不属于窗口 " + windowId, hit.itemIndex());
            }
        }

        // 按 UTC 日有序锁定本批涉及的日蒸发率，缺失率在此拒绝
        List<Long> days = drafts.stream().map(SettlementDraft::dayStartNanos).distinct().sorted().toList();
        Map<Long, EvaporationRateRow> rates = new LinkedHashMap<>();
        for (Long day : days) {
            EvaporationRateRow rate = repository.lockRate(channelId, day);
            if (rate == null) {
                SettlementDraft hit = drafts.stream().filter(d -> d.dayStartNanos() == day).findFirst()
                        .orElseThrow();
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EVAPORATION_RATE_MISSING",
                        "渠道 " + channelId + " 在 UTC 日 " + toDayUtc(day) + " 未登记蒸发率，不能核销",
                        null, null, null, hit.itemIndex());
            }
            rates.put(day, rate);
        }

        // 按业务键顺序锁定本批全部上游申请（已确认同属本窗口），与转让等多对象事务保持一致加锁顺序
        Map<String, AllocationRow> allocations = new LinkedHashMap<>();
        for (String key : allocationKeys) {
            allocations.put(key, repository.lockAllocationByKey(key));
        }

        // 逐笔校验状态、配水窗口与逐笔舍入守恒；按提交顺序累计上游扣减量，得到完整最终余额
        Map<String, BigDecimal> deducted = new LinkedHashMap<>();
        List<SettlementComputation> computations = new ArrayList<>();
        for (SettlementDraft draft : drafts) {
            AllocationRow upstream = allocations.get(draft.allocationKey());
            if (!STATUS_APPROVED.equals(upstream.status())) {
                throw conflictWithIndex("UPSTREAM_NOT_APPROVED",
                        "上游申请 " + draft.allocationKey() + " 当前状态为 " + upstream.status()
                                + "，必须为 APPROVED 才能核销", draft.itemIndex());
            }
            if (draft.occurredNanos() < window.startNanos() || draft.occurredNanos() >= window.endNanos()) {
                throw ApiException.unprocessable("SETTLEMENT_OUTSIDE_WINDOW",
                        "送达时刻不在配水窗口 [" + toIso(window.startNanos()) + ", "
                                + toIso(window.endNanos()) + ") 内",
                        draft.occurredUtc(), toIso(window.startNanos()) + "/" + toIso(window.endNanos()),
                        null, draft.itemIndex());
            }
            if (repository.findSettlementByKey(draft.settlementKey()) != null) {
                throw conflictWithIndex("SETTLEMENT_KEY_REUSED",
                        "settlementKey 已被使用: " + draft.settlementKey(), draft.itemIndex());
            }
            EvaporationRateRow rate = rates.get(draft.dayStartNanos());
            SettlementMath math = computeSettlementMath(draft.delivered(), rate.rate());
            if (math.deduction().signum() <= 0) {
                throw ApiException.unprocessable("SETTLEMENT_VOLUME_TOO_SMALL",
                        "送达量 " + fmt(draft.delivered()) + " 按两位小数银行家舍入后上游扣减为 0，"
                                + "无法核销（舍入后上游扣减至少为 0.01 立方米）",
                        fmt(math.deduction()), "0.01", "0.01", draft.itemIndex());
            }
            if (math.conservationBroken()) {
                BigDecimal required = math.settledDelivered().add(math.loss());
                throw ApiException.unprocessable("ROUNDING_CONSERVATION",
                        "银行家舍入到两位小数后上游扣减 " + fmt(math.deduction()) + " 不等于计入送达 "
                                + fmt(required) + "（原始送达 " + fmt(draft.delivered()) + "，计入送达 "
                                + fmt(math.settledDelivered()) + "，损耗 " + fmt(math.loss()) + "）",
                        fmt(math.deduction()), fmt(required),
                        fmt(math.deduction().subtract(required)), draft.itemIndex());
            }
            computations.add(new SettlementComputation(draft, math, rate));
            deducted.merge(draft.allocationKey(), math.deduction(), BigDecimal::add);
        }

        // 完整最终余额校验：任一上游最终持有额度为负则整批拒绝（整批回滚，不留半成品）
        for (Map.Entry<String, BigDecimal> entry : deducted.entrySet()) {
            AllocationRow upstream = allocations.get(entry.getKey());
            BigDecimal total = entry.getValue();
            if (upstream.heldAmount().compareTo(total) < 0) {
                int itemIndex = drafts.stream()
                        .filter(d -> d.allocationKey().equals(entry.getKey()))
                        .mapToInt(SettlementDraft::itemIndexSafe).max().orElse(-1);
                Integer idx = itemIndex < 0 ? null : itemIndex;
                throw ApiException.unprocessable("UPSTREAM_QUOTA_INSUFFICIENT",
                        "上游申请 " + entry.getKey() + " 剩余持有额度 " + fmt(upstream.heldAmount())
                                + " 不足，本批累计要求扣减 " + fmt(total),
                        fmt(upstream.heldAmount()), fmt(total),
                        fmt(total.subtract(upstream.heldAmount())), idx);
            }
        }

        long now = nowNanos();
        if (batchKey != null) {
            try {
                repository.insertBatchKey(batchKey, windowId, now);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("BATCH_KEY_REUSED", "batchKey 已被使用: " + batchKey);
            }
        }
        List<SettlementResponse> responses = new ArrayList<>();
        for (SettlementComputation computation : computations) {
            SettlementDraft draft = computation.draft();
            SettlementMath math = computation.math();
            EvaporationRateRow rate = computation.rate();
            long id;
            try {
                id = repository.insertSettlement(draft.settlementKey(), batchKey, windowId, channelId,
                        draft.allocationKey(), draft.downstreamUserId(), draft.dayStartNanos(),
                        draft.occurredNanos(), draft.delivered(), math.settledDelivered(), math.loss(),
                        math.deduction(), rate.rate(), rate.version(), actor, now);
            } catch (DuplicateKeyException e) {
                throw conflictWithIndex("SETTLEMENT_KEY_REUSED",
                        "settlementKey 已被使用: " + draft.settlementKey(), draft.itemIndex());
            }
            responses.add(toSettlementResponse(repository.findSettlementByKey(draft.settlementKey()), null));
        }
        // 同一上游只做一次聚合扣减，扣减额即完整最终余额口径下的累计上游扣减
        for (Map.Entry<String, BigDecimal> entry : deducted.entrySet()) {
            repository.decrementHeldAmount(allocations.get(entry.getKey()).id(), entry.getValue(), now);
        }
        List<UpstreamBalance> balances = deducted.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    AllocationRow upstream = allocations.get(e.getKey());
                    BigDecimal remaining = upstream.heldAmount().subtract(e.getValue());
                    return new UpstreamBalance(e.getKey(), fmt(remaining), fmt(e.getValue()), fmt(e.getValue()));
                }).toList();
        return new SettlementOutcome(responses, balances);
    }

    private SettlementListResponse buildSettlementList(long windowId, List<SettlementRow> rows) {
        java.util.Set<Long> rowIds = rows.stream().map(SettlementRow::id).collect(java.util.stream.Collectors.toSet());
        // 按申请查询时只返回该申请核销对应的更正；按窗口查询时 rows 覆盖窗口全部核销
        List<CorrectionRow> corrections = repository.listCorrectionsByWindow(windowId).stream()
                .filter(c -> rowIds.contains(c.originalSettlementId())).toList();
        Map<Long, String> correctionKeyByOriginal = new LinkedHashMap<>();
        for (CorrectionRow correction : corrections) {
            correctionKeyByOriginal.put(correction.originalSettlementId(), correction.correctionKey());
        }
        List<SettlementResponse> settlements = rows.stream()
                .map(row -> toSettlementResponse(row, correctionKeyByOriginal.get(row.id()))).toList();
        Map<Long, SettlementRow> originals = new LinkedHashMap<>();
        for (SettlementRow row : rows) {
            originals.put(row.id(), row);
        }
        List<SettlementReversalResponse> reversalViews = corrections.stream()
                .map(c -> toReversalResponse(c, originals.get(c.originalSettlementId()))).toList();
        return new SettlementListResponse(windowId, settlements, reversalViews);
    }

    // ------------------------------------------------------------------
    // 蒸发结算口径与解析
    // ------------------------------------------------------------------

    /**
     * 蒸发结算舍入口径（均银行家舍入到两位小数）：计入送达 = round2(送达量)，
     * 损耗 = round2(送达量 × 率)，上游扣减 = round2(送达量 × (1 + 率))；
     * 三者独立舍入后再校验 上游扣减 = 计入送达 + 损耗。
     */
    static SettlementMath computeSettlementMath(BigDecimal delivered, BigDecimal rate) {
        BigDecimal settledDelivered = delivered.setScale(SETTLEMENT_SCALE, RoundingMode.HALF_EVEN);
        BigDecimal loss = delivered.multiply(rate).setScale(SETTLEMENT_SCALE, RoundingMode.HALF_EVEN);
        BigDecimal deduction = delivered.multiply(BigDecimal.ONE.add(rate))
                .setScale(SETTLEMENT_SCALE, RoundingMode.HALF_EVEN);
        return new SettlementMath(settledDelivered, loss, deduction);
    }

    /** 单笔核销预校验后的舍入结果，三个量均为两位小数口径。 */
    record SettlementMath(BigDecimal settledDelivered, BigDecimal loss, BigDecimal deduction) {
        /** 独立舍入后守恒不成立：上游扣减 != 计入送达 + 损耗。 */
        boolean conservationBroken() {
            return deduction.compareTo(settledDelivered.add(loss)) != 0;
        }
    }

    private SettlementDraft buildDraft(String settlementKey, String allocationKey, String occurredUtc,
                                       String deliveredText, String downstreamUserId, String dayUtc) {
        return buildDraft(settlementKey, allocationKey, occurredUtc, deliveredText, downstreamUserId, dayUtc,
                null);
    }

    private SettlementDraft buildDraft(String settlementKey, String allocationKey, String occurredUtc,
                                       String deliveredText, String downstreamUserId, String dayUtc,
                                       Integer itemIndex) {
        long occurredNanos = parseInstant("occurredUtc", occurredUtc);
        BigDecimal delivered = parseAmount("deliveredVolume", deliveredText);
        long derivedDay = toDayStartNanos(occurredNanos);
        if (dayUtc != null && !dayUtc.isBlank()) {
            long explicitDay = parseDay("dayUtc", dayUtc);
            if (explicitDay != derivedDay) {
                throw ApiException.badRequest("DAY_MISMATCH",
                        "dayUtc " + dayUtc + " 与 occurredUtc 所属 UTC 日 " + toDayUtc(derivedDay) + " 不一致");
            }
        }
        String downstream = downstreamUserId == null || downstreamUserId.isBlank() ? null
                : downstreamUserId.trim();
        if (downstream != null && !KEY_PATTERN.matcher(downstream).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "downstreamUserId 仅允许字母数字及 . - _ :，最长 128 字符");
        }
        return new SettlementDraft(settlementKey, allocationKey, occurredNanos, occurredUtc.trim(), derivedDay,
                delivered, downstream, itemIndex);
    }

    /** 解析 UTC 自然日（yyyy-MM-dd）为当日 00:00:00Z 的纳秒时间戳。 */
    static long parseDay(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不能为空");
        }
        try {
            LocalDate date = LocalDate.parse(value.trim());
            return toNanos(date.atStartOfDay(ZoneOffset.UTC).toInstant());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 必须为 UTC 日期（yyyy-MM-dd）: " + value);
        }
    }

    /** 解析蒸发率：十进制比例 0~0.2000，最多四位小数。 */
    static BigDecimal parseRate(String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "rate 不能为空");
        }
        String trimmed = value.trim();
        if (!RATE_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "rate 必须为 0~0.2000 的十进制比例，最多四位小数: " + value);
        }
        BigDecimal rate = new BigDecimal(trimmed);
        if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(MAX_RATE) > 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "rate 必须在 0~0.2000（含）之间: " + value);
        }
        return rate;
    }

    private static long toDayStartNanos(long nanos) {
        LocalDate day = LocalDate.ofInstant(toInstant(nanos), ZoneOffset.UTC);
        return toNanos(day.atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    private static String toDayUtc(long dayStartNanos) {
        return LocalDate.ofInstant(toInstant(dayStartNanos), ZoneOffset.UTC).toString();
    }

    private static String rateText(BigDecimal rate) {
        return rate.setScale(4, RoundingMode.UNNECESSARY).toPlainString();
    }

    private String firstSettlementIso(String channelId, long dayStartNanos) {
        Long first = repository.firstSettlementNanos(channelId, dayStartNanos);
        return first == null ? null : toIso(first);
    }

    private EvaporationRateResponse toRateResponse(EvaporationRateRow row, Long firstSettlementNanos) {
        return new EvaporationRateResponse(row.channelId(), toDayUtc(row.dayStartNanos()), rateText(row.rate()),
                row.version(), toIso(row.createdNanos()), toIso(row.updatedNanos()),
                firstSettlementNanos == null ? null : toIso(firstSettlementNanos),
                firstSettlementNanos != null);
    }

    private SettlementResponse toSettlementResponse(SettlementRow row, String correctionKey) {
        return new SettlementResponse(row.settlementKey(), row.batchKey(), row.windowId(), row.channelId(),
                row.allocationKey(), row.downstreamUserId(), toDayUtc(row.dayStartNanos()),
                toIso(row.occurredNanos()), fmt(row.deliveredVolume()), fmt(row.settledDeliveredVolume()),
                fmt(row.lossVolume()), fmt(row.upstreamDeduction()), rateText(row.rateValue()), row.rateVersion(), row.status(),
                row.actor(), toIso(row.createdNanos()), correctionKey);
    }

    private SettlementReversalResponse toReversalResponse(CorrectionRow row, SettlementRow original) {
        String originalKey = original == null ? null : original.settlementKey();
        return new SettlementReversalResponse(row.correctionKey(), row.originalSettlementId(), originalKey,
                row.batchKey(), row.windowId(), row.channelId(), row.allocationKey(), row.downstreamUserId(),
                toDayUtc(row.dayStartNanos()), fmt(row.reversedDeliveredVolume()),
                fmt(row.reversedSettledDeliveredVolume()), fmt(row.reversedLossVolume()),
                fmt(row.restoredUpstreamVolume()), rateText(row.rateValue()), row.rateVersion(), row.reason(), row.actor(),
                toIso(row.createdNanos()));
    }

    /** 单笔/批量共用的已解析核销明细。 */
    private record SettlementDraft(String settlementKey, String allocationKey, long occurredNanos,
                                   String occurredUtc, long dayStartNanos, BigDecimal delivered,
                                   String downstreamUserId, Integer itemIndex) {
        int itemIndexSafe() {
            return itemIndex == null ? -1 : itemIndex;
        }
    }

    /** 明细与按当日率版本完成的舍入计算结果。 */
    private record SettlementComputation(SettlementDraft draft, SettlementMath math, EvaporationRateRow rate) {
    }

    /** 一次核销事务的全部写入结果。 */
    private record SettlementOutcome(List<SettlementResponse> responses, List<UpstreamBalance> balances) {
        SettlementResultResponse single() {
            SettlementResponse response = responses.get(0);
            UpstreamBalance balance = balances.get(0);
            return new SettlementResultResponse(response, balance.remaining(), balance.required());
        }

        SettlementBatchResponse batch(String batchKey, long windowId) {
            return new SettlementBatchResponse(batchKey, windowId, responses, balances);
        }
    }

    private static String nvl(String value) {
        return value == null ? "" : value.trim();
    }

    private static ApiException conflictWithIndex(String code, String message, Integer itemIndex) {
        return new ApiException(org.springframework.http.HttpStatus.CONFLICT, code, message, null, null, null,
                itemIndex);
    }

    private static ApiException notFoundWithIndex(String code, String message, Integer itemIndex) {
        return new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, code, message, null, null, null,
                itemIndex);
    }

    // ------------------------------------------------------------------
    // 幂等命令框架
    // ------------------------------------------------------------------

    /**
     * 在单事务内执行幂等命令：先占位插入命令行，再执行业务并写回响应。
     * 并发同键时占位插入会因唯一约束失败，随后读取已提交的首次结果重放或报 409。
     */
    private <T> T runCommand(String operation, String commandKey, String params, Class<T> type,
                             Supplier<T> business) {
        try {
            return tx.execute(status -> {
                CommandRow existing = repository.findCommand(commandKey);
                if (existing != null) {
                    return replay(existing, operation, params, type);
                }
                repository.insertCommand(commandKey, operation, params, nowNanos());
                T result = business.get();
                repository.updateCommandResponse(commandKey, toJson(result));
                return result;
            });
        } catch (DuplicateKeyException e) {
            CommandRow committed = repository.findCommand(commandKey);
            if (committed == null || committed.response() == null) {
                throw ApiException.conflict("COMMAND_CONFLICT", "相同 commandKey 的命令正在处理，请重试");
            }
            return replay(committed, operation, params, type);
        }
    }

    private <T> T replay(CommandRow existing, String operation, String params, Class<T> type) {
        if (!existing.operation().equals(operation) || !existing.params().equals(params)) {
            throw ApiException.conflict("COMMAND_KEY_REUSED", "相同 commandKey 但参数不同，拒绝重放");
        }
        return fromJson(existing.response(), type);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private WindowRow lockWindowOf(AllocationRow allocation) {
        return lockWindowById(allocation.windowId());
    }

    private WindowRow lockWindowById(long windowId) {
        WindowRow window = repository.lockWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        return window;
    }

    /** 键校验并附带批量明细索引，错误返回 400 且可定位明细。 */
    private static void requireKeyWithIndex(String field, String value, int itemIndex) {
        if (value == null || !KEY_PATTERN.matcher(value).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT",
                    field + " 不能为空，且仅允许字母数字及 . - _ :，最长 128 字符（明细 " + itemIndex + "）",
                    null, null, null, itemIndex);
        }
    }

    private BigDecimal availableTotal(WindowRow window) {
        CurtailmentRow active = repository.findActiveCurtailment(window.id());
        return active != null ? active.volume() : window.plannedVolume();
    }

    private WindowResponse toWindowResponse(WindowRow row, CurtailmentRow active) {
        BigDecimal available = active != null ? active.volume() : row.plannedVolume();
        return new WindowResponse(row.id(), row.windowKey(), row.channelId(), toIso(row.startNanos()),
                toIso(row.endNanos()), fmt(row.plannedVolume()),
                active != null ? fmt(active.volume()) : null, fmt(available), toIso(row.createdNanos()));
    }

    private AllocationResponse toAllocationResponse(AllocationRow row) {
        return new AllocationResponse(row.allocationKey(), row.windowId(), row.userId(), fmt(row.amount()),
                fmt(row.heldAmount()), row.requester(), row.status(),
                toIso(row.createdNanos()), toIso(row.updatedNanos()));
    }

    private TransferResponse toTransferResponse(TransferRow row) {
        return new TransferResponse(row.transferKey(), row.windowId(), row.sourceAllocationKey(),
                row.targetAllocationKey(), fmt(row.amount()), row.actor(), toIso(row.createdNanos()));
    }

    private CurtailmentResponse toCurtailmentResponse(CurtailmentRow row) {
        return new CurtailmentResponse(row.id(), row.windowId(), fmt(row.volume()), row.status(),
                toIso(row.createdNanos()), row.cancelledNanos() == null ? null : toIso(row.cancelledNanos()));
    }

    long nowNanos() {
        return toNanos(clock.instant());
    }

    /** Instant -> UTC 纳秒时间戳。 */
    static long toNanos(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), NANOS_PER_SECOND), instant.getNano());
    }

    /** UTC 纳秒时间戳 -> Instant。 */
    static Instant toInstant(long nanos) {
        return Instant.ofEpochSecond(Math.floorDiv(nanos, NANOS_PER_SECOND),
                Math.floorMod(nanos, NANOS_PER_SECOND));
    }

    static String toIso(long nanos) {
        return toInstant(nanos).toString();
    }

    static long parseInstant(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不能为空");
        }
        try {
            return toNanos(Instant.parse(value.trim()));
        } catch (DateTimeParseException | ArithmeticException e) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不是合法的 UTC 时刻: " + value);
        }
    }

    /** 解析水量：十进制字符串，大于 0，最多 3 位小数。 */
    static BigDecimal parseAmount(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不能为空");
        }
        String trimmed = value.trim();
        if (!AMOUNT_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 必须为大于 0 的十进制字符串，最多 3 位小数: " + value);
        }
        BigDecimal amount = new BigDecimal(trimmed);
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 必须大于 0");
        }
        return amount;
    }

    static void requireKey(String field, String value) {
        if (value == null || !KEY_PATTERN.matcher(value).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 不能为空，且仅允许字母数字及 . - _ :，最长 128 字符");
        }
    }

    /** 水量格式化为十进制字符串（去掉多余尾零）。 */
    static String fmt(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.signum() == 0) {
            return "0";
        }
        return stripped.toPlainString();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("响应反序列化失败", e);
        }
    }
}
