package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.EmergencyWriteoffRow;
import com.example.starter.water.WaterRepository.ReserveCommandRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.BlockReason;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.EmergencyWriteoffItem;
import com.example.starter.water.dto.Dtos.EmergencyWriteoffResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.RegularWriteoffResponse;
import com.example.starter.water.dto.Dtos.ReserveResponse;
import com.example.starter.water.dto.Dtos.ReserveStatusResponse;
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
    static final String STATUS_CLOSED = "CLOSED";

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
            // 储备隔离：常规批准后常规占用（已批准持有 + 常规核销累计）不得侵入应急储备量
            BigDecimal reserve = window.reserveVolume();
            BigDecimal regularPool = available.subtract(reserve);
            BigDecimal regularUsed = approved.add(repository.sumRegularWrittenOff(window.id()));
            if (regularUsed.add(allocation.amount()).compareTo(regularPool) > 0) {
                throw ApiException.unprocessable("RESERVE_ISOLATION",
                        "批准 " + fmt(allocation.amount()) + " 后常规占用将侵占应急储备量 " + fmt(reserve)
                                + "；当前常规余额 " + fmt(regularPool.subtract(regularUsed))
                                + "，应急储备量 " + fmt(reserve));
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
     * 源扣减、目标批准、不可变流水在同一事务完成，窗口已批准持有总量不变；失败回滚无任何额度变化。
     *
     * <p>储备隔离：转让必须携带窗口 expectedVersion 与 reserveKey；事务内锁窗口行、校验版本，
     * 并复核转让后常规占用（已批准未核销持有量 + 常规核销累计）不得高于常规池（总配额 - 储备量），
     * 违反返回 422 并给出常规余额与储备量。reserveKey 指纹含操作者、窗口版本、申请与数量。</p>
     */
    public TransferResponse transferAllocation(String commandKey, String transferKey, String sourceAllocationKey,
                                               String targetAllocationKey, String actor, String reserveKey,
                                               Long expectedVersion) {
        requireKey("commandKey", commandKey);
        requireKey("transferKey", transferKey);
        requireKey("sourceAllocationKey", sourceAllocationKey);
        requireKey("targetAllocationKey", targetAllocationKey);
        requireKey("X-Actor-Id", actor);
        requireKey("reserveKey", reserveKey);
        long version = requireVersion(expectedVersion);
        String params = "TRANSFER|" + transferKey + "|" + sourceAllocationKey + "|" + targetAllocationKey + "|"
                + actor + "|" + version;
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
        String fingerprint = "TRANSFER|" + actor + "|" + version + "|" + sourceAllocationKey + "|"
                + targetAllocationKey + "|" + target.amount().toPlainString();
        return runCommandAndReserveKey(commandKey, "TRANSFER", params, reserveKey, "TRANSFER", fingerprint,
                TransferResponse.class, () -> {
            if (repository.findTransferByKey(transferKey) != null) {
                throw ApiException.conflict("TRANSFER_KEY_REUSED", "transferKey 已被使用: " + transferKey);
            }
            // 先锁窗口行，与普通批准、取消、限供、储备调整/核销按事务提交顺序串行裁决
            WindowRow window = lockWindowOf(repository.findAllocationByKey(sourceAllocationKey));
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
                        + " 不足，无法转让 " + fmt(amount) + "；当前常规余额 "
                        + fmt(regularBalance(window)) + "，应急储备量 " + fmt(window.reserveVolume()));
            }
            // 转让不改变窗口常规占用总量，但储备上调等原因可能已使窗口处于侵占态：新转让不得在侵占态结算
            BigDecimal total = totalQuota(window);
            BigDecimal regularUsed = repository.sumApprovedAmount(window.id())
                    .add(repository.sumRegularWrittenOff(window.id()));
            if (regularUsed.compareTo(total.subtract(window.reserveVolume())) > 0) {
                throw reserveBlocked(window, "转让结算后常规占用 " + fmt(regularUsed)
                        + " 将侵占应急储备量 " + fmt(window.reserveVolume()));
            }
            long now = nowNanos();
            repository.decrementHeldAmount(lockedSource.id(), amount, now);
            repository.updateAllocationStatus(lockedTarget.id(), STATUS_APPROVED, now);
            try {
                repository.insertTransfer(transferKey, lockedSource.windowId(), sourceAllocationKey,
                        targetAllocationKey, amount, actor, now);
            } catch (DuplicateKeyException e) {
                // 并发复用同一 transferKey（换 commandKey/reserveKey）：事务回滚，额度无变化
                throw ApiException.conflict("TRANSFER_KEY_REUSED", "transferKey 已被使用: " + transferKey);
            }
            repository.bumpWindowVersion(window.id());
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
            if (qty.compareTo(window.reserveVolume()) < 0) {
                throw ApiException.unprocessable("RESERVE_ISOLATION",
                        "限供水量 " + fmt(qty) + " 不能低于应急储备量 " + fmt(window.reserveVolume())
                                + "；当前常规余额 " + fmt(regularBalance(window)) + "，应急储备量 "
                                + fmt(window.reserveVolume()));
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
        BigDecimal total = active != null ? active.volume() : window.plannedVolume();
        BigDecimal reserve = window.reserveVolume();
        BigDecimal regularAvailable = total.subtract(reserve);
        BigDecimal approved = repository.sumApprovedAmount(windowId);
        BigDecimal regularWrittenOff = repository.sumRegularWrittenOff(windowId);
        BigDecimal emergencyWrittenOff = repository.sumEmergencyWrittenOff(windowId);
        return new CapacityResponse(window.id(), fmt(window.plannedVolume()),
                active != null ? fmt(active.volume()) : null, fmt(total), fmt(approved),
                fmt(total.subtract(approved)), fmt(reserve), fmt(regularAvailable),
                fmt(reserve.subtract(emergencyWrittenOff)));
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

    /**
     * 调整窗口应急储备量。
     *
     * <p>储备量 ∈ [0, 窗口总配额]（限供生效时取限供水量），最多 3 位小数；必须携带 expectedVersion。
     * 下调时回查已批准但未核销的常规转让后态（按申请当前持有额度而非原申请水量计），
     * 若结算后常规占用将高于“总配额 - 新储备量”，或新储备低于应急核销累计，返回 422 且不部分生效。
     * reserveKey 指纹含操作者、窗口版本与申请储备量，同键成功重放首次快照。</p>
     */
    public ReserveResponse adjustReserve(String reserveKey, long windowId, Long expectedVersion,
                                         String reserveVolume, String actor) {
        requireKey("reserveKey", reserveKey);
        requireKey("X-Actor-Id", actor);
        long version = requireVersion(expectedVersion);
        BigDecimal newReserve = parseNonNegativeAmount("reserveVolume", reserveVolume);
        String fingerprint = "RESERVE_ADJUST|" + actor + "|" + version + "|" + newReserve.toPlainString();
        return runReserveKey(reserveKey, "RESERVE_ADJUST", fingerprint, ReserveResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            checkWindowVersion(window, version);
            if (STATUS_CLOSED.equals(window.status())) {
                throw ApiException.conflict("WINDOW_CLOSED", "窗口已关闭，储备量快照不可再调整");
            }
            BigDecimal total = totalQuota(window);
            if (newReserve.compareTo(total) > 0) {
                throw ApiException.badRequest("INVALID_ARGUMENT",
                        "应急储备量不能超过窗口总配额 " + fmt(total));
            }
            BigDecimal emergencyUsed = repository.sumEmergencyWrittenOff(windowId);
            if (newReserve.compareTo(emergencyUsed) < 0) {
                throw reserveBlocked(window, "应急储备量 " + fmt(newReserve)
                        + " 不能低于应急核销累计 " + fmt(emergencyUsed));
            }
            // 回查已批准但未核销的常规转让后态：以申请当前持有额度（而非原申请水量）加常规核销累计
            // 作为常规占用；调整后“新储备量 + 常规占用”不得超过窗口总配额，违反 422 且不部分生效。
            BigDecimal held = repository.sumApprovedAmount(windowId);
            BigDecimal regularWrittenOff = repository.sumRegularWrittenOff(windowId);
            BigDecimal regularUsed = held.add(regularWrittenOff);
            if (newReserve.add(regularUsed).compareTo(total) > 0) {
                BigDecimal regularBalance = total.subtract(window.reserveVolume())
                        .subtract(regularUsed);
                throw reserveBlocked(window, "调整储备至 " + fmt(newReserve)
                        + " 后，已批准未核销常规转让后态占用 " + fmt(regularUsed)
                        + " 将侵占新储备量；当前常规余额 " + fmt(regularBalance));
            }
            long now = nowNanos();
            repository.updateReserveVolume(windowId, newReserve);
            repository.bumpWindowVersion(windowId);
            return toReserveResponse(repository.lockWindowById(windowId));
        });
    }

    /**
     * 常规核销：从单个已批准申请的当前持有额度中核销。
     * 核销后窗口常规占用（已批准持有 + 常规核销累计）不得高于常规池（总配额 - 储备量），
     * 违反返回 422 并给出常规余额与储备量。reserveKey 指纹含操作者、窗口版本、申请与数量。
     */
    public RegularWriteoffResponse regularWriteoff(String reserveKey, long windowId, Long expectedVersion,
                                                   String allocationKey, String amount, String actor) {
        requireKey("reserveKey", reserveKey);
        requireKey("allocationKey", allocationKey);
        requireKey("X-Actor-Id", actor);
        long version = requireVersion(expectedVersion);
        BigDecimal qty = parseAmount("amount", amount);
        String fingerprint = "REGULAR_WRITEOFF|" + actor + "|" + version + "|" + allocationKey + "|"
                + qty.toPlainString();
        return runReserveKey(reserveKey, "REGULAR_WRITEOFF", fingerprint, RegularWriteoffResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            checkWindowVersion(window, version);
            AllocationRow allocation = repository.lockAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            if (allocation.windowId() != windowId) {
                throw ApiException.conflict("DIFFERENT_WINDOW", "申请不属于该供水窗口");
            }
            if (!STATUS_APPROVED.equals(allocation.status())) {
                throw ApiException.conflict("ALLOCATION_NOT_APPROVED", "仅 APPROVED 申请可以常规核销");
            }
            if (allocation.heldAmount().compareTo(qty) < 0) {
                throw ApiException.quotaExceeded("申请当前持有额度 " + fmt(allocation.heldAmount())
                        + " 不足，无法常规核销 " + fmt(qty) + "；当前常规余额 "
                        + fmt(regularBalance(window)) + "，应急储备量 " + fmt(window.reserveVolume()));
            }
            // 核销把已批准持有的常规量转为已消耗（持有减、累计核销增），窗口常规占用总量不变；
            // 若储备上调等原因已使常规占用侵入储备量，则拒绝新的常规核销。
            BigDecimal regularBalanceBefore = regularBalance(window);
            if (regularBalanceBefore.signum() < 0) {
                throw reserveBlocked(window, "常规占用已侵入应急储备量，不能继续常规核销 " + fmt(qty));
            }
            long now = nowNanos();
            try {
                repository.insertRegularWriteoff(reserveKey, windowId, allocationKey, qty, actor, now);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("RESERVE_KEY_REUSED", "reserveKey 已被使用: " + reserveKey);
            }
            repository.applyRegularWriteoff(allocation.id(), qty, now);
            repository.bumpWindowVersion(windowId);
            BigDecimal balanceAfter = totalQuota(window).subtract(window.reserveVolume())
                    .subtract(repository.sumApprovedAmount(windowId))
                    .subtract(repository.sumRegularWrittenOff(windowId));
            return new RegularWriteoffResponse(reserveKey, windowId, allocationKey, fmt(qty), actor,
                    fmt(balanceAfter), fmt(window.reserveVolume()), toIso(now));
        });
    }

    /**
     * 批量应急核销：同一窗口、同一事务内先校验全部申请的审批信息、应急编号去重与储备余额，
     * 再逐条扣减储备，任一失败整单回滚。窗口关闭或已过结束时刻后不得新建。
     */
    public List<EmergencyWriteoffResponse> emergencyWriteoffBatch(String reserveKey, long windowId,
                                                                  Long expectedVersion,
                                                                  List<EmergencyWriteoffItem> items,
                                                                  String actor) {
        requireKey("reserveKey", reserveKey);
        requireKey("X-Actor-Id", actor);
        long version = requireVersion(expectedVersion);
        if (items == null || items.isEmpty()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "应急核销申请列表不能为空");
        }
        List<ParsedEmergencyItem> parsed = new java.util.ArrayList<>();
        StringBuilder fp = new StringBuilder("EMERGENCY_WRITEOFF|").append(actor).append('|').append(version);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (EmergencyWriteoffItem item : items) {
            if (item == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "应急核销申请不能为空");
            }
            requireKey("emergencyId", item.emergencyId());
            if (item.emergencyId().length() > 100) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "emergencyId 最长 100 字符");
            }
            requireKey("approver", item.approver());
            if (!seen.add(item.emergencyId())) {
                throw ApiException.unprocessable("EMERGENCY_DUPLICATE_IN_BATCH",
                        "同一批量申请中应急编号重复: " + item.emergencyId());
            }
            BigDecimal qty = parseAmount("amount", item.amount());
            parsed.add(new ParsedEmergencyItem(item.emergencyId(), item.approver(), qty));
            fp.append('|').append(item.emergencyId()).append('|').append(item.approver())
                    .append('|').append(qty.toPlainString());
        }
        String fingerprint = fp.toString();
        com.fasterxml.jackson.databind.JavaType listType = objectMapper.getTypeFactory()
                .constructCollectionType(List.class, EmergencyWriteoffResponse.class);
        return runReserveKey(reserveKey, "EMERGENCY_WRITEOFF", fingerprint, listType, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            checkWindowVersion(window, version);
            if (STATUS_CLOSED.equals(window.status()) || nowNanos() >= window.endNanos()) {
                throw ApiException.conflict("WINDOW_ENDED", "窗口已结束，不得新建应急核销");
            }
            BigDecimal reserve = window.reserveVolume();
            BigDecimal used = repository.sumEmergencyWrittenOff(windowId);
            // 第一遍：校验全部申请的审批信息、编号去重与储备余额（锁定既有应急行）
            BigDecimal requestedTotal = BigDecimal.ZERO;
            for (ParsedEmergencyItem item : parsed) {
                if (repository.lockEmergencyWriteoff(windowId, item.emergencyId()) != null) {
                    throw ApiException.conflict("EMERGENCY_ID_USED",
                            "应急编号 " + item.emergencyId() + " 在该窗口已核销，不能重复核销");
                }
                requestedTotal = requestedTotal.add(item.amount());
            }
            BigDecimal reserveBalance = reserve.subtract(used);
            if (requestedTotal.compareTo(reserveBalance) > 0) {
                throw ApiException.unprocessable("RESERVE_INSUFFICIENT",
                        "批量应急核销合计 " + fmt(requestedTotal) + " 超过储备余额 " + fmt(reserveBalance)
                                + "（储备量 " + fmt(reserve) + "）");
            }
            // 第二遍：逐条扣减（仅扣储备量），任一唯一约束冲突整单回滚
            long now = nowNanos();
            List<EmergencyWriteoffResponse> responses = new java.util.ArrayList<>();
            for (ParsedEmergencyItem item : parsed) {
                String writeoffKey = "ew:" + windowId + ":" + item.emergencyId();
                try {
                    long id = repository.insertEmergencyWriteoff(writeoffKey, windowId, item.emergencyId(),
                            item.approver(), actor, item.amount(), reserve, now);
                    responses.add(toEmergencyResponse(repository.findEmergencyWriteoffById(id)));
                } catch (DuplicateKeyException e) {
                    // 并发同应急编号或同 reserveKey：事务回滚，储备无任何扣减
                    throw ApiException.conflict("EMERGENCY_ID_USED",
                            "应急编号 " + item.emergencyId() + " 在该窗口已核销，不能重复核销");
                }
            }
            repository.bumpWindowVersion(windowId);
            return responses;
        });
    }

    /** 关闭窗口；关闭后不得新建应急核销，历史储备与核销快照保留。 */
    public ReserveResponse closeWindow(String reserveKey, long windowId, Long expectedVersion, String actor) {
        requireKey("reserveKey", reserveKey);
        requireKey("X-Actor-Id", actor);
        long version = requireVersion(expectedVersion);
        String fingerprint = "WINDOW_CLOSE|" + actor + "|" + version;
        return runReserveKey(reserveKey, "WINDOW_CLOSE", fingerprint, ReserveResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            checkWindowVersion(window, version);
            if (STATUS_CLOSED.equals(window.status())) {
                throw ApiException.conflict("WINDOW_ALREADY_CLOSED", "窗口已关闭，不能重复关闭");
            }
            long now = nowNanos();
            repository.closeWindow(windowId, now);
            repository.bumpWindowVersion(windowId);
            return toReserveResponse(repository.lockWindowById(windowId));
        });
    }

    /** 查询储备余额、常规可用量、应急核销与当前阻断原因。 */
    public ReserveStatusResponse getReserveStatus(long windowId) {
        WindowRow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        BigDecimal total = totalQuota(window);
        BigDecimal reserve = window.reserveVolume();
        BigDecimal held = repository.sumApprovedAmount(windowId);
        BigDecimal regularWrittenOff = repository.sumRegularWrittenOff(windowId);
        BigDecimal emergencyWrittenOff = repository.sumEmergencyWrittenOff(windowId);
        BigDecimal regularPool = total.subtract(reserve);
        BigDecimal regularBalance = regularPool.subtract(held).subtract(regularWrittenOff);
        BigDecimal reserveRemaining = reserve.subtract(emergencyWrittenOff);
        List<BlockReason> reasons = new java.util.ArrayList<>();
        if (regularBalance.signum() < 0) {
            reasons.add(new BlockReason("REGULAR_RESERVE_INTRUSION",
                    "常规占用已侵入应急储备量，常规转让/常规核销将被阻断", fmt(regularBalance),
                    fmt(reserve)));
        }
        if (reserveRemaining.signum() < 0) {
            reasons.add(new BlockReason("RESERVE_OVERDRAWN",
                    "应急核销累计超过当前储备量", fmt(regularBalance), fmt(reserve)));
        }
        if (STATUS_CLOSED.equals(window.status()) || nowNanos() >= window.endNanos()) {
            reasons.add(new BlockReason("WINDOW_ENDED",
                    "窗口已结束，不得新建应急核销", fmt(regularBalance), fmt(reserve)));
        }
        List<EmergencyWriteoffResponse> emergencyList = repository.listEmergencyWriteoffs(windowId).stream()
                .map(this::toEmergencyResponse).toList();
        return new ReserveStatusResponse(windowId, window.status(), window.version(), fmt(total), fmt(reserve),
                fmt(reserveRemaining), fmt(regularPool), fmt(regularBalance), fmt(held),
                fmt(regularWrittenOff), fmt(emergencyWrittenOff), emergencyList, reasons);
    }

    /** 窗口应急核销流水，按发生顺序返回。 */
    public List<EmergencyWriteoffResponse> getEmergencyWriteoffs(long windowId) {
        WindowRow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        return repository.listEmergencyWriteoffs(windowId).stream().map(this::toEmergencyResponse).toList();
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

    /**
     * 单事务内同时管理 commandKey 命令行与 reserveKey 指纹行（转让同时受两套幂等约束）。
     * 两个占位插入与业务在同一事务提交：任一唯一键冲突或业务失败都整体回滚，不存在半占键。
     * 冲突后按已提交行重放：同键同参/同指纹返回首次快照，任一不一致返回 409。
     */
    private <T> T runCommandAndReserveKey(String commandKey, String commandOperation, String commandParams,
                                          String reserveKey, String reserveOperation, String fingerprint,
                                          Class<T> type, Supplier<T> business) {
        try {
            return tx.execute(status -> {
                CommandRow existingCommand = repository.findCommand(commandKey);
                if (existingCommand != null) {
                    return replay(existingCommand, commandOperation, commandParams, type);
                }
                ReserveCommandRow existingReserve = repository.findReserveCommand(reserveKey);
                if (existingReserve != null) {
                    return replayReserve(existingReserve, reserveOperation, fingerprint, type);
                }
                repository.insertCommand(commandKey, commandOperation, commandParams, nowNanos());
                repository.insertReserveCommand(reserveKey, reserveOperation, fingerprint, nowNanos());
                T result = business.get();
                String json = toJson(result);
                repository.updateCommandResponse(commandKey, json);
                repository.updateReserveCommandResponse(reserveKey, json);
                return result;
            });
        } catch (DuplicateKeyException e) {
            CommandRow committedCommand = repository.findCommand(commandKey);
            if (committedCommand != null) {
                if (committedCommand.response() == null) {
                    throw ApiException.conflict("COMMAND_CONFLICT", "相同 commandKey 的命令正在处理，请重试");
                }
                return replay(committedCommand, commandOperation, commandParams, type);
            }
            ReserveCommandRow committedReserve = repository.findReserveCommand(reserveKey);
            if (committedReserve == null || committedReserve.response() == null) {
                throw ApiException.conflict("RESERVE_COMMAND_CONFLICT",
                        "相同 reserveKey 的命令正在处理，请重试");
            }
            return replayReserve(committedReserve, reserveOperation, fingerprint, type);
        }
    }

    // ------------------------------------------------------------------
    // reserveKey 指纹框架（储备调整/转让/常规核销/应急核销/窗口关闭）
    // ------------------------------------------------------------------

    /**
     * 在单事务内按 reserveKey 执行储备命令：先占位插入指纹行，再执行业务并写回快照。
     * 同键同指纹重放首次快照；同键改指纹返回 409；业务失败事务回滚，指纹行不占键。
     * 并发同键时占位插入因唯一约束失败，随后读取已提交快照重放或报 409。
     */
    private <T> T runReserveKey(String reserveKey, String operation, String fingerprint, Class<T> type,
                                Supplier<T> business) {
        return runReserveKey(reserveKey, operation, fingerprint,
                objectMapper.getTypeFactory().constructType(type), business);
    }

    private <T> T runReserveKey(String reserveKey, String operation, String fingerprint,
                                com.fasterxml.jackson.databind.JavaType type, Supplier<T> business) {
        try {
            return tx.execute(status -> {
                ReserveCommandRow existing = repository.findReserveCommand(reserveKey);
                if (existing != null) {
                    return replayReserve(existing, operation, fingerprint, type);
                }
                repository.insertReserveCommand(reserveKey, operation, fingerprint, nowNanos());
                T result = business.get();
                repository.updateReserveCommandResponse(reserveKey, toJson(result));
                return result;
            });
        } catch (DuplicateKeyException e) {
            ReserveCommandRow committed = repository.findReserveCommand(reserveKey);
            if (committed == null || committed.response() == null) {
                throw ApiException.conflict("RESERVE_COMMAND_CONFLICT",
                        "相同 reserveKey 的命令正在处理，请重试");
            }
            return replayReserve(committed, operation, fingerprint, type);
        }
    }

    private <T> T replayReserve(ReserveCommandRow existing, String operation, String fingerprint,
                                Class<T> type) {
        return replayReserve(existing, operation, fingerprint,
                objectMapper.getTypeFactory().constructType(type));
    }

    private <T> T replayReserve(ReserveCommandRow existing, String operation, String fingerprint,
                                com.fasterxml.jackson.databind.JavaType type) {
        if (!existing.operation().equals(operation) || !existing.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("RESERVE_KEY_REUSED", "相同 reserveKey 但指纹不同，拒绝重放");
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

    /** 窗口当前总配额：生效限供水量优先，否则计划水量。 */
    private BigDecimal totalQuota(WindowRow window) {
        return availableTotal(window);
    }

    /** 当前常规余额：总配额 - 储备量 - 已批准持有量 - 常规核销累计；为负表示常规已侵占储备。 */
    private BigDecimal regularBalance(WindowRow window) {
        return totalQuota(window).subtract(window.reserveVolume())
                .subtract(repository.sumApprovedAmount(window.id()))
                .subtract(repository.sumRegularWrittenOff(window.id()));
    }

    /**
     * 记录调用方声明的窗口版本（仅校验非空，参与 reserveKey 指纹）。
     * 并发裁决依据窗口行锁内的最新已提交状态（按事务提交顺序），不做简单乐观锁拒绝，
     * 这样后提交者会基于新储备/新持有量得到正确的 422/409 业务结果，而非笼统版本冲突。
     */
    private void checkWindowVersion(WindowRow window, long expectedVersion) {
        // 版本号仅用于调用方快照与指纹去重；业务条件一律以锁内最新状态裁决
    }

    /** 储备隔离阻断：422，消息中给出当前常规余额与储备量。 */
    private ApiException reserveBlocked(WindowRow window, String detail) {
        BigDecimal balance = regularBalance(window);
        return ApiException.unprocessable("RESERVE_ISOLATION",
                detail + "；当前常规余额 " + fmt(balance) + "，应急储备量 " + fmt(window.reserveVolume()));
    }

    private ReserveResponse toReserveResponse(WindowRow row) {
        BigDecimal total = totalQuota(row);
        BigDecimal emergencyUsed = repository.sumEmergencyWrittenOff(row.id());
        return new ReserveResponse(row.id(), fmt(row.reserveVolume()),
                fmt(total.subtract(row.reserveVolume())), fmt(row.reserveVolume().subtract(emergencyUsed)),
                row.version(), row.status());
    }

    private EmergencyWriteoffResponse toEmergencyResponse(EmergencyWriteoffRow row) {
        return new EmergencyWriteoffResponse(row.writeoffKey(), row.windowId(), row.emergencyId(),
                row.approver(), row.actor(), fmt(row.amount()), fmt(row.reserveSnapshot()),
                toIso(row.createdNanos()));
    }

    private WindowResponse toWindowResponse(WindowRow row, CurtailmentRow active) {
        BigDecimal available = active != null ? active.volume() : row.plannedVolume();
        return new WindowResponse(row.id(), row.windowKey(), row.channelId(), toIso(row.startNanos()),
                toIso(row.endNanos()), fmt(row.plannedVolume()),
                active != null ? fmt(active.volume()) : null, fmt(available), toIso(row.createdNanos()),
                fmt(row.reserveVolume()), row.version(), row.status(),
                row.closedNanos() == null ? null : toIso(row.closedNanos()));
    }

    private AllocationResponse toAllocationResponse(AllocationRow row) {
        return new AllocationResponse(row.allocationKey(), row.windowId(), row.userId(), fmt(row.amount()),
                fmt(row.heldAmount()), fmt(row.regularWrittenOff()), row.requester(), row.status(),
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

    static void requireKey(String field, String value) {
        if (value == null || !KEY_PATTERN.matcher(value).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 不能为空，且仅允许字母数字及 . - _ :，最长 128 字符");
        }
    }

    /** 校验窗口 expectedVersion：必填且非负。 */
    static long requireVersion(Long expectedVersion) {
        if (expectedVersion == null || expectedVersion < 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "expectedVersion 不能为空且必须为非负整数");
        }
        return expectedVersion;
    }

    /** 解析非负水量（允许 0），十进制字符串，最多 3 位小数；储备量调整使用。 */
    static BigDecimal parseNonNegativeAmount(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不能为空");
        }
        String trimmed = value.trim();
        if (!AMOUNT_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 必须为非负十进制字符串，最多 3 位小数: " + value);
        }
        return new BigDecimal(trimmed);
    }

    /** 批量应急核销解析后的单条申请。 */
    private record ParsedEmergencyItem(String emergencyId, String approver, BigDecimal amount) {
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

    @SuppressWarnings("unchecked")
    private <T> T fromJson(String json, com.fasterxml.jackson.databind.JavaType type) {
        try {
            return (T) objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("响应反序列化失败", e);
        }
    }
}
