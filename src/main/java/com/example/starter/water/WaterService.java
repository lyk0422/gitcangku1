package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.BasinRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.ExecutionRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.BasinResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.ExecutionListResponse;
import com.example.starter.water.dto.Dtos.ExecutionResponse;
import com.example.starter.water.dto.Dtos.GateWindowResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.TransferListResponse;
import com.example.starter.water.dto.Dtos.TransferResponse;
import com.example.starter.water.dto.Dtos.WindowResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
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

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("\\d{1,16}(\\.\\d{1,3})?");
    private static final Pattern KEY_PATTERN = Pattern.compile("[\\w.\\-:]{1,128}");
    private static final Pattern INTEGER_PATTERN = Pattern.compile("\\d{1,16}");
    /** 水位差上限，单位米；执行前后两池水位差绝对值均不得超过该值。 */
    static final long MAX_LEVEL_DIFF_M = 2L;
    /** 水位显示舍入的小数位数；门禁判断使用整数精确比对，不使用该舍入值。 */
    private static final int LEVEL_SCALE = 12;

    private final WaterRepository repository;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public WaterService(WaterRepository repository, PlatformTransactionManager transactionManager,
                        ObjectMapper objectMapper) {
        this.repository = repository;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
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
            // 只能转未执行量：已执行水量不可转移
            BigDecimal transferable = lockedSource.heldAmount().subtract(lockedSource.executedAmount());
            if (transferable.compareTo(amount) < 0) {
                throw ApiException.quotaExceeded("源申请未执行额度 " + fmt(transferable)
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
    // 闸门双池：登记、执行、查询
    // ------------------------------------------------------------------

    /**
     * 登记闸门窗口：在普通窗口之外登记上下池。面积为固定正整数（平方米），
     * 初始体积与体积上下界为整数（立方米），登记时两池水位差即不得超过 2 米。
     */
    public GateWindowResponse createGateWindow(String commandKey, String windowKey, String channelId,
                                               String startUtc, String endUtc, String plannedVolume,
                                               Long upperAreaM2, Long upperInitialVolumeM3,
                                               Long upperMinVolumeM3, Long upperMaxVolumeM3,
                                               Long lowerAreaM2, Long lowerInitialVolumeM3,
                                               Long lowerMinVolumeM3, Long lowerMaxVolumeM3) {
        requireKey("commandKey", commandKey);
        requireKey("windowKey", windowKey);
        requireKey("channelId", channelId);
        long startNanos = parseInstant("startUtc", startUtc);
        long endNanos = parseInstant("endUtc", endUtc);
        if (startNanos >= endNanos) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "startUtc 必须早于 endUtc");
        }
        BigDecimal planned = parseAmount("plannedVolume", plannedVolume);
        long upperArea = requirePositiveLong("upperAreaM2", upperAreaM2);
        long upperInit = requireNonNegativeLong("upperInitialVolumeM3", upperInitialVolumeM3);
        long upperMin = requireNonNegativeLong("upperMinVolumeM3", upperMinVolumeM3);
        long upperMax = requireNonNegativeLong("upperMaxVolumeM3", upperMaxVolumeM3);
        long lowerArea = requirePositiveLong("lowerAreaM2", lowerAreaM2);
        long lowerInit = requireNonNegativeLong("lowerInitialVolumeM3", lowerInitialVolumeM3);
        long lowerMin = requireNonNegativeLong("lowerMinVolumeM3", lowerMinVolumeM3);
        long lowerMax = requireNonNegativeLong("lowerMaxVolumeM3", lowerMaxVolumeM3);
        requireBounds("upper", upperMin, upperMax, upperInit);
        requireBounds("lower", lowerMin, lowerMax, lowerInit);
        if (!levelDiffWithin(upperInit, upperArea, lowerInit, lowerArea)) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "登记时两池水位差不得超过 2 米");
        }
        String params = "GATE_WINDOW_CREATE|" + windowKey + "|" + channelId + "|" + startNanos + "|" + endNanos
                + "|" + planned.toPlainString() + "|" + upperArea + "|" + upperInit + "|" + upperMin + "|"
                + upperMax + "|" + lowerArea + "|" + lowerInit + "|" + lowerMin + "|" + lowerMax;
        return runCommand("GATE_WINDOW_CREATE", commandKey, params, GateWindowResponse.class, () -> {
            if (repository.existsOverlappingWindow(channelId, startNanos, endNanos)) {
                throw ApiException.conflict("WINDOW_OVERLAP", "同一渠道存在时间重叠的供水窗口");
            }
            long now = nowNanos();
            long id = repository.insertWindow(windowKey, channelId, startNanos, endNanos, planned, now);
            repository.insertBasin(id, upperArea, upperInit, upperMin, upperMax,
                    lowerArea, lowerInit, lowerMin, lowerMax, now);
            return new GateWindowResponse(id, windowKey, channelId, toIso(startNanos), toIso(endNanos),
                    fmt(planned), toIso(now), toBasinResponse(repository.findBasinByWindowId(id)));
        });
    }

    /** 查询窗口双池状态；普通窗口未登记双池返回 404。 */
    public BasinResponse getBasins(long windowId) {
        if (repository.findWindowById(windowId) == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        BasinRow basin = repository.findBasinByWindowId(windowId);
        if (basin == null) {
            throw ApiException.notFound("BASIN_NOT_CONFIGURED", "普通窗口不启用双池规则: " + windowId);
        }
        return toBasinResponse(basin);
    }

    /**
     * 闸门执行：仅当前 APPROVED 申请可执行，累计执行不超过其批准量（持有额度）；
     * 每次执行正整数水量 V，从上池扣 V、下池加 V，执行前后两池水位差均不得超过 2 米、体积均须在界内，
     * 任一条件失败 422 且双池与执行账均不变。窗口行锁串行化执行/取消/转让，禁止分别检查后超灌。
     */
    public ExecutionResponse executeAllocation(String commandKey, String allocationKey, String executionKey,
                                               String volume, Long expectedVersion, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("executionKey", executionKey);
        requireKey("X-Actor-Id", actor);
        if (allocationKey == null || allocationKey.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "allocationKey 不能为空");
        }
        long vol = parsePositiveInteger("volume", volume);
        if (expectedVersion == null || expectedVersion < 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "expectedVersion 不能为空且必须为非负整数");
        }
        String params = "GATE_EXECUTE|" + allocationKey + "|" + executionKey + "|" + vol + "|"
                + expectedVersion + "|" + actor;
        return runCommand("GATE_EXECUTE", commandKey, params, ExecutionResponse.class, () -> {
            if (repository.findExecutionByKey(executionKey) != null) {
                throw ApiException.conflict("EXECUTION_KEY_REUSED", "executionKey 已被使用: " + executionKey);
            }
            AllocationRow allocation = repository.findAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            // 先锁窗口行，与批准、取消、转让、限供按事务提交顺序串行裁决
            lockWindowOf(allocation);
            // 窗口锁内重读申请，避免与取消/转让并发时使用过期状态与持有额度
            allocation = repository.lockAllocationByKey(allocationKey);
            BasinRow basin = repository.findBasinByWindowId(allocation.windowId());
            if (basin == null) {
                throw ApiException.unprocessable("GATE_NOT_ENABLED", "普通窗口不启用双池规则，不能执行");
            }
            if (basin.version() != expectedVersion) {
                throw ApiException.conflict("VERSION_MISMATCH",
                        "期望版本 " + expectedVersion + " 与当前双池版本 " + basin.version() + " 不一致");
            }
            if (!STATUS_APPROVED.equals(allocation.status())) {
                throw ApiException.unprocessable("ALLOCATION_NOT_APPROVED",
                        "只有 APPROVED 状态的申请可以执行，当前状态: " + allocation.status());
            }
            BigDecimal remaining = allocation.heldAmount().subtract(allocation.executedAmount());
            if (remaining.compareTo(BigDecimal.valueOf(vol)) < 0) {
                throw ApiException.quotaExceeded("累计执行将超过批准量，剩余可执行 " + fmt(remaining));
            }
            // 执行前：双池体积须在界内且水位差不得超过 2 米
            requireBasinStateWithinLimits(basin.upperVolumeM3(), basin.lowerVolumeM3(), basin, "执行前");
            long upperAfter = basin.upperVolumeM3() - vol;
            long lowerAfter = basin.lowerVolumeM3() + vol;
            // 执行后：体积界值与水位差门禁
            requireBasinStateWithinLimits(upperAfter, lowerAfter, basin, "执行后");
            long now = nowNanos();
            repository.updateBasinVolumes(basin.windowId(), upperAfter, lowerAfter, now);
            repository.incrementExecutedAmount(allocation.id(), BigDecimal.valueOf(vol), now);
            long newVersion = basin.version() + 1;
            try {
                repository.insertExecution(executionKey, basin.windowId(), allocationKey, vol,
                        basin.upperVolumeM3(), upperAfter, basin.lowerVolumeM3(), lowerAfter,
                        level(basin.upperVolumeM3(), basin.upperAreaM2()),
                        level(upperAfter, basin.upperAreaM2()),
                        level(basin.lowerVolumeM3(), basin.lowerAreaM2()),
                        level(lowerAfter, basin.lowerAreaM2()),
                        newVersion, actor, now);
            } catch (DuplicateKeyException e) {
                // 并发复用同一 executionKey（换 commandKey）：事务回滚，双池与执行账均不变
                throw ApiException.conflict("EXECUTION_KEY_REUSED", "executionKey 已被使用: " + executionKey);
            }
            return toExecutionResponse(repository.findExecutionByKey(executionKey),
                    remaining.subtract(BigDecimal.valueOf(vol)));
        });
    }

    /** 查询申请的全部执行明细与剩余可执行量。 */
    public ExecutionListResponse listExecutions(String allocationKey) {
        AllocationRow allocation = repository.findAllocationByKey(allocationKey);
        if (allocation == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
        }
        BigDecimal remaining = allocation.heldAmount().subtract(allocation.executedAmount());
        List<ExecutionResponse> executions = repository.listExecutionsByAllocation(allocationKey).stream()
                .map(row -> toExecutionResponse(row, null)).toList();
        return new ExecutionListResponse(allocationKey, allocation.windowId(), allocation.status(),
                fmt(allocation.executedAmount()), fmt(remaining), executions);
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
                fmt(row.heldAmount()), fmt(row.executedAmount()), row.requester(), row.status(),
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

    private BasinResponse toBasinResponse(BasinRow row) {
        return new BasinResponse(row.windowId(), row.upperAreaM2(), row.upperVolumeM3(),
                row.upperMinM3(), row.upperMaxM3(), fmt(level(row.upperVolumeM3(), row.upperAreaM2())),
                row.lowerAreaM2(), row.lowerVolumeM3(), row.lowerMinM3(), row.lowerMaxM3(),
                fmt(level(row.lowerVolumeM3(), row.lowerAreaM2())), row.version(), toIso(row.updatedNanos()));
    }

    /** 执行明细视图；remaining 为该笔执行后的剩余可执行量，列表场景不关心时传 null。 */
    private ExecutionResponse toExecutionResponse(ExecutionRow row, BigDecimal remaining) {
        return new ExecutionResponse(row.executionKey(), row.allocationKey(), row.windowId(), row.volumeM3(),
                row.upperVolumeBeforeM3(), row.upperVolumeAfterM3(),
                fmt(row.upperLevelBeforeM()), fmt(row.upperLevelAfterM()),
                row.lowerVolumeBeforeM3(), row.lowerVolumeAfterM3(),
                fmt(row.lowerLevelBeforeM()), fmt(row.lowerLevelAfterM()),
                row.basinVersion(), remaining == null ? null : fmt(remaining), row.actor(),
                toIso(row.createdNanos()));
    }

    /** 水位 = 体积 / 面积，单位米；按显示精度舍入，仅用于展示与落库，不参与水位门禁。 */
    private static BigDecimal level(long volumeM3, long areaM2) {
        return BigDecimal.valueOf(volumeM3).divide(BigDecimal.valueOf(areaM2), LEVEL_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 水位差门禁：|上池体积/上池面积 - 下池体积/下池面积| <= 2 米。
     * 全部参数为整数，交叉相乘后按整数值精确比较，显示舍入不参与判断。
     */
    private static boolean levelDiffWithin(long upperVolumeM3, long upperAreaM2,
                                           long lowerVolumeM3, long lowerAreaM2) {
        BigInteger left = BigInteger.valueOf(upperVolumeM3).multiply(BigInteger.valueOf(lowerAreaM2))
                .subtract(BigInteger.valueOf(lowerVolumeM3).multiply(BigInteger.valueOf(upperAreaM2))).abs();
        BigInteger right = BigInteger.valueOf(MAX_LEVEL_DIFF_M)
                .multiply(BigInteger.valueOf(upperAreaM2)).multiply(BigInteger.valueOf(lowerAreaM2));
        return left.compareTo(right) <= 0;
    }

    /** 校验给定双池体积在界内且水位差不超过 2 米，否则 422 且整次执行失败。 */
    private static void requireBasinStateWithinLimits(long upperVolumeM3, long lowerVolumeM3,
                                                      BasinRow basin, String phase) {
        if (upperVolumeM3 < basin.upperMinM3() || upperVolumeM3 > basin.upperMaxM3()) {
            throw ApiException.unprocessable("BASIN_BOUND_EXCEEDED",
                    phase + "上池体积 " + upperVolumeM3 + " 越出界值 [" + basin.upperMinM3() + ", "
                            + basin.upperMaxM3() + "]");
        }
        if (lowerVolumeM3 < basin.lowerMinM3() || lowerVolumeM3 > basin.lowerMaxM3()) {
            throw ApiException.unprocessable("BASIN_BOUND_EXCEEDED",
                    phase + "下池体积 " + lowerVolumeM3 + " 越出界值 [" + basin.lowerMinM3() + ", "
                            + basin.lowerMaxM3() + "]");
        }
        if (!levelDiffWithin(upperVolumeM3, basin.upperAreaM2(), lowerVolumeM3, basin.lowerAreaM2())) {
            throw ApiException.unprocessable("WATER_LEVEL_EXCEEDED",
                    phase + "两池水位差超过 " + MAX_LEVEL_DIFF_M + " 米");
        }
    }

    /** 校验登记界值：min <= max 且初始体积在界内。 */
    private static void requireBounds(String basin, long min, long max, long initial) {
        if (max < min) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    basin + " 池体积上界 " + max + " 不得小于下界 " + min);
        }
        if (initial < min || initial > max) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    basin + " 池初始体积 " + initial + " 须在界值 [" + min + ", " + max + "] 内");
        }
    }

    /** 校验正整数（面积等），空或非正返回 400。 */
    private static long requirePositiveLong(String field, Long value) {
        if (value == null || value <= 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 必须为正整数");
        }
        return value;
    }

    /** 校验非负整数（体积与界值），空或为负返回 400。 */
    private static long requireNonNegativeLong(String field, Long value) {
        if (value == null || value < 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 必须为非负整数");
        }
        return value;
    }

    /** 解析正整数水量（执行水量，单位立方米）。 */
    private static long parsePositiveInteger(String field, String value) {
        if (value == null || !INTEGER_PATTERN.matcher(value.trim()).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 必须为正整数: " + value);
        }
        long parsed;
        try {
            parsed = Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 超出正整数范围: " + value);
        }
        if (parsed <= 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 必须为正整数: " + value);
        }
        return parsed;
    }

    static long nowNanos() {
        return toNanos(Instant.now());
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
