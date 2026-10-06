package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.FieldRow;
import com.example.starter.water.WaterRepository.SaltLedgerRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.FieldResponse;
import com.example.starter.water.dto.Dtos.FieldSaltResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.IrrigationResponse;
import com.example.starter.water.dto.Dtos.SaltLedgerEntryResponse;
import com.example.starter.water.dto.Dtos.TransferListResponse;
import com.example.starter.water.dto.Dtos.TransferResponse;
import com.example.starter.water.dto.Dtos.WindowResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
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
    private static final Pattern CONCENTRATION_PATTERN = Pattern.compile("\\d{1,10}(\\.\\d{1,3})?");
    private static final Pattern SALT_CAP_PATTERN = Pattern.compile("\\d{1,18}");
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

    /** 创建农户田块：盐量上限为不可变正整数克；操作人（actor）非空并计入幂等参数。 */
    public FieldResponse createField(String commandKey, String fieldKey, String farmerId,
                                     String saltCapGrams, Long expectedVersion, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("fieldKey", fieldKey);
        requireKey("farmerId", farmerId);
        requireKey("X-Actor-Id", actor);
        long cap = parseSaltCap(saltCapGrams);
        String params = "FIELD_CREATE|" + fieldKey + "|" + farmerId + "|" + cap + "|" + ver(expectedVersion)
                + "|" + actor;
        return runCommand("FIELD_CREATE", commandKey, params, FieldResponse.class, () -> {
            if (repository.findFieldByKey(fieldKey) != null) {
                throw ApiException.conflict("FIELD_KEY_REUSED", "fieldKey 已被使用: " + fieldKey);
            }
            long id;
            try {
                id = repository.insertField(fieldKey, farmerId, cap, nowNanos());
            } catch (DuplicateKeyException e) {
                // 并发复用同一 fieldKey（换 commandKey）：事务回滚，不产生重复田块
                throw ApiException.conflict("FIELD_KEY_REUSED", "fieldKey 已被使用: " + fieldKey);
            }
            return toFieldResponse(repository.findFieldById(id));
        });
    }

    /** 提交配水申请，申请人为 actor；可绑定田块及浓度（二者同时提供或同时缺省）。 */
    public AllocationResponse submitAllocation(String commandKey, String allocationKey, Long windowId,
                                               String userId, String amount, Long fieldId,
                                               String concentration, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        requireKey("userId", userId);
        requireKey("X-Actor-Id", actor);
        if (windowId == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "windowId 不能为空");
        }
        BigDecimal qty = parseAmount("amount", amount);
        BigDecimal conc;
        if (fieldId != null || concentration != null) {
            if (fieldId == null || concentration == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "fieldId 与 concentration 必须同时提供");
            }
            conc = parseConcentration("concentration", concentration);
        } else {
            conc = null;
        }
        String params = "ALLOCATION_SUBMIT|" + allocationKey + "|" + windowId + "|" + userId + "|"
                + qty.toPlainString() + "|" + fieldId + "|" + (conc == null ? null : fmt(conc)) + "|" + actor;
        return runCommand("ALLOCATION_SUBMIT", commandKey, params, AllocationResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            if (fieldId != null) {
                FieldRow field = repository.findFieldById(fieldId);
                if (field == null) {
                    throw ApiException.notFound("FIELD_NOT_FOUND", "田块不存在: " + fieldId);
                }
                if (!field.farmerId().equals(userId)) {
                    throw ApiException.forbidden("FIELD_NOT_OWNED", "田块 " + fieldId + " 不属于农户 " + userId);
                }
            }
            long id = repository.insertAllocation(allocationKey, windowId, userId, qty, fieldId, conc, actor,
                    nowNanos());
            return toAllocationResponse(repository.findAllocationByKey(allocationKey));
        });
    }

    /** 批准申请：加入本申请后已批准总量不得超过当前可用总量，绑定田块时累计实灌加全部预留不得超盐上限。 */
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
            // 窗口锁后按统一顺序锁田块（fieldId 创建后不可变），串行化同田块盐预算裁决
            FieldRow field = lockFieldOf(allocation);
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
            if (field != null) {
                verifyFieldOwnership(field, allocation);
                // 预留盐 = 批准水量乘浓度的精确乘积（不先舍入）；累计实灌 + 全部预留 + 本预留不得超限
                checkSaltBudget(field, BigDecimal.ZERO,
                        allocation.amount().multiply(allocation.concentration()), "批准");
            }
            repository.updateAllocationStatus(allocation.id(), STATUS_APPROVED, nowNanos());
            return toAllocationResponse(repository.findAllocationByKey(allocationKey));
        });
    }

    /** 取消申请：仅申请人本人可取消 REQUESTED/APPROVED；仅释放未灌盐量与相应未灌水额度，已灌部分保留。 */
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
            FieldRow field = lockFieldOf(allocation);
            // 窗口锁内重读申请，避免与转让/并发取消使用过期状态
            allocation = repository.lockAllocationByKey(allocationKey);
            if (field != null) {
                verifyFieldOwnership(field, allocation);
            }
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
     * 实际灌入：对已批准申请提交正水量（不超过未灌量），等量预留盐转为不可逆实灌盐并冻结入账。
     * 仅申请人本人可灌；未绑定田块的旧流程申请只累计已灌水量、不记盐账。
     */
    public IrrigationResponse irrigateAllocation(String commandKey, String allocationKey, String amount,
                                                 Long expectedVersion, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        requireKey("X-Actor-Id", actor);
        BigDecimal qty = parseAmount("amount", amount);
        String params = "ALLOCATION_IRRIGATE|" + allocationKey + "|" + fmt(qty) + "|" + ver(expectedVersion)
                + "|" + actor;
        return runCommand("ALLOCATION_IRRIGATE", commandKey, params, IrrigationResponse.class, () -> {
            AllocationRow allocation = repository.findAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            lockWindowOf(allocation);
            FieldRow field = lockFieldOf(allocation);
            // 窗口锁内重读申请，避免与取消/转让并发时使用过期持有与已灌量
            allocation = repository.lockAllocationByKey(allocationKey);
            if (!allocation.requester().equals(actor)) {
                throw ApiException.forbidden("NOT_OWNER", "只有申请人本人可以登记实灌");
            }
            if (!STATUS_APPROVED.equals(allocation.status())) {
                throw ApiException.conflict("ALLOCATION_NOT_APPROVED", "只有 APPROVED 状态的申请可以实灌");
            }
            BigDecimal unirrigated = allocation.heldAmount().subtract(allocation.irrigatedAmount());
            if (qty.compareTo(unirrigated) > 0) {
                throw ApiException.quotaExceeded("实灌水量 " + fmt(qty) + " 超过未灌量 " + fmt(unirrigated));
            }
            long now = nowNanos();
            repository.addIrrigatedAmount(allocation.id(), qty, now);
            BigDecimal saltGrams = null;
            BigDecimal cumulative = null;
            if (field != null) {
                verifyFieldOwnership(field, allocation);
                // 精确乘积不先舍入；批准时已保证累计实灌 + 全部预留不超上限，实灌等额转化预留
                saltGrams = qty.multiply(allocation.concentration());
                cumulative = repository.sumIrrigatedSalt(field.id()).add(saltGrams);
                repository.insertSaltLedger(field.id(), allocationKey, allocation.windowId(),
                        allocation.concentration(), qty, saltGrams, cumulative, actor, now);
            }
            AllocationRow updated = repository.findAllocationByKey(allocationKey);
            return new IrrigationResponse(allocationKey, updated.windowId(), updated.fieldId(), fmt(qty),
                    fmt(updated.irrigatedAmount()),
                    updated.concentration() == null ? null : fmt(updated.concentration()),
                    saltGrams == null ? null : fmt(saltGrams),
                    cumulative == null ? null : fmt(cumulative), actor, toIso(now));
        });
    }

    /** 查询田块盐负荷：累计实灌盐、全部预留盐、余量及不可变账本明细。 */
    public FieldSaltResponse getFieldSalt(long fieldId) {
        FieldRow field = repository.findFieldById(fieldId);
        if (field == null) {
            throw ApiException.notFound("FIELD_NOT_FOUND", "田块不存在: " + fieldId);
        }
        BigDecimal irrigated = repository.sumIrrigatedSalt(fieldId);
        BigDecimal reserved = reservedSalt(fieldId);
        BigDecimal remaining = BigDecimal.valueOf(field.saltCapGrams()).subtract(irrigated).subtract(reserved);
        List<SaltLedgerEntryResponse> entries = repository.listSaltLedger(fieldId).stream()
                .map(this::toLedgerEntryResponse).toList();
        return new FieldSaltResponse(field.id(), field.fieldKey(), field.farmerId(),
                String.valueOf(field.saltCapGrams()), fmt(irrigated), fmt(reserved), fmt(remaining), entries);
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
            // 窗口锁内按田块主键顺序锁定双方田块（避免交叉死锁），fieldId 创建后不可变
            FieldRow sourceField;
            FieldRow targetField;
            if (source.fieldId() != null && target.fieldId() != null
                    && target.fieldId() < source.fieldId()) {
                targetField = lockFieldOf(target);
                sourceField = lockFieldOf(source);
            } else {
                sourceField = lockFieldOf(source);
                targetField = lockFieldOf(target);
            }
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
            // 共同核对农户与田块归属（数据一致性复核，归属错误 403）
            if (sourceField != null) {
                verifyFieldOwnership(sourceField, lockedSource);
            }
            if (targetField != null) {
                verifyFieldOwnership(targetField, lockedTarget);
            }
            BigDecimal amount = lockedTarget.amount();
            // 已灌部分不能转让：可转让额度 = 持有额度 - 已灌水量
            BigDecimal transferable = lockedSource.heldAmount().subtract(lockedSource.irrigatedAmount());
            if (transferable.compareTo(amount) < 0) {
                throw ApiException.quotaExceeded("源申请未灌持有额度 " + fmt(transferable)
                        + " 不足，无法转让 " + fmt(amount));
            }
            if (targetField != null) {
                // 整次转让重新核对两侧浓度与盐预算，不凭来源总水量推断盐量相同：
                // 目标按自身浓度新增预留；源若同田块则等额预留释放计入差额
                BigDecimal reservedDelta = BigDecimal.ZERO;
                if (sourceField != null && lockedSource.fieldId().equals(lockedTarget.fieldId())) {
                    reservedDelta = amount.multiply(lockedSource.concentration()).negate();
                }
                checkSaltBudget(targetField, reservedDelta,
                        amount.multiply(lockedTarget.concentration()), "转让");
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

    /** 申请绑定田块时锁定田块行（FOR UPDATE），未绑定返回 null。 */
    private FieldRow lockFieldOf(AllocationRow allocation) {
        if (allocation.fieldId() == null) {
            return null;
        }
        FieldRow field = repository.lockFieldById(allocation.fieldId());
        if (field == null) {
            throw ApiException.notFound("FIELD_NOT_FOUND", "田块不存在: " + allocation.fieldId());
        }
        return field;
    }

    /** 核对农户与田块归属：田块农户必须等于申请用水户，否则 403。 */
    private void verifyFieldOwnership(FieldRow field, AllocationRow allocation) {
        if (!field.farmerId().equals(allocation.userId())) {
            throw ApiException.forbidden("FIELD_NOT_OWNED",
                    "田块 " + field.id() + " 不属于农户 " + allocation.userId());
        }
    }

    /** 田块当前全部预留盐（克）：APPROVED 绑定申请的未灌持有水量乘浓度，Java 侧精确求和。 */
    private BigDecimal reservedSalt(long fieldId) {
        BigDecimal total = BigDecimal.ZERO;
        for (AllocationRow row : repository.listApprovedBoundForField(fieldId)) {
            total = total.add(row.heldAmount().subtract(row.irrigatedAmount()).multiply(row.concentration()));
        }
        return total;
    }

    /** 盐预算校验：累计实灌 + 当前全部预留 + 预留差额 + 新增预留不得超过田块上限，否则 422。 */
    private void checkSaltBudget(FieldRow field, BigDecimal reservedDelta, BigDecimal newReservation,
                                 String operation) {
        BigDecimal total = repository.sumIrrigatedSalt(field.id())
                .add(reservedSalt(field.id())).add(reservedDelta).add(newReservation);
        if (total.compareTo(BigDecimal.valueOf(field.saltCapGrams())) > 0) {
            throw ApiException.quotaExceeded(operation + "后田块累计实灌加全部预留盐 " + fmt(total)
                    + " 克将超过上限 " + field.saltCapGrams() + " 克");
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
                fmt(row.heldAmount()), fmt(row.irrigatedAmount()), row.fieldId(),
                row.concentration() == null ? null : fmt(row.concentration()), row.requester(), row.status(),
                toIso(row.createdNanos()), toIso(row.updatedNanos()));
    }

    private FieldResponse toFieldResponse(FieldRow row) {
        return new FieldResponse(row.id(), row.fieldKey(), row.farmerId(),
                String.valueOf(row.saltCapGrams()), toIso(row.createdNanos()));
    }

    private SaltLedgerEntryResponse toLedgerEntryResponse(SaltLedgerRow row) {
        return new SaltLedgerEntryResponse(row.id(), row.allocationKey(), row.windowId(),
                fmt(row.concentration()), fmt(row.waterAmount()), fmt(row.saltGrams()),
                fmt(row.cumulativeSaltGrams()), row.actor(), toIso(row.createdNanos()));
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

    /** 解析盐浓度：非负十进制字符串（允许 0），最多 3 位小数，单位克每立方米。 */
    static BigDecimal parseConcentration(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不能为空");
        }
        String trimmed = value.trim();
        if (!CONCENTRATION_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 必须为非负十进制字符串，最多 3 位小数: " + value);
        }
        return new BigDecimal(trimmed);
    }

    /** 解析盐量上限：正整数克。 */
    static long parseSaltCap(String value) {
        if (value == null || value.isBlank() || !SALT_CAP_PATTERN.matcher(value.trim()).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "saltCapGrams 必须为正整数（单位克）: " + value);
        }
        long cap = Long.parseLong(value.trim());
        if (cap <= 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "saltCapGrams 必须为正整数（单位克）");
        }
        return cap;
    }

    /** 期望版本进入幂等参数：null 归一化为占位符，文本原样比较。 */
    private static String ver(Long expectedVersion) {
        return expectedVersion == null ? "-" : expectedVersion.toString();
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
