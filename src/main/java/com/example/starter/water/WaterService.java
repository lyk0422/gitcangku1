package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.DroughtDetailRow;
import com.example.starter.water.WaterRepository.DroughtRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.DroughtDetailResponse;
import com.example.starter.water.dto.Dtos.DroughtHistoryResponse;
import com.example.starter.water.dto.Dtos.DroughtResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.TransferListResponse;
import com.example.starter.water.dto.Dtos.TransferResponse;
import com.example.starter.water.dto.Dtos.WindowDroughtResponse;
import com.example.starter.water.dto.Dtos.WindowResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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

    static final String LEVEL_NONE = "NONE";
    static final String LEVEL_1 = "LEVEL1";
    static final String LEVEL_2 = "LEVEL2";
    static final String LEVEL_3 = "LEVEL3";
    static final List<String> LEVELS = List.of(LEVEL_NONE, LEVEL_1, LEVEL_2, LEVEL_3);

    static final String PRIORITY_ESSENTIAL = "ESSENTIAL";
    static final String PRIORITY_NORMAL = "NORMAL";
    static final String PRIORITY_DEFERRABLE = "DEFERRABLE";
    static final List<String> PRIORITIES = List.of(PRIORITY_ESSENTIAL, PRIORITY_NORMAL, PRIORITY_DEFERRABLE);

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("\\d{1,16}(\\.\\d{1,3})?");
    private static final Pattern KEY_PATTERN = Pattern.compile("[\\w.\\-:]{1,128}");

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

    /** 提交配水申请，申请人为 actor；priority 缺省 NORMAL。 */
    public AllocationResponse submitAllocation(String commandKey, String allocationKey, Long windowId,
                                               String userId, String amount, String priority, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        requireKey("userId", userId);
        requireKey("X-Actor-Id", actor);
        if (windowId == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "windowId 不能为空");
        }
        BigDecimal qty = parseAmount("amount", amount);
        String normalizedPriority = normalizePriority(priority);
        String params = "ALLOCATION_SUBMIT|" + allocationKey + "|" + windowId + "|" + userId + "|"
                + qty.toPlainString() + "|" + normalizedPriority + "|" + actor;
        return runCommand("ALLOCATION_SUBMIT", commandKey, params, AllocationResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            repository.insertAllocation(allocationKey, windowId, userId, normalizedPriority, qty, actor,
                    nowNanos());
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

    /**
     * 声明旱情等级：在窗口行锁事务内按优先级比例削减（或回补）全部 APPROVED 申请的当前持有额度。
     * 目标额度 = 旱情基线持有额度 × (100 - 百分比) / 100，3 位小数 HALF_UP；同级取整差额由
     * 申请标识升序首笔承担，使该级削减后总量恰好等于该级基线总量的同式取整值。
     * 升级按最新百分比在基线上重算，降级/恢复 NONE 按基线回补；回补后任一申请超过原申请水量
     * 或窗口已批准总量超过可用总量则整次 422 且额度不变。
     */
    public DroughtResponse declareDrought(String commandKey, String curtailmentKey, long windowId,
                                          String level, Integer essentialPct, Integer normalPct,
                                          Integer deferrablePct, Long expectedVersion) {
        requireKey("commandKey", commandKey);
        requireKey("curtailmentKey", curtailmentKey);
        String normalizedLevel = normalizeLevel(level);
        int essential = requirePct("essentialPct", essentialPct);
        int normal = requirePct("normalPct", normalPct);
        int deferrable = requirePct("deferrablePct", deferrablePct);
        if (!(essential <= normal && normal <= deferrable)) {
            throw ApiException.unprocessable("RATIO_ORDER_INVALID",
                    "削减百分比须满足 ESSENTIAL<=NORMAL<=DEFERRABLE");
        }
        if (expectedVersion == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "expectedVersion 不能为空");
        }
        String params = "DROUGHT_DECLARE|" + curtailmentKey + "|" + windowId + "|" + normalizedLevel + "|"
                + essential + "|" + normal + "|" + deferrable + "|" + expectedVersion;
        return runCommand("DROUGHT_DECLARE", commandKey, params, DroughtResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            if (repository.findDroughtByKey(curtailmentKey) != null) {
                throw ApiException.conflict("DROUGHT_KEY_REUSED",
                        "curtailmentKey 已被使用: " + curtailmentKey);
            }
            if (window.version() != expectedVersion) {
                throw ApiException.conflict("VERSION_CONFLICT", "expectedVersion " + expectedVersion
                        + " 与窗口当前版本 " + window.version() + " 不一致");
            }
            List<AllocationRow> approved = repository.listApprovedAllocations(windowId);
            List<BigDecimal> targets = LEVEL_NONE.equals(normalizedLevel)
                    ? approved.stream().map(AllocationRow::baseHeldAmount).toList()
                    : curtailTargets(approved, essential, normal, deferrable);
            // 越界校验：任一申请超过原申请水量，或窗口已批准总量超过可用总量，整次 422 且额度不变
            BigDecimal newTotal = BigDecimal.ZERO;
            for (int i = 0; i < approved.size(); i++) {
                BigDecimal target = targets.get(i);
                if (target.signum() < 0 || target.compareTo(approved.get(i).amount()) > 0) {
                    throw ApiException.unprocessable("ALLOCATION_EXCEEDS_AMOUNT",
                            "调整后申请 " + approved.get(i).allocationKey() + " 持有额度越界: " + fmt(target));
                }
                newTotal = newTotal.add(target);
            }
            BigDecimal available = availableTotal(window);
            if (newTotal.compareTo(available) > 0) {
                throw ApiException.unprocessable("APPROVED_EXCEEDS_AVAILABLE",
                        "调整后已批准总量 " + fmt(newTotal) + " 将超过可用总量 " + fmt(available));
            }
            long now = nowNanos();
            for (int i = 0; i < approved.size(); i++) {
                if (approved.get(i).heldAmount().compareTo(targets.get(i)) != 0) {
                    repository.setHeldAmount(approved.get(i).id(), targets.get(i), now);
                }
            }
            long newVersion = window.version() + 1;
            repository.updateWindowDrought(windowId, normalizedLevel, newVersion);
            long droughtId;
            try {
                droughtId = repository.insertDrought(curtailmentKey, windowId, normalizedLevel, essential,
                        normal, deferrable, newVersion, now);
            } catch (DuplicateKeyException e) {
                // 并发复用同一 curtailmentKey（换 commandKey）：事务回滚，额度无变化
                throw ApiException.conflict("DROUGHT_KEY_REUSED",
                        "curtailmentKey 已被使用: " + curtailmentKey);
            }
            List<DroughtDetailResponse> details = new ArrayList<>();
            for (int i = 0; i < approved.size(); i++) {
                AllocationRow allocation = approved.get(i);
                repository.insertDroughtDetail(droughtId, allocation.allocationKey(), allocation.priority(),
                        allocation.heldAmount(), targets.get(i));
                details.add(new DroughtDetailResponse(allocation.allocationKey(), allocation.priority(),
                        fmt(allocation.heldAmount()), fmt(targets.get(i))));
            }
            return new DroughtResponse(droughtId, curtailmentKey, windowId, normalizedLevel, essential,
                    normal, deferrable, newVersion, toIso(now), details);
        });
    }

    /** 查询窗口当前旱情等级、版本号与最近一次声明（含明细）。 */
    public WindowDroughtResponse getWindowDrought(long windowId) {
        WindowRow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        DroughtRow latest = repository.findLatestDrought(windowId);
        return new WindowDroughtResponse(windowId, window.droughtLevel(), window.version(),
                latest == null ? null : toDroughtResponse(latest));
    }

    /** 查询窗口全部旱情削减声明历史（含明细），按声明顺序返回。 */
    public DroughtHistoryResponse getDroughtHistory(long windowId) {
        WindowRow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        List<DroughtResponse> curtailments = repository.listDroughts(windowId).stream()
                .map(this::toDroughtResponse).toList();
        return new DroughtHistoryResponse(windowId, curtailments);
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
                fmt(available.subtract(approved)), window.droughtLevel());
    }

    /** 查询窗口历史明细：窗口 + 全部申请 + 全部限供 + 全部旱情削减声明。 */
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
        List<DroughtResponse> droughts = repository.listDroughts(windowId).stream()
                .map(this::toDroughtResponse).toList();
        return new HistoryResponse(toWindowResponse(window, active), allocations, curtailments, droughts);
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
                active != null ? fmt(active.volume()) : null, fmt(available), row.droughtLevel(),
                row.version(), toIso(row.createdNanos()));
    }

    private AllocationResponse toAllocationResponse(AllocationRow row) {
        return new AllocationResponse(row.allocationKey(), row.windowId(), row.userId(), row.priority(),
                fmt(row.amount()), fmt(row.heldAmount()), row.requester(), row.status(),
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

    private DroughtResponse toDroughtResponse(DroughtRow row) {
        List<DroughtDetailResponse> details = repository.listDroughtDetails(row.id()).stream()
                .map(d -> new DroughtDetailResponse(d.allocationKey(), d.priority(),
                        fmt(d.previousHeld()), fmt(d.newHeld())))
                .toList();
        return new DroughtResponse(row.id(), row.curtailmentKey(), row.windowId(), row.level(),
                row.essentialPct(), row.normalPct(), row.deferrablePct(), row.windowVersion(),
                toIso(row.createdNanos()), details);
    }

    /** 旱情目标额度：基线 × (100 - 百分比) / 100，3 位小数 HALF_UP。 */
    static BigDecimal curtailTarget(BigDecimal base, int pct) {
        return base.multiply(BigDecimal.valueOf(100L - pct)).divide(HUNDRED, 3, RoundingMode.HALF_UP);
    }

    /**
     * 计算各 APPROVED 申请削减后持有额度：逐笔按优先级比例取整后，同级总量与
     * 该级基线总量同式取整值的差额由该级申请标识升序首笔承担（输入已按申请标识升序）。
     */
    static List<BigDecimal> curtailTargets(List<AllocationRow> approved, int essentialPct, int normalPct,
                                           int deferrablePct) {
        List<BigDecimal> targets = new ArrayList<>(approved.size());
        for (AllocationRow allocation : approved) {
            targets.add(curtailTarget(allocation.baseHeldAmount(),
                    pctOf(allocation.priority(), essentialPct, normalPct, deferrablePct)));
        }
        for (String priority : PRIORITIES) {
            int pct = pctOf(priority, essentialPct, normalPct, deferrablePct);
            BigDecimal levelBase = BigDecimal.ZERO;
            BigDecimal levelSum = BigDecimal.ZERO;
            int first = -1;
            for (int i = 0; i < approved.size(); i++) {
                if (!priority.equals(approved.get(i).priority())) {
                    continue;
                }
                if (first < 0) {
                    first = i;
                }
                levelBase = levelBase.add(approved.get(i).baseHeldAmount());
                levelSum = levelSum.add(targets.get(i));
            }
            if (first < 0) {
                continue;
            }
            BigDecimal delta = curtailTarget(levelBase, pct).subtract(levelSum);
            if (delta.signum() != 0) {
                targets.set(first, targets.get(first).add(delta));
            }
        }
        return targets;
    }

    private static int pctOf(String priority, int essentialPct, int normalPct, int deferrablePct) {
        return switch (priority) {
            case PRIORITY_ESSENTIAL -> essentialPct;
            case PRIORITY_DEFERRABLE -> deferrablePct;
            default -> normalPct;
        };
    }

    private static String normalizeLevel(String level) {
        if (level == null || !LEVELS.contains(level.trim())) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "level 必须为 NONE/LEVEL1/LEVEL2/LEVEL3: " + level);
        }
        return level.trim();
    }

    private static String normalizePriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return PRIORITY_NORMAL;
        }
        String trimmed = priority.trim();
        if (!PRIORITIES.contains(trimmed)) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "priority 必须为 ESSENTIAL/NORMAL/DEFERRABLE: " + priority);
        }
        return trimmed;
    }

    private static int requirePct(String field, Integer pct) {
        if (pct == null || pct < 0 || pct > 100) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 必须为 0~100 的整数");
        }
        return pct;
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
