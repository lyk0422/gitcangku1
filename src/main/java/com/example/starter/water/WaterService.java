package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.EmergencyWriteOffRow;
import com.example.starter.water.WaterRepository.RegularWriteOffRow;
import com.example.starter.water.WaterRepository.ReserveHistoryRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.EmergencyBatchResponse;
import com.example.starter.water.dto.Dtos.EmergencyWriteOffItem;
import com.example.starter.water.dto.Dtos.EmergencyWriteOffResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.RegularWriteOffResponse;
import com.example.starter.water.dto.Dtos.ReserveHistoryItem;
import com.example.starter.water.dto.Dtos.ReserveStatusResponse;
import com.example.starter.water.dto.Dtos.TransferListResponse;
import com.example.starter.water.dto.Dtos.TransferResponse;
import com.example.starter.water.dto.Dtos.WindowCloseResponse;
import com.example.starter.water.dto.Dtos.WindowResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
            WindowRow window = lockWindowOf(source);
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
            // 储备隔离：常规转让不得使窗口可用常规量低于储备量；同窗口等额转让本身不改变窗口占用，
            // 此处校验窗口常规池未被既有批准与常规核销透支（限供收紧或储备划定后的边界防御）
            ensureRegularPoolNonNegative(window);
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
    // 应急储备与核销
    // ------------------------------------------------------------------

    /**
     * 设置/调整窗口应急储备量。reserveKey 为幂等指纹键（含操作者、窗口版本与数量）；
     * expectedVersion 必须匹配当前窗口储备版本，不匹配返回 409。
     * 储备量最多 3 位小数、不小于 0、不大于窗口总配额；调整（含下调）时回查已批准但未核销的
     * 常规占用（已批准持有额度 + 已常规核销），若其结算后会侵占新储备量返回 422，不部分生效。
     */
    public ReserveStatusResponse setReserve(String reserveKey, long windowId, String reserveVolume,
                                            Integer expectedVersion, String actor) {
        requireKey("reserveKey", reserveKey);
        requireKey("X-Actor-Id", actor);
        if (expectedVersion == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "expectedVersion 不能为空");
        }
        BigDecimal newReserve = parseReserveAmount("reserveVolume", reserveVolume);
        String params = "RESERVE_SET|" + windowId + "|" + expectedVersion + "|"
                + newReserve.toPlainString() + "|" + actor;
        return runCommand("RESERVE_SET", reserveKey, params, ReserveStatusResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            if (window.version() != expectedVersion) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "窗口储备版本不匹配：期望 " + expectedVersion + "，当前 " + window.version());
            }
            if (newReserve.compareTo(window.plannedVolume()) > 0) {
                throw ApiException.badRequest("INVALID_ARGUMENT",
                        "储备量不能大于窗口总配额 " + fmt(window.plannedVolume()));
            }
            if (newReserve.compareTo(window.reserveUsed()) < 0) {
                throw ApiException.unprocessable("RESERVE_BELOW_USED",
                        "储备量不能低于已应急核销量 " + fmt(window.reserveUsed()));
            }
            // 回查已批准但未核销的常规占用后态：占用 > 可用总量 - 新储备量 即结算后侵占新储备
            BigDecimal available = availableTotal(window);
            BigDecimal occupied = repository.sumApprovedAmount(windowId).add(window.regularUsed());
            if (occupied.compareTo(available.subtract(newReserve)) > 0) {
                throw ApiException.unprocessable("RESERVE_CONFLICT",
                        "已批准未核销常规占用 " + fmt(occupied) + " 结算后将侵占新储备量 "
                                + fmt(newReserve) + "（可用总量 " + fmt(available) + "）");
            }
            long now = nowNanos();
            repository.updateReserve(windowId, newReserve, window.version() + 1);
            repository.insertReserveHistory(reserveKey, windowId, actor, window.reserveVolume(),
                    newReserve, window.version() + 1, now);
            return toReserveStatus(repository.findWindowById(windowId));
        });
    }

    /**
     * 常规核销：仅从窗口常规可用量扣减，不得使可用常规量低于储备量；
     * 违反返回 422 并给出常规余额与储备量。
     */
    public RegularWriteOffResponse regularWriteOff(String commandKey, String writeOffKey, long windowId,
                                                   String amount) {
        requireKey("commandKey", commandKey);
        requireKey("writeOffKey", writeOffKey);
        BigDecimal qty = parseAmount("amount", amount);
        String params = "REGULAR_WRITE_OFF|" + writeOffKey + "|" + windowId + "|" + qty.toPlainString();
        return runCommand("REGULAR_WRITE_OFF", commandKey, params, RegularWriteOffResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            if (repository.findRegularWriteOffByKey(writeOffKey) != null) {
                throw ApiException.conflict("WRITE_OFF_KEY_REUSED", "writeOffKey 已被使用: " + writeOffKey);
            }
            BigDecimal regularAvailable = regularAvailable(window);
            if (qty.compareTo(regularAvailable) > 0) {
                throw ApiException.unprocessable("REGULAR_POOL_EXCEEDED",
                        "常规核销 " + fmt(qty) + " 将使可用常规量低于储备量：常规余额 "
                                + fmt(regularAvailable) + "，储备量 " + fmt(window.reserveVolume()));
            }
            long now = nowNanos();
            repository.addRegularUsed(windowId, qty);
            repository.insertRegularWriteOff(writeOffKey, windowId, qty, now);
            return toRegularWriteOffResponse(repository.findRegularWriteOffByKey(writeOffKey));
        });
    }

    /**
     * 单笔应急核销：必须声明 emergencyId 与审批人，仅从储备余额扣减；
     * 同一 emergencyId 在同一窗口只能核销一次；窗口结束后不得新建。
     */
    public EmergencyWriteOffResponse emergencyWriteOff(String commandKey, String writeOffKey, long windowId,
                                                       String emergencyId, String approver, String amount) {
        requireKey("commandKey", commandKey);
        requireKey("writeOffKey", writeOffKey);
        requireKey("emergencyId", emergencyId);
        requireKey("approver", approver);
        BigDecimal qty = parseAmount("amount", amount);
        String params = "EMERGENCY_WRITE_OFF|" + writeOffKey + "|" + windowId + "|" + emergencyId + "|"
                + approver + "|" + qty.toPlainString();
        return runCommand("EMERGENCY_WRITE_OFF", commandKey, params, EmergencyWriteOffResponse.class, () -> {
            WindowRow window = lockOpenWindow(windowId);
            if (repository.findEmergencyWriteOffByKey(writeOffKey) != null) {
                throw ApiException.conflict("WRITE_OFF_KEY_REUSED", "writeOffKey 已被使用: " + writeOffKey);
            }
            ensureEmergencyIdUnused(windowId, emergencyId);
            ensureReserveSufficient(window, qty);
            long now = nowNanos();
            repository.addReserveUsed(windowId, qty);
            insertEmergencyRow(writeOffKey, windowId, emergencyId, approver, qty, null, now);
            return toEmergencyWriteOffResponse(repository.findEmergencyWriteOffByKey(writeOffKey));
        });
    }

    /**
     * 批量应急核销：先校验全部明细的储备余额与审批信息，再单事务扣减；
     * 任一失败整单回滚，不产生部分核销。
     */
    public EmergencyBatchResponse emergencyWriteOffBatch(String commandKey, String batchKey, long windowId,
                                                         List<EmergencyWriteOffItem> items) {
        requireKey("commandKey", commandKey);
        requireKey("batchKey", batchKey);
        if (items == null || items.isEmpty()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "items 不能为空");
        }
        List<BigDecimal> amounts = new ArrayList<>(items.size());
        Set<String> seen = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (EmergencyWriteOffItem item : items) {
            if (item == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "items 明细不能为空");
            }
            requireKey("emergencyId", item.emergencyId());
            requireKey("approver", item.approver());
            BigDecimal qty = parseAmount("amount", item.amount());
            if (!seen.add(item.emergencyId())) {
                throw ApiException.conflict("EMERGENCY_ID_REUSED",
                        "同一批次内应急编号重复: " + item.emergencyId());
            }
            amounts.add(qty);
            total = total.add(qty);
        }
        StringBuilder params = new StringBuilder("EMERGENCY_WRITE_OFF_BATCH|" + batchKey + "|" + windowId);
        for (int i = 0; i < items.size(); i++) {
            params.append('|').append(items.get(i).emergencyId()).append(':')
                    .append(items.get(i).approver()).append(':').append(amounts.get(i).toPlainString());
        }
        BigDecimal batchTotal = total;
        return runCommand("EMERGENCY_WRITE_OFF_BATCH", commandKey, params.toString(),
                EmergencyBatchResponse.class, () -> {
                    WindowRow window = lockOpenWindow(windowId);
                    // 先整体校验：审批信息已在事务外校验，此处校验储备余额与应急编号占用
                    for (EmergencyWriteOffItem item : items) {
                        ensureEmergencyIdUnused(windowId, item.emergencyId());
                    }
                    ensureReserveSufficient(window, batchTotal);
                    long now = nowNanos();
                    repository.addReserveUsed(windowId, batchTotal);
                    List<EmergencyWriteOffResponse> responses = new ArrayList<>(items.size());
                    for (int i = 0; i < items.size(); i++) {
                        String writeOffKey = batchKey + "-" + (i + 1);
                        insertEmergencyRow(writeOffKey, windowId, items.get(i).emergencyId(),
                                items.get(i).approver(), amounts.get(i), batchKey, now);
                        responses.add(toEmergencyWriteOffResponse(
                                repository.findEmergencyWriteOffByKey(writeOffKey)));
                    }
                    return new EmergencyBatchResponse(batchKey, windowId, fmt(batchTotal), responses);
                });
    }

    /** 关闭窗口：关闭后不得新建应急核销，历史储备快照保留。 */
    public WindowCloseResponse closeWindow(String commandKey, long windowId) {
        requireKey("commandKey", commandKey);
        String params = "WINDOW_CLOSE|" + windowId;
        return runCommand("WINDOW_CLOSE", commandKey, params, WindowCloseResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            if (window.closedNanos() != null) {
                throw ApiException.conflict("WINDOW_ALREADY_CLOSED", "窗口已关闭，不能重复关闭");
            }
            long now = nowNanos();
            repository.closeWindow(windowId, now);
            return new WindowCloseResponse(windowId, true, toIso(now));
        });
    }

    /** 查询窗口储备余额、常规可用量、应急核销流水、储备调整历史与阻断原因。 */
    public ReserveStatusResponse getReserveStatus(long windowId) {
        WindowRow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        return toReserveStatus(window);
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

    /** 储备余额 = 储备量 - 已应急核销量。 */
    private BigDecimal reserveBalance(WindowRow window) {
        return window.reserveVolume().subtract(window.reserveUsed());
    }

    /** 常规可用量 = 可用总量 - 储备量 - 已常规核销量；常规转让与常规核销不得使其低于 0（即不低于储备量口径）。 */
    private BigDecimal regularAvailable(WindowRow window) {
        return availableTotal(window).subtract(window.reserveVolume()).subtract(window.regularUsed());
    }

    /** 窗口是否已结束（已关闭或已过结束时刻），结束后不得新建应急核销。 */
    private boolean isWindowEnded(WindowRow window, long nowNanos) {
        return window.closedNanos() != null || nowNanos >= window.endNanos();
    }

    /** 锁定窗口并校验未结束，用于新建应急核销。 */
    private WindowRow lockOpenWindow(long windowId) {
        WindowRow window = repository.lockWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        if (isWindowEnded(window, nowNanos())) {
            throw ApiException.conflict("WINDOW_CLOSED", "窗口已结束，不得新建应急核销");
        }
        return window;
    }

    /** 校验窗口常规池未被既有占用透支（常规转让的储备隔离防线）。 */
    private void ensureRegularPoolNonNegative(WindowRow window) {
        BigDecimal regularAvailable = regularAvailable(window);
        if (regularAvailable.signum() < 0) {
            throw ApiException.unprocessable("REGULAR_POOL_EXCEEDED",
                    "可用常规量已低于储备量：常规余额 " + fmt(regularAvailable)
                            + "，储备量 " + fmt(window.reserveVolume()));
        }
    }

    /** 校验应急编号在同一窗口内尚未核销。 */
    private void ensureEmergencyIdUnused(long windowId, String emergencyId) {
        if (repository.findEmergencyWriteOffByEmergencyId(windowId, emergencyId) != null) {
            throw ApiException.conflict("EMERGENCY_ID_REUSED",
                    "应急编号 " + emergencyId + " 在该窗口已核销，不能重复核销");
        }
    }

    /** 校验储备余额足以覆盖本次应急核销量。 */
    private void ensureReserveSufficient(WindowRow window, BigDecimal amount) {
        BigDecimal balance = reserveBalance(window);
        if (amount.compareTo(balance) > 0) {
            throw ApiException.unprocessable("EMERGENCY_RESERVE_EXCEEDED",
                    "应急核销 " + fmt(amount) + " 超过储备余额 " + fmt(balance));
        }
    }

    /** 插入应急核销流水，同窗口同应急编号的并发重复由唯一约束裁决为 409。 */
    private void insertEmergencyRow(String writeOffKey, long windowId, String emergencyId, String approver,
                                    BigDecimal amount, String batchKey, long now) {
        try {
            repository.insertEmergencyWriteOff(writeOffKey, windowId, emergencyId, approver, amount,
                    batchKey, now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("EMERGENCY_WRITE_OFF_CONFLICT",
                    "应急核销键或应急编号冲突: " + writeOffKey + " / " + emergencyId);
        }
    }

    private ReserveStatusResponse toReserveStatus(WindowRow window) {
        BigDecimal available = availableTotal(window);
        BigDecimal reserveBalance = reserveBalance(window);
        BigDecimal regularAvailable = regularAvailable(window);
        boolean ended = isWindowEnded(window, nowNanos());
        List<String> blockedReasons = new ArrayList<>();
        if (ended) {
            blockedReasons.add("窗口已结束，禁止新建应急核销");
        }
        if (reserveBalance.signum() <= 0) {
            blockedReasons.add("储备余额不足，应急核销将被拒绝");
        }
        if (regularAvailable.signum() <= 0) {
            blockedReasons.add("常规可用量已达储备下限，常规核销将被拒绝");
        }
        List<EmergencyWriteOffResponse> writeOffs = repository.listEmergencyWriteOffs(window.id())
                .stream().map(this::toEmergencyWriteOffResponse).toList();
        List<ReserveHistoryItem> history = repository.listReserveHistory(window.id()).stream()
                .map(this::toReserveHistoryItem).toList();
        return new ReserveStatusResponse(window.id(), fmt(available), fmt(window.reserveVolume()),
                fmt(window.reserveUsed()), fmt(reserveBalance), fmt(window.regularUsed()),
                fmt(regularAvailable), window.version(), ended, blockedReasons, writeOffs, history);
    }

    private ReserveHistoryItem toReserveHistoryItem(ReserveHistoryRow row) {
        return new ReserveHistoryItem(row.reserveKey(), row.actor(), fmt(row.oldVolume()),
                fmt(row.newVolume()), row.version(), toIso(row.createdNanos()));
    }

    private RegularWriteOffResponse toRegularWriteOffResponse(RegularWriteOffRow row) {
        return new RegularWriteOffResponse(row.writeOffKey(), row.windowId(), fmt(row.amount()),
                toIso(row.createdNanos()));
    }

    private EmergencyWriteOffResponse toEmergencyWriteOffResponse(EmergencyWriteOffRow row) {
        return new EmergencyWriteOffResponse(row.writeOffKey(), row.windowId(), row.emergencyId(),
                row.approver(), fmt(row.amount()), row.batchKey(), toIso(row.createdNanos()));
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

    /** 解析储备量：十进制字符串，不小于 0（允许 0 表示取消储备），最多 3 位小数。 */
    static BigDecimal parseReserveAmount(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不能为空");
        }
        String trimmed = value.trim();
        if (!AMOUNT_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 必须为不小于 0 的十进制字符串，最多 3 位小数: " + value);
        }
        return new BigDecimal(trimmed);
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
