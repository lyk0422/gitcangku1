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
import com.example.starter.water.dto.Dtos.BatchSettleResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CorrectionResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.EvaporationRateHistoryResponse;
import com.example.starter.water.dto.Dtos.EvaporationRateResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.SettlementDiagnosticResponse;
import com.example.starter.water.dto.Dtos.SettlementHistoryResponse;
import com.example.starter.water.dto.Dtos.SettlementItemRequest;
import com.example.starter.water.dto.Dtos.SettlementResponse;
import com.example.starter.water.dto.Dtos.SubmitAllocationRequest;
import com.example.starter.water.dto.Dtos.TransferListResponse;
import com.example.starter.water.dto.Dtos.TransferResponse;
import com.example.starter.water.dto.Dtos.WindowResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
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
    static final String STATUS_REVERSED = "REVERSED";

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("\\d{1,16}(\\.\\d{1,3})?");
    private static final Pattern KEY_PATTERN = Pattern.compile("[\\w.\\-:]{1,128}");
    private static final Pattern RATE_PATTERN = Pattern.compile("\\d{1,2}(\\.\\d{1,4})?");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final BigDecimal MAX_RATE = new BigDecimal("0.2000");

    private final WaterRepository repository;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private Clock clock = Clock.systemUTC();

    public WaterService(WaterRepository repository, PlatformTransactionManager transactionManager,
                        ObjectMapper objectMapper) {
        this.repository = repository;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /** 替换 UTC 时钟，仅用于测试中的可控时间（默认系统 UTC 时钟）。 */
    void setClock(Clock clock) {
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
            long id = repository.insertWindow(windowKey, channelId, startNanos, endNanos, planned, currentNanos());
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
            long id = repository.insertAllocation(allocationKey, windowId, userId, qty, actor, currentNanos());
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
            repository.updateAllocationStatus(allocation.id(), STATUS_APPROVED, currentNanos());
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
            repository.updateAllocationStatus(allocation.id(), STATUS_CANCELLED, currentNanos());
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
            long now = currentNanos();
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
            long id = repository.insertCurtailment(windowId, qty, currentNanos());
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
            repository.cancelCurtailment(active.id(), currentNanos());
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
    // 日蒸发率
    // ------------------------------------------------------------------

    /**
     * 登记或修改渠道某 UTC 自然日的蒸发率（0～20%，最多 4 位小数）。
     * 当天首次核销前可以修改：修改即追加新版本（旧版本保留为证据）；当天一旦存在核销凭证，
     * 再修改返回 409。核销固化的是提交时刻的当前版本。
     */
    public EvaporationRateResponse registerEvaporationRate(String commandKey, String channelId,
                                                           String rateDate, String rate) {
        requireKey("commandKey", commandKey);
        requireKey("channelId", channelId);
        LocalDate date = parseDate("rateDate", rateDate);
        BigDecimal value = parseRate("rate", rate);
        String params = "RATE_REGISTER|" + channelId + "|" + date + "|" + value.toPlainString();
        return runCommand("RATE_REGISTER", commandKey, params, EvaporationRateResponse.class, () -> {
            EvaporationRateRow current = repository.lockCurrentRate(channelId, date);
            int version = 1;
            if (current != null) {
                // 当天已有核销凭证（含已更正的凭证，证据仍在）即冻结，修改一律 409
                if (repository.existsSettlementOnDate(channelId, date)) {
                    throw ApiException.conflict("RATE_FROZEN",
                            "渠道 " + channelId + " 在 " + date + " 已存在核销，当日蒸发率不可修改（当前版本 v"
                                    + current.version() + "）");
                }
                version = current.version() + 1;
            } else if (repository.existsRateVersion(channelId, date)) {
                // 理论上不可达：任意历史版本存在时必有一个当前版本
                throw ApiException.conflict("RATE_VERSION_STATE", "当日蒸发率版本状态异常，拒绝修改");
            }
            long id;
            try {
                id = repository.insertRateVersion(channelId, date, value, version, currentNanos());
            } catch (DuplicateKeyException e) {
                // 并发首次登记同一渠道同日：唯一 (渠道,日期,版本) 冲突，按提交顺序后者 409
                throw ApiException.conflict("EVAPORATION_RATE_CONCURRENT",
                        "渠道 " + channelId + " 在 " + date + " 的蒸发率正在并发登记，请重试");
            }
            return toRateResponse(repository.lockRateById(id));
        });
    }

    /** 查询渠道全部蒸发率版本（含历史版本），按日期、版本升序；只读。 */
    public EvaporationRateHistoryResponse getRateHistory(String channelId) {
        requireKey("channelId", channelId);
        List<EvaporationRateResponse> rates = repository.listRates(channelId).stream()
                .map(this::toRateResponse).toList();
        return new EvaporationRateHistoryResponse(channelId, rates);
    }

    // ------------------------------------------------------------------
    // 送达核销（单笔与批量）
    // ------------------------------------------------------------------

    /** 单笔核销送达水量，等价于一批一项。 */
    public SettlementResponse settleDelivery(String commandKey, String batchKey, String allocationKey,
                                             String deliveryDate, String deliveredVolume) {
        requireKey("commandKey", commandKey);
        requireKey("batchKey", batchKey);
        requireKey("allocationKey", allocationKey);
        parseDate("deliveryDate", deliveryDate);
        parseAmount("deliveredVolume", deliveredVolume);
        String settlementKey = batchKey + ":1";
        List<SettlementItemRequest> items = List.of(
                new SettlementItemRequest(settlementKey, allocationKey,
                        deliveryDate.trim(), deliveredVolume.trim()));
        String params = settlementParams("SETTLEMENT", batchKey, items);
        BatchSettleResponse result = runCommand("SETTLEMENT", commandKey, params, BatchSettleResponse.class,
                () -> settleBatch(batchKey, items));
        return result.settlements().get(0);
    }

    /**
     * 批量核销：按完整最终余额与逐笔舍入口径预校验——同一窗口、日期落在窗口内、率已登记、
     * 逐笔银行家舍入且 上游扣减=送达+损耗、按同一申请累计扣减不超过其最终持有额度；
     * 任一失败整批回滚，不写入任何凭证或半成品。
     */
    public BatchSettleResponse settleDeliveries(String commandKey, String batchKey,
                                                List<SettlementItemRequest> rawItems) {
        requireKey("commandKey", commandKey);
        requireKey("batchKey", batchKey);
        if (rawItems == null || rawItems.isEmpty()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "items 至少包含一笔核销");
        }
        // 先做完整的参数规范化（失败不占键），再进入命令事务
        List<SettlementItemRequest> items = new ArrayList<>();
        List<String> seenKeys = new ArrayList<>();
        for (int i = 0; i < rawItems.size(); i++) {
            SettlementItemRequest item = rawItems.get(i);
            if (item == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "items[" + i + "] 不能为空");
            }
            requireKey("items[" + i + "].settlementKey", item.settlementKey());
            requireKey("items[" + i + "].allocationKey", item.allocationKey());
            if (seenKeys.contains(item.settlementKey())) {
                throw ApiException.badRequest("INVALID_ARGUMENT",
                        "批内 settlementKey 重复: " + item.settlementKey());
            }
            seenKeys.add(item.settlementKey());
            LocalDate date = parseDate("items[" + i + "].deliveryDate", item.deliveryDate());
            parseAmount("items[" + i + "].deliveredVolume", item.deliveredVolume());
            items.add(new SettlementItemRequest(item.settlementKey(), item.allocationKey(),
                    date.toString(), item.deliveredVolume().trim()));
        }
        String params = settlementParams("SETTLEMENT_BATCH", batchKey, items);
        return runCommand("SETTLEMENT_BATCH", commandKey, params, BatchSettleResponse.class,
                () -> settleBatch(batchKey, items));
    }

    private BatchSettleResponse settleBatch(String batchKey, List<SettlementItemRequest> items) {
        if (repository.findBatchByKey(batchKey) != null) {
            throw ApiException.conflict("BATCH_KEY_REUSED", "batchKey 已被使用: " + batchKey);
        }
        for (SettlementItemRequest item : items) {
            if (repository.findSettlementByKey(item.settlementKey()) != null) {
                throw ApiException.conflict("SETTLEMENT_KEY_REUSED",
                        "settlementKey 已被使用: " + item.settlementKey());
            }
        }
        // 解析全部申请行（不加锁的预读）
        List<AllocationRow> allocations = new ArrayList<>();
        Long windowId = null;
        for (int i = 0; i < items.size(); i++) {
            AllocationRow allocation = repository.findAllocationByKey(items.get(i).allocationKey());
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND",
                        "配水申请不存在: " + items.get(i).allocationKey());
            }
            if (windowId == null) {
                windowId = allocation.windowId();
            } else if (windowId != allocation.windowId()) {
                throw ApiException.unprocessable("BATCH_MIXED_WINDOWS",
                        "批量核销的全部申请必须属于同一供水窗口；第 " + (i + 1) + " 笔属于窗口 "
                                + allocation.windowId() + "，与窗口 " + windowId + " 不一致");
            }
            allocations.add(allocation);
        }

        // 锁窗口行：与转让、取消、批准、限供、其他核销按事务提交顺序串行裁决
        WindowRow window = repository.lockWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        String channelId = window.channelId();

        // 锁内重读全部涉及申请（按主键排序加锁，避免跨批次死锁）
        Map<Long, AllocationRow> lockedById = new LinkedHashMap<>();
        allocations.stream().map(AllocationRow::id).distinct().sorted().forEach(id ->
                lockedById.put(id, repository.lockAllocationById(id)));

        // 逐日锁定并读取蒸发率当前版本（同一日只锁一次，按日期排序加锁避免跨批次死锁）
        Map<LocalDate, EvaporationRateRow> rateByDate = new LinkedHashMap<>();
        items.stream().map(i -> LocalDate.parse(i.deliveryDate(), DATE_FORMATTER))
                .distinct().sorted().forEach(date -> {
                    EvaporationRateRow rate = repository.lockCurrentRate(channelId, date);
                    if (rate == null) {
                        throw ApiException.unprocessable("EVAPORATION_RATE_MISSING",
                                "渠道 " + channelId + " 在 " + date + " 的蒸发率尚未登记，无法核销");
                    }
                    rateByDate.put(date, rate);
                });

        // 完整最终状态预校验：日期窗口、申请状态、逐笔舍入守恒、按申请累计扣减不超过最终持有额度
        Map<Long, BigDecimal> cumulativeDeduction = new HashMap<>();
        List<PreparedSettlement> prepared = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            SettlementItemRequest item = items.get(i);
            LocalDate date = LocalDate.parse(item.deliveryDate(), DATE_FORMATTER);
            AllocationRow allocation = lockedById.get(allocations.get(i).id());
            if (!STATUS_APPROVED.equals(allocation.status())) {
                throw ApiException.conflict("ALLOCATION_NOT_SETTLEABLE",
                        "第 " + (i + 1) + " 笔申请 " + allocation.allocationKey() + " 状态为 "
                                + allocation.status() + "，仅 APPROVED 可以核销");
            }
            long dayStartNanos = toNanos(date.atStartOfDay().toInstant(ZoneOffset.UTC));
            long dayEndNanos = toNanos(date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
            if (!(window.startNanos() < dayEndNanos && dayStartNanos < window.endNanos())) {
                throw ApiException.unprocessable("DELIVERY_OUTSIDE_WINDOW",
                        "第 " + (i + 1) + " 笔送达日 " + date + " 不在配水窗口 ["
                                + toIso(window.startNanos()) + ", " + toIso(window.endNanos()) + ") 内");
            }
            EvaporationRateRow rate = rateByDate.get(date);
            BigDecimal delivered = new BigDecimal(item.deliveredVolume().trim());
            BigDecimal loss = bankerLoss(delivered, rate.rate());
            BigDecimal deduction = delivered.add(loss);
            if (deduction.compareTo(delivered.add(loss)) != 0 || loss.signum() < 0) {
                // 防御性守恒校验：正常算术下不可达，命中则拒绝整批
                throw ApiException.unprocessable("CONSERVATION_MISMATCH",
                        "第 " + (i + 1) + " 笔舍入后守恒不成立：上游扣减 " + fmt(deduction)
                                + " != 送达 " + fmt(delivered) + " + 损耗 " + fmt(loss));
            }
            BigDecimal required = cumulativeDeduction.merge(allocation.id(), deduction, BigDecimal::add);
            if (required.compareTo(allocation.heldAmount()) > 0) {
                BigDecimal shortfall = required.subtract(allocation.heldAmount());
                throw ApiException.unprocessable("UPSTREAM_QUOTA_EXCEEDED",
                        "第 " + (i + 1) + " 笔上游剩余额度不足：申请 " + allocation.allocationKey()
                                + " 实际持有 " + fmt(allocation.heldAmount()) + "，本批要求累计扣减 "
                                + fmt(required) + "，差额 " + fmt(shortfall));
            }
            prepared.add(new PreparedSettlement(item, allocation, date, rate, delivered, loss, deduction));
        }

        // 全部预校验通过后统一落库：批次、逐笔凭证、申请扣减与送达/损耗累加
        long now = currentNanos();
        long batchId;
        try {
            batchId = repository.insertBatch(batchKey, window.id(), items.size(), now);
        } catch (DuplicateKeyException e) {
            // 并发复用同一 batchKey（换 commandKey）：整事务回滚
            throw ApiException.conflict("BATCH_KEY_REUSED", "batchKey 已被使用: " + batchKey);
        }
        List<SettlementResponse> responses = new ArrayList<>();
        for (PreparedSettlement p : prepared) {
            try {
                repository.insertSettlement(p.item.settlementKey(), batchId, window.id(),
                        channelId, p.allocation.id(), p.allocation.allocationKey(), p.rate.id(),
                        p.rate.version(), p.date, p.rate.rate(), p.delivered, p.loss, p.deduction, now);
            } catch (DuplicateKeyException e) {
                // 并发复用同一 settlementKey（换 commandKey）：整事务回滚
                throw ApiException.conflict("SETTLEMENT_KEY_REUSED",
                        "settlementKey 已被使用: " + p.item.settlementKey());
            }
            repository.applySettlementToAllocation(p.allocation.id(), p.delivered, p.loss, now);
            SettlementRow row = repository.findSettlementByKey(p.item.settlementKey());
            responses.add(toSettlementResponse(row));
        }
        return new BatchSettleResponse(batchKey, window.id(), items.size(), List.copyOf(responses));
    }

    // ------------------------------------------------------------------
    // 反向更正与查询
    // ------------------------------------------------------------------

    /**
     * 反向更正一笔已结算核销：只能更正 SETTLED 凭证；冲回其上游扣减、送达与损耗累计，
     * 原凭证置 REVERSED 并保留全部证据。已更正凭证不能再次更正（409）。
     */
    public CorrectionResponse correctSettlement(String commandKey, String correctionKey,
                                                String settlementKey, String reason) {
        requireKey("commandKey", commandKey);
        requireKey("correctionKey", correctionKey);
        requireKey("settlementKey", settlementKey);
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "reason 不能为空");
        }
        String trimmedReason = reason.trim();
        if (trimmedReason.length() > 512) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "reason 最长 512 字符");
        }
        String params = "CORRECTION|" + correctionKey + "|" + settlementKey + "|" + trimmedReason;
        return runCommand("CORRECTION", commandKey, params, CorrectionResponse.class, () -> {
            SettlementRow settlement = repository.findSettlementByKey(settlementKey);
            if (settlement == null) {
                throw ApiException.notFound("SETTLEMENT_NOT_FOUND", "核销凭证不存在: " + settlementKey);
            }
            if (repository.findCorrectionByKey(correctionKey) != null) {
                throw ApiException.conflict("CORRECTION_KEY_REUSED",
                        "correctionKey 已被使用: " + correctionKey);
            }
            // 先锁窗口，与核销/转让等按提交顺序串行
            WindowRow window = repository.lockWindowById(settlement.windowId());
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + settlement.windowId());
            }
            SettlementRow locked = repository.lockSettlementById(settlement.id());
            if (STATUS_REVERSED.equals(locked.status())) {
                throw ApiException.conflict("SETTLEMENT_ALREADY_REVERSED",
                        "核销凭证 " + settlementKey + " 已被反向更正，不能再次更正");
            }
            AllocationRow allocation = repository.lockAllocationByKey(locked.allocationKey());
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND",
                        "配水申请不存在: " + locked.allocationKey());
            }
            long now = currentNanos();
            repository.applyCorrectionToAllocation(allocation.id(), locked.rawDelivered(), locked.loss(), now);
            repository.markSettlementReversed(locked.id());
            long correctionId;
            try {
                correctionId = repository.insertCorrection(correctionKey, locked.id(), settlementKey,
                        window.id(), locked.rawDelivered(), locked.loss(), locked.upstreamDeduction(),
                        trimmedReason, now);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("CORRECTION_KEY_REUSED",
                        "correctionKey 已被使用: " + correctionKey);
            }
            return toCorrectionResponse(repository.findCorrectionById(correctionId));
        });
    }

    /** 查询窗口核销明细（全部凭证含已更正 + 全部更正），按发生顺序；只读，不改变状态。 */
    public SettlementHistoryResponse getSettlementHistory(long windowId) {
        WindowRow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        List<SettlementResponse> settlements = repository.listSettlements(windowId).stream()
                .map(this::toSettlementResponse).toList();
        List<CorrectionResponse> corrections = repository.listCorrections(windowId).stream()
                .map(this::toCorrectionResponse).toList();
        return new SettlementHistoryResponse(windowId, settlements, corrections);
    }

    /** 按业务键查询单笔核销凭证，不存在 404；只读。 */
    public SettlementResponse getSettlement(String settlementKey) {
        requireKey("settlementKey", settlementKey);
        SettlementRow row = repository.findSettlementByKey(settlementKey);
        if (row == null) {
            throw ApiException.notFound("SETTLEMENT_NOT_FOUND", "核销凭证不存在: " + settlementKey);
        }
        return toSettlementResponse(row);
    }

    /**
     * 单申请核销诊断：返回当前持有额度、已结算送达/损耗与剩余可核销额度（实际值）；
     * 给定送达日与试算送达量时，按当日当前率以银行家舍入口径试算要求扣减与差额，不落库。
     */
    public SettlementDiagnosticResponse getSettlementDiagnostic(String allocationKey, String deliveryDate,
                                                                String deliveredVolume) {
        requireKey("allocationKey", allocationKey);
        AllocationRow allocation = repository.findAllocationByKey(allocationKey);
        if (allocation == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
        }
        WindowRow window = repository.findWindowById(allocation.windowId());
        LocalDate date = deliveryDate == null || deliveryDate.isBlank() ? null
                : parseDate("deliveryDate", deliveryDate);
        BigDecimal trialDelivered = null;
        EvaporationRateRow rate = null;
        BigDecimal trialLoss = null;
        BigDecimal trialDeduction = null;
        BigDecimal shortfall = null;
        String rateVersion = null;
        if (date != null) {
            rate = repository.findCurrentRate(window.channelId(), date);
            if (deliveredVolume != null && !deliveredVolume.isBlank()) {
                trialDelivered = parseAmount("deliveredVolume", deliveredVolume);
                if (rate != null) {
                    trialLoss = bankerLoss(trialDelivered, rate.rate());
                    trialDeduction = trialDelivered.add(trialLoss);
                    BigDecimal after = trialDeduction.subtract(allocation.heldAmount());
                    shortfall = after.signum() > 0 ? after : BigDecimal.ZERO;
                }
            }
            if (rate != null) {
                rateVersion = String.valueOf(rate.version());
            }
        }
        return new SettlementDiagnosticResponse(allocationKey, allocation.windowId(), allocation.status(),
                fmt(allocation.heldAmount()), fmt(allocation.settledDelivered()), fmt(allocation.settledLoss()),
                fmt(allocation.heldAmount()),
                date == null ? null : date.toString(),
                rate == null ? null : fmt(rate.rate()), rateVersion,
                trialDelivered == null ? null : fmt(trialDelivered),
                trialLoss == null ? null : fmt(trialLoss),
                trialDeduction == null ? null : fmt(trialDeduction),
                shortfall == null ? null : fmt(shortfall));
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
                repository.insertCommand(commandKey, operation, params, currentNanos());
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
        WindowRow window = repository.lockWindowById(allocation.windowId());
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + allocation.windowId());
        }
        return window;
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
                toIso(row.createdNanos()), toIso(row.updatedNanos()),
                fmt(row.settledDelivered()), fmt(row.settledLoss()));
    }

    private TransferResponse toTransferResponse(TransferRow row) {
        return new TransferResponse(row.transferKey(), row.windowId(), row.sourceAllocationKey(),
                row.targetAllocationKey(), fmt(row.amount()), row.actor(), toIso(row.createdNanos()));
    }

    private CurtailmentResponse toCurtailmentResponse(CurtailmentRow row) {
        return new CurtailmentResponse(row.id(), row.windowId(), fmt(row.volume()), row.status(),
                toIso(row.createdNanos()), row.cancelledNanos() == null ? null : toIso(row.cancelledNanos()));
    }

    static long nowNanos() {
        return toNanos(Instant.now());
    }

    /** 当前时间（受可控时钟影响），单位 UTC 纳秒。 */
    private long currentNanos() {
        return toNanos(Instant.now(clock));
    }

    /** 当前 UTC 自然日。 */
    private LocalDate currentDate() {
        return LocalDate.now(clock);
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

    static long parseInstant(String field, String value) {        if (value == null || value.isBlank()) {
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

    /** 解析 UTC 自然日（yyyy-MM-dd）。 */
    static LocalDate parseDate(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不能为空");
        }
        try {
            return LocalDate.parse(value.trim(), DATE_FORMATTER);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 不是合法的 UTC 日期(yyyy-MM-dd): " + value);
        }
    }

    /** 解析蒸发率：十进制，0～0.2（含），最多 4 位小数。 */
    static BigDecimal parseRate(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不能为空");
        }
        String trimmed = value.trim();
        if (!RATE_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 必须为 0～0.2 之间的十进制数，最多 4 位小数: " + value);
        }
        BigDecimal rate = new BigDecimal(trimmed);
        if (rate.signum() < 0 || rate.compareTo(MAX_RATE) > 0 || rate.scale() > 4) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 必须满足 0 <= rate <= 0.2000，最多 4 位小数: " + value);
        }
        return rate;
    }

    /**
     * 银行家舍入（HALF_EVEN）到 2 位小数计算蒸发损耗：loss = round_half_even(送达 × 率, 2)。
     * 率按 4 位小数值精确参与乘法，送达最多 3 位小数。
     */
    static BigDecimal bankerLoss(BigDecimal delivered, BigDecimal rate) {
        return delivered.multiply(rate).setScale(2, RoundingMode.HALF_EVEN);
    }

    /** 核销批量命令的规范化参数串：批次键 + 逐笔（核销键|申请|日期|送达），保持请求顺序。 */
    private static String settlementParams(String operation, String batchKey,
                                           List<SettlementItemRequest> items) {
        StringBuilder sb = new StringBuilder(operation).append('|').append(batchKey);
        for (SettlementItemRequest item : items) {
            sb.append('|').append(item.settlementKey()).append('|').append(item.allocationKey())
                    .append('|').append(item.deliveryDate()).append('|').append(item.deliveredVolume());
        }
        return sb.toString();
    }

    private EvaporationRateResponse toRateResponse(EvaporationRateRow row) {
        return new EvaporationRateResponse(row.id(), row.channelId(), row.rateDate().toString(),
                fmt(row.rate()), row.version(), !row.superseded(), toIso(row.createdNanos()));
    }

    private SettlementResponse toSettlementResponse(SettlementRow row) {
        return new SettlementResponse(row.settlementKey(), row.batchKey(), row.windowId(), row.channelId(),
                row.allocationKey(), row.rateDate().toString(), row.rateVersion(), fmt(row.rateValue()),
                fmt(row.rawDelivered()), fmt(row.loss()), fmt(row.upstreamDeduction()), row.status(),
                toIso(row.createdNanos()));
    }

    private CorrectionResponse toCorrectionResponse(CorrectionRow row) {
        return new CorrectionResponse(row.correctionKey(), row.settlementKey(), row.windowId(),
                fmt(row.deliveredRefund()), fmt(row.lossRefund()), fmt(row.deductionRefund()),
                row.reason(), toIso(row.createdNanos()));
    }

    /** 预校验通过、待落库的单笔核销。 */
    private record PreparedSettlement(SettlementItemRequest item, AllocationRow allocation, LocalDate date,
                                      EvaporationRateRow rate, BigDecimal delivered, BigDecimal loss,
                                      BigDecimal deduction) {
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
