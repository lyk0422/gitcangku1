package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.CorrectionRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.LedgerEntryRow;
import com.example.starter.water.WaterRepository.RejectionRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.WaterRepository.WriteoffRow;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.BalanceEventResponse;
import com.example.starter.water.dto.Dtos.BalanceEvolutionResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CorrectionApproveResponse;
import com.example.starter.water.dto.Dtos.CorrectionResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.LedgerEntryResponse;
import com.example.starter.water.dto.Dtos.LedgerResponse;
import com.example.starter.water.dto.Dtos.RejectionListResponse;
import com.example.starter.water.dto.Dtos.RejectionResponse;
import com.example.starter.water.dto.Dtos.TransferListResponse;
import com.example.starter.water.dto.Dtos.TransferResponse;
import com.example.starter.water.dto.Dtos.WindowResponse;
import com.example.starter.water.dto.Dtos.WriteoffListResponse;
import com.example.starter.water.dto.Dtos.WriteoffResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    static final String STATUS_REVOKED = "REVOKED";

    static final String KIND_WRITEOFF = "WRITEOFF";
    static final String KIND_CORRECTION = "CORRECTION";
    static final String KIND_REVOCATION = "REVOCATION";
    static final String KIND_TRANSFER_OUT = "TRANSFER_OUT";

    private static final int MAX_CORRECTION_BATCH = 20;
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
    // 核销（原流水）
    // ------------------------------------------------------------------

    /**
     * 核销：从 APPROVED 申请的持有额度中扣减实际用水量，写入不可变原流水（WRITEOFF）。
     * 与批准/转让/限供在同一窗口行锁上按事务提交顺序裁决；窗口关闭后拒绝。
     */
    public WriteoffResponse createWriteoff(String commandKey, String allocationKey, String writeoffKey,
                                           String amount, String meterUtc, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        requireKey("writeoffKey", writeoffKey);
        requireKey("X-Actor-Id", actor);
        BigDecimal qty = parseAmount("amount", amount);
        long meterNanos = parseInstant("meterUtc", meterUtc);
        String params = "WRITEOFF|" + writeoffKey + "|" + allocationKey + "|" + qty.toPlainString() + "|"
                + meterNanos + "|" + actor;
        return runCommand("WRITEOFF", commandKey, params, WriteoffResponse.class, () -> {
            if (repository.findWriteoffByKey(writeoffKey) != null) {
                throw ApiException.conflict("WRITEOFF_KEY_REUSED", "writeoffKey 已被使用: " + writeoffKey);
            }
            AllocationRow allocation = repository.findAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            WindowRow window = lockWindowOf(allocation);
            requireWindowOpen(window, "窗口已关闭，不能新增核销");
            // 窗口锁内重读申请，避免与转让/更正并发时使用过期持有额度
            allocation = repository.lockAllocationByKey(allocationKey);
            if (!STATUS_APPROVED.equals(allocation.status())) {
                throw ApiException.conflict("ALLOCATION_NOT_APPROVED", "只有 APPROVED 申请可以核销");
            }
            if (allocation.heldAmount().compareTo(qty) < 0) {
                throw ApiException.quotaExceeded("当前持有额度 " + fmt(allocation.heldAmount())
                        + " 不足，无法核销 " + fmt(qty));
            }
            long now = nowNanos();
            int version = repository.nextWriteoffVersion(allocationKey);
            repository.decrementHeldAmount(allocation.id(), qty, now);
            BigDecimal balanceAfter = allocation.heldAmount().subtract(qty);
            try {
                repository.insertWriteoff(writeoffKey, allocationKey, window.id(), version, qty, meterNanos,
                        actor, now);
            } catch (DuplicateKeyException e) {
                // 并发复用同一 writeoffKey（换 commandKey）：事务回滚，额度无变化
                throw ApiException.conflict("WRITEOFF_KEY_REUSED", "writeoffKey 已被使用: " + writeoffKey);
            }
            repository.insertLedgerEntry(window.id(), allocationKey, KIND_WRITEOFF, writeoffKey,
                    qty.negate(), balanceAfter, meterNanos, now);
            return toWriteoffResponse(repository.findWriteoffByKey(writeoffKey));
        });
    }

    /** 查询申请全部核销记录（原流水），按版本升序。 */
    public WriteoffListResponse getWriteoffs(String allocationKey) {
        requireKey("allocationKey", allocationKey);
        if (repository.findAllocationByKey(allocationKey) == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
        }
        List<WriteoffResponse> writeoffs = repository.listWriteoffsByAllocation(allocationKey).stream()
                .map(this::toWriteoffResponse).toList();
        return new WriteoffListResponse(allocationKey, writeoffs);
    }

    // ------------------------------------------------------------------
    // 计量更正
    // ------------------------------------------------------------------

    /**
     * 登记计量更正：meterKey 指纹（核销键|原核销版本|校正数|读表时刻|原因|操作者）幂等，
     * 同键同指纹重放首次结果，同键异指纹 409；校验失败事务回滚不占键。窗口关闭后仍可登记。
     */
    public CorrectionResponse requestCorrection(String meterKey, String writeoffKey, Integer originalVersion,
                                                String correctedAmount, String meterUtc, String reason,
                                                String actor) {
        requireKey("meterKey", meterKey);
        requireKey("writeoffKey", writeoffKey);
        requireKey("X-Actor-Id", actor);
        if (originalVersion == null || originalVersion < 1) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "originalVersion 必须为不小于 1 的整数");
        }
        BigDecimal corrected = parseCorrectedAmount("correctedAmount", correctedAmount);
        long meterNanos = parseInstant("meterUtc", meterUtc);
        String trimmedReason = requireReason(reason);
        String fingerprint = writeoffKey + "|" + originalVersion + "|" + corrected.toPlainString() + "|"
                + meterNanos + "|" + trimmedReason + "|" + actor;
        WriteoffRow writeoff = repository.findWriteoffByKey(writeoffKey);
        Long logWindowId = writeoff != null ? writeoff.windowId() : null;
        return logRejectionOnFailure("CORRECTION_REQUEST", meterKey, logWindowId, () -> {
            try {
                return tx.execute(status -> {
                    CorrectionRow existing = repository.findCorrectionByKey(meterKey);
                    if (existing != null) {
                        if (!existing.fingerprint().equals(fingerprint)) {
                            throw ApiException.conflict("METER_KEY_REUSED",
                                    "相同 meterKey 但指纹不同，拒绝重放: " + meterKey);
                        }
                        return toCorrectionResponse(existing);
                    }
                    WriteoffRow target = repository.findWriteoffByKey(writeoffKey);
                    if (target == null) {
                        throw ApiException.notFound("WRITEOFF_NOT_FOUND", "核销记录不存在: " + writeoffKey);
                    }
                    AllocationRow allocation = repository.lockAllocationByKey(target.allocationKey());
                    if (allocation == null || !STATUS_APPROVED.equals(allocation.status())) {
                        throw ApiException.conflict("ALLOCATION_NOT_APPROVED",
                                "只有 APPROVED 申请的核销可以登记更正");
                    }
                    if (target.version() != originalVersion) {
                        throw ApiException.conflict("WRITEOFF_VERSION_MISMATCH",
                                "原核销版本不匹配: 当前版本 " + target.version() + "，请求版本 " + originalVersion);
                    }
                    repository.insertCorrection(meterKey, fingerprint, writeoffKey, target.allocationKey(),
                            target.windowId(), originalVersion, corrected, meterNanos, trimmedReason, actor,
                            nowNanos());
                    return toCorrectionResponse(repository.findCorrectionByKey(meterKey));
                });
            } catch (DuplicateKeyException e) {
                // 并发同键：读取已提交的首次结果重放或按指纹报 409
                CorrectionRow committed = repository.findCorrectionByKey(meterKey);
                if (committed == null) {
                    throw ApiException.conflict("COMMAND_CONFLICT", "相同 meterKey 的更正正在处理，请重试");
                }
                if (!committed.fingerprint().equals(fingerprint)) {
                    throw ApiException.conflict("METER_KEY_REUSED",
                            "相同 meterKey 但指纹不同，拒绝重放: " + meterKey);
                }
                return toCorrectionResponse(committed);
            }
        });
    }

    /**
     * 批量批准更正：按提交顺序在同一事务内对每个申请按最终有效核销量重放事件时间线
     * （核销读表时刻 + 已结算转让时刻），任一历史时点之后可用量为负返回 422 NEGATIVE_BALANCE；
     * 任一窗口已结算持有总额超过当前可用总量返回 422 RESERVE_ENCROACHED；
     * 任一失败，全部更正、额度与流水回滚。成功时为每条更正写反向流水与不可变读表快照。
     */
    public CorrectionApproveResponse approveCorrections(String commandKey, List<String> meterKeys) {
        requireKey("commandKey", commandKey);
        if (meterKeys == null || meterKeys.isEmpty()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "meterKeys 不能为空");
        }
        if (meterKeys.size() > MAX_CORRECTION_BATCH) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "单批更正数量不能超过 " + MAX_CORRECTION_BATCH);
        }
        List<String> keys = new ArrayList<>(new LinkedHashSet<>(meterKeys));
        keys.forEach(key -> requireKey("meterKey", key));
        String params = "CORRECTION_APPROVE|" + String.join(",", keys);
        CorrectionRow first = repository.findCorrectionByKey(keys.get(0));
        Long logWindowId = first != null ? first.windowId() : null;
        return logRejectionOnFailure("CORRECTION_APPROVE", keys.get(0), logWindowId, () ->
                runCommand("CORRECTION_APPROVE", commandKey, params, CorrectionApproveResponse.class, () -> {
                    List<CorrectionRow> corrections = new ArrayList<>();
                    for (String key : keys) {
                        CorrectionRow correction = repository.findCorrectionByKey(key);
                        if (correction == null) {
                            throw ApiException.notFound("CORRECTION_NOT_FOUND", "计量更正不存在: " + key);
                        }
                        corrections.add(correction);
                    }
                    // 先按 ID 升序锁全部涉及窗口，与核销/转让/限供按事务提交顺序串行裁决
                    List<Long> windowIds = corrections.stream().map(CorrectionRow::windowId).distinct()
                            .sorted().toList();
                    Map<Long, WindowRow> windows = new HashMap<>();
                    for (Long windowId : windowIds) {
                        WindowRow window = repository.lockWindowById(windowId);
                        if (window == null) {
                            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
                        }
                        requireWindowOpen(window, "窗口已关闭，不能批准影响已结算余额的更正");
                        windows.put(windowId, window);
                    }
                    // 窗口锁内重读更正行，获得最新状态
                    List<CorrectionRow> locked = new ArrayList<>();
                    for (String key : keys) {
                        locked.add(repository.lockCorrectionByKey(key));
                    }
                    for (CorrectionRow correction : locked) {
                        if (!STATUS_REQUESTED.equals(correction.status())) {
                            throw ApiException.conflict("CORRECTION_NOT_REQUESTED",
                                    "更正 " + correction.meterKey() + " 当前状态 " + correction.status()
                                            + "，不能批准");
                        }
                    }
                    // 按申请分组，按提交顺序应用本批更正并做最终态校验
                    Map<String, List<CorrectionRow>> byAllocation = new LinkedHashMap<>();
                    for (CorrectionRow correction : locked) {
                        byAllocation.computeIfAbsent(correction.allocationKey(), k -> new ArrayList<>())
                                .add(correction);
                    }
                    Map<String, AllocationRow> allocations = new HashMap<>();
                    Map<String, BigDecimal> newHeldByAllocation = new LinkedHashMap<>();
                    List<LedgerSpec> ledgerSpecs = new ArrayList<>();
                    for (Map.Entry<String, List<CorrectionRow>> entry : byAllocation.entrySet()) {
                        String allocationKey = entry.getKey();
                        AllocationRow allocation = repository.lockAllocationByKey(allocationKey);
                        if (allocation == null || !STATUS_APPROVED.equals(allocation.status())) {
                            throw ApiException.conflict("ALLOCATION_NOT_APPROVED",
                                    "只有 APPROVED 申请的更正可以批准: " + allocationKey);
                        }
                        allocations.put(allocationKey, allocation);
                        Map<String, WriteoffRow> writeoffs = writeoffsByKey(allocationKey);
                        Map<String, BigDecimal> effective = effectiveAmounts(allocationKey, null);
                        BigDecimal balance = allocation.heldAmount();
                        for (CorrectionRow correction : entry.getValue()) {
                            BigDecimal oldEffective = effective.getOrDefault(correction.writeoffKey(),
                                    writeoffs.get(correction.writeoffKey()).amount());
                            effective.put(correction.writeoffKey(), correction.correctedAmount());
                            BigDecimal delta = oldEffective.subtract(correction.correctedAmount());
                            balance = balance.add(delta);
                            ledgerSpecs.add(new LedgerSpec(allocation.windowId(), allocationKey,
                                    KIND_CORRECTION, correction.meterKey(), delta, balance,
                                    correction.meterNanos()));
                        }
                        newHeldByAllocation.put(allocationKey, replayBalance(allocation, effective));
                    }
                    // 储备约束：更正后各窗口已结算持有总额不得超过当前可用总量
                    for (Long windowId : windowIds) {
                        BigDecimal sum = repository.sumApprovedAmount(windowId);
                        for (Map.Entry<String, BigDecimal> e : newHeldByAllocation.entrySet()) {
                            AllocationRow allocation = allocations.get(e.getKey());
                            if (allocation.windowId() == windowId) {
                                sum = sum.subtract(allocation.heldAmount()).add(e.getValue());
                            }
                        }
                        BigDecimal available = availableTotal(windows.get(windowId));
                        if (sum.compareTo(available) > 0) {
                            throw ApiException.unprocessable("RESERVE_ENCROACHED",
                                    "更正后窗口已结算持有 " + fmt(sum) + " 将超过可用总量 " + fmt(available)
                                            + "，侵占储备");
                        }
                    }
                    // 全部校验通过，原子应用：状态、持有额度、反向流水、读表快照
                    long now = nowNanos();
                    for (CorrectionRow correction : locked) {
                        repository.updateCorrectionStatus(correction.id(), STATUS_APPROVED, now);
                        repository.insertSnapshot(correction.meterKey(), correction.writeoffKey(),
                                correction.windowId(), correction.originalVersion(),
                                correction.correctedAmount(), correction.meterNanos(), correction.reason(),
                                correction.actor(), now);
                    }
                    for (Map.Entry<String, BigDecimal> e : newHeldByAllocation.entrySet()) {
                        repository.setHeldAmount(allocations.get(e.getKey()).id(), e.getValue(), now);
                    }
                    for (LedgerSpec spec : ledgerSpecs) {
                        repository.insertLedgerEntry(spec.windowId(), spec.allocationKey(), spec.kind(),
                                spec.refKey(), spec.delta(), spec.balanceAfter(), spec.eventNanos(), now);
                    }
                    List<CorrectionResponse> approved = new ArrayList<>();
                    for (String key : keys) {
                        approved.add(toCorrectionResponse(repository.findCorrectionByKey(key)));
                    }
                    return new CorrectionApproveResponse(approved);
                }));
    }

    /**
     * 撤销已批准更正：再产生一条 REVOCATION 反向流水，并按同一最终态校验
     * （历史时点可用量不得为负、不得侵占储备）；窗口关闭后拒绝。
     */
    public CorrectionResponse revokeCorrection(String commandKey, String meterKey) {
        requireKey("commandKey", commandKey);
        requireKey("meterKey", meterKey);
        String params = "CORRECTION_REVOKE|" + meterKey;
        CorrectionRow existing = repository.findCorrectionByKey(meterKey);
        Long logWindowId = existing != null ? existing.windowId() : null;
        return logRejectionOnFailure("CORRECTION_REVOKE", meterKey, logWindowId, () ->
                runCommand("CORRECTION_REVOKE", commandKey, params, CorrectionResponse.class, () -> {
                    CorrectionRow correction = repository.findCorrectionByKey(meterKey);
                    if (correction == null) {
                        throw ApiException.notFound("CORRECTION_NOT_FOUND", "计量更正不存在: " + meterKey);
                    }
                    WindowRow window = repository.lockWindowById(correction.windowId());
                    if (window == null) {
                        throw ApiException.notFound("WINDOW_NOT_FOUND",
                                "供水窗口不存在: " + correction.windowId());
                    }
                    requireWindowOpen(window, "窗口已关闭，不能撤销影响已结算余额的更正");
                    correction = repository.lockCorrectionByKey(meterKey);
                    if (!STATUS_APPROVED.equals(correction.status())) {
                        throw ApiException.conflict("CORRECTION_NOT_APPROVED",
                                "更正当前状态 " + correction.status() + "，只有 APPROVED 可以撤销");
                    }
                    AllocationRow allocation = repository.lockAllocationByKey(correction.allocationKey());
                    if (allocation == null || !STATUS_APPROVED.equals(allocation.status())) {
                        throw ApiException.conflict("ALLOCATION_NOT_APPROVED",
                                "只有 APPROVED 申请的更正可以撤销");
                    }
                    Map<String, WriteoffRow> writeoffs = writeoffsByKey(allocation.allocationKey());
                    WriteoffRow writeoff = writeoffs.get(correction.writeoffKey());
                    Map<String, BigDecimal> currentEffective =
                            effectiveAmounts(allocation.allocationKey(), null);
                    Map<String, BigDecimal> revokedEffective =
                            effectiveAmounts(allocation.allocationKey(), meterKey);
                    BigDecimal oldEffective = currentEffective.getOrDefault(correction.writeoffKey(),
                            writeoff.amount());
                    BigDecimal newEffective = revokedEffective.getOrDefault(correction.writeoffKey(),
                            writeoff.amount());
                    BigDecimal delta = oldEffective.subtract(newEffective);
                    BigDecimal finalBalance = replayBalance(allocation, revokedEffective);
                    BigDecimal sum = repository.sumApprovedAmount(window.id())
                            .subtract(allocation.heldAmount()).add(finalBalance);
                    BigDecimal available = availableTotal(window);
                    if (sum.compareTo(available) > 0) {
                        throw ApiException.unprocessable("RESERVE_ENCROACHED",
                                "撤销后窗口已结算持有 " + fmt(sum) + " 将超过可用总量 " + fmt(available) + "，侵占储备");
                    }
                    long now = nowNanos();
                    repository.updateCorrectionStatus(correction.id(), STATUS_REVOKED, now);
                    repository.setHeldAmount(allocation.id(), finalBalance, now);
                    repository.insertLedgerEntry(window.id(), allocation.allocationKey(), KIND_REVOCATION,
                            meterKey, delta, finalBalance, now, now);
                    return toCorrectionResponse(repository.findCorrectionByKey(meterKey));
                }));
    }

    /** 按 meterKey 查询更正详情，不存在返回 404。 */
    public CorrectionResponse getCorrection(String meterKey) {
        requireKey("meterKey", meterKey);
        CorrectionRow correction = repository.findCorrectionByKey(meterKey);
        if (correction == null) {
            throw ApiException.notFound("CORRECTION_NOT_FOUND", "计量更正不存在: " + meterKey);
        }
        return toCorrectionResponse(correction);
    }

    /** 查询申请的反向流水（CORRECTION/REVOCATION），按入账顺序返回。 */
    public LedgerResponse getReverseLedger(String allocationKey) {
        requireKey("allocationKey", allocationKey);
        if (repository.findAllocationByKey(allocationKey) == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
        }
        List<LedgerEntryResponse> entries = repository.listLedgerEntries(allocationKey).stream()
                .filter(entry -> !KIND_WRITEOFF.equals(entry.kind()))
                .map(this::toLedgerEntryResponse).toList();
        return new LedgerResponse(allocationKey, entries);
    }

    /**
     * 余额演算：自批准额度起，按业务事件时刻（读表时刻/转让时刻/裁决时刻）逐事件重放
     * 原流水、反向流水与已结算转让，返回每步余额与最终余额。
     */
    public BalanceEvolutionResponse getBalanceEvolution(String allocationKey) {
        requireKey("allocationKey", allocationKey);
        AllocationRow allocation = repository.findAllocationByKey(allocationKey);
        if (allocation == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
        }
        List<BalanceEvent> events = new ArrayList<>();
        for (LedgerEntryRow entry : repository.listLedgerEntries(allocationKey)) {
            events.add(new BalanceEvent(entry.eventNanos(), eventOrder(entry.kind()), entry.kind(),
                    entry.refKey(), entry.delta()));
        }
        for (TransferRow transfer : repository.listTransfersBySource(allocationKey)) {
            events.add(new BalanceEvent(transfer.createdNanos(), eventOrder(KIND_TRANSFER_OUT),
                    KIND_TRANSFER_OUT, transfer.transferKey(), transfer.amount().negate()));
        }
        events.sort(Comparator.comparingLong(BalanceEvent::nanos).thenComparingInt(BalanceEvent::order));
        BigDecimal start = STATUS_REQUESTED.equals(allocation.status())
                ? BigDecimal.ZERO : allocation.amount();
        BigDecimal balance = start;
        List<BalanceEventResponse> responses = new ArrayList<>();
        for (BalanceEvent event : events) {
            balance = balance.add(event.delta());
            responses.add(new BalanceEventResponse(toIso(event.nanos()), event.kind(), event.refKey(),
                    fmt(event.delta()), fmt(balance)));
        }
        return new BalanceEvolutionResponse(allocationKey, fmt(start), responses, fmt(balance));
    }

    /** 查询窗口全部拒绝原因日志（更正相关失败，独立事务记录），按发生顺序返回。 */
    public RejectionListResponse getRejections(long windowId) {
        WindowRow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        List<RejectionResponse> rejections = repository.listRejections(windowId).stream()
                .map(this::toRejectionResponse).toList();
        return new RejectionListResponse(windowId, rejections);
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

    /** 待入账反向流水规格（窗口锁内计算，全部校验通过后统一落库）。 */
    private record LedgerSpec(long windowId, String allocationKey, String kind, String refKey,
                              BigDecimal delta, BigDecimal balanceAfter, long eventNanos) {
    }

    /** 余额演算事件：业务事件时刻 + 同刻顺序 + 类型 + 来源键 + 有符号水量。 */
    private record BalanceEvent(long nanos, int order, String kind, String refKey, BigDecimal delta) {
    }

    /** 窗口已关闭（当前时刻不早于窗口结束时刻）时拒绝影响已结算余额的操作。 */
    private void requireWindowOpen(WindowRow window, String message) {
        if (nowNanos() >= window.endNanos()) {
            throw ApiException.conflict("WINDOW_CLOSED", message);
        }
    }

    /** 申请全部核销记录按业务键索引。 */
    private Map<String, WriteoffRow> writeoffsByKey(String allocationKey) {
        Map<String, WriteoffRow> map = new HashMap<>();
        for (WriteoffRow writeoff : repository.listWriteoffsByAllocation(allocationKey)) {
            map.put(writeoff.writeoffKey(), writeoff);
        }
        return map;
    }

    /**
     * 申请各核销记录的当前有效水量：最近一条已批准（未撤销）更正的校正数，否则为原核销量。
     * excludeMeterKey 非空时在计算中排除该更正（用于撤销演算）。
     */
    private Map<String, BigDecimal> effectiveAmounts(String allocationKey, String excludeMeterKey) {
        Map<String, BigDecimal> effective = new LinkedHashMap<>();
        for (CorrectionRow correction : repository.listApprovedCorrections(allocationKey)) {
            if (excludeMeterKey != null && excludeMeterKey.equals(correction.meterKey())) {
                continue;
            }
            // 已按裁决时间、主键升序，后批准的覆盖先批准的
            effective.put(correction.writeoffKey(), correction.correctedAmount());
        }
        return effective;
    }

    /**
     * 以给定有效核销量重放申请事件时间线（核销读表时刻 + 已结算转让时刻）：
     * 任一历史时点之后可用量为负则 422 NEGATIVE_BALANCE，否则返回最终余额。
     */
    private BigDecimal replayBalance(AllocationRow allocation, Map<String, BigDecimal> effective) {
        List<BalanceEvent> events = new ArrayList<>();
        for (WriteoffRow writeoff : repository.listWriteoffsByAllocation(allocation.allocationKey())) {
            BigDecimal amount = effective.getOrDefault(writeoff.writeoffKey(), writeoff.amount());
            events.add(new BalanceEvent(writeoff.meterNanos(), eventOrder(KIND_WRITEOFF), KIND_WRITEOFF,
                    writeoff.writeoffKey(), amount.negate()));
        }
        for (TransferRow transfer : repository.listTransfersBySource(allocation.allocationKey())) {
            events.add(new BalanceEvent(transfer.createdNanos(), eventOrder(KIND_TRANSFER_OUT),
                    KIND_TRANSFER_OUT, transfer.transferKey(), transfer.amount().negate()));
        }
        events.sort(Comparator.comparingLong(BalanceEvent::nanos).thenComparingInt(BalanceEvent::order));
        BigDecimal balance = allocation.amount();
        for (BalanceEvent event : events) {
            balance = balance.add(event.delta());
            if (balance.signum() < 0) {
                throw ApiException.unprocessable("NEGATIVE_BALANCE",
                        "更正后历史时点 " + toIso(event.nanos()) + " 之后可用量为负: " + fmt(balance));
            }
        }
        return balance;
    }

    /** 同一业务事件时刻内的类型顺序：核销先于转出，更正先于撤销。 */
    private static int eventOrder(String kind) {
        return switch (kind) {
            case KIND_WRITEOFF -> 0;
            case KIND_TRANSFER_OUT -> 1;
            case KIND_CORRECTION -> 2;
            default -> 3;
        };
    }

    /**
     * 更正相关操作失败时在独立事务记录可查询的拒绝原因；
     * 业务命令本身已回滚不占键，日志写入失败不掩盖原业务异常。
     */
    private <T> T logRejectionOnFailure(String operation, String meterKey, Long windowId,
                                        Supplier<T> body) {
        try {
            return body.get();
        } catch (ApiException e) {
            try {
                tx.execute(status -> {
                    repository.insertRejection(windowId, meterKey, operation, e.code(),
                            truncate(e.getMessage()), nowNanos());
                    return null;
                });
            } catch (RuntimeException logFailure) {
                // 拒绝日志写入失败不掩盖原业务异常
            }
            throw e;
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "reason 不能为空");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > 512) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "reason 最长 512 字符");
        }
        return trimmed;
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

    private WriteoffResponse toWriteoffResponse(WriteoffRow row) {
        return new WriteoffResponse(row.writeoffKey(), row.allocationKey(), row.windowId(), row.version(),
                fmt(row.amount()), toIso(row.meterNanos()), row.actor(), toIso(row.createdNanos()));
    }

    private CorrectionResponse toCorrectionResponse(CorrectionRow row) {
        return new CorrectionResponse(row.meterKey(), row.writeoffKey(), row.allocationKey(), row.windowId(),
                row.originalVersion(), fmt(row.correctedAmount()), toIso(row.meterNanos()), row.reason(),
                row.actor(), row.status(), toIso(row.createdNanos()),
                row.decidedNanos() == null ? null : toIso(row.decidedNanos()));
    }

    private LedgerEntryResponse toLedgerEntryResponse(LedgerEntryRow row) {
        return new LedgerEntryResponse(row.id(), row.kind(), row.refKey(), fmt(row.delta()),
                fmt(row.balanceAfter()), toIso(row.eventNanos()), toIso(row.createdNanos()));
    }

    private RejectionResponse toRejectionResponse(RejectionRow row) {
        return new RejectionResponse(row.id(), row.windowId(), row.meterKey(), row.operation(), row.code(),
                row.message(), toIso(row.createdNanos()));
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

    /** 解析校正数量：十进制字符串，不得为负（允许 0），最多 3 位小数。 */
    static BigDecimal parseCorrectedAmount(String field, String value) {
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
