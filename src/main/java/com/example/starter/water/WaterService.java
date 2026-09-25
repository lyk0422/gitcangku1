package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.CorrectionRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.SnapshotRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.dto.Dtos.AllocationLedgerResponse;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CorrectionBatchResponse;
import com.example.starter.water.dto.Dtos.CorrectionPreviewItem;
import com.example.starter.water.dto.Dtos.CorrectionPreviewResponse;
import com.example.starter.water.dto.Dtos.CorrectionResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.LedgerEntryResponse;
import com.example.starter.water.dto.Dtos.SnapshotResponse;
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
import java.util.ArrayList;
import java.util.Comparator;
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
    static final String STATUS_REVOKED = "REVOKED";

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
            // 原核销流水：批准即版本 1 的核销记录入账
            repository.insertLedger(allocation.allocationKey(), window.id(), "WRITE_OFF", null,
                    allocation.amount(), allocation.amount(), nowNanos());
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
            // 取消流水：持有额度归零
            repository.insertLedger(allocation.allocationKey(), allocation.windowId(), "CANCEL", null,
                    allocation.heldAmount().negate(), BigDecimal.ZERO, nowNanos());
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
            // 转出流水：源持有额度等额扣减；目标以 WRITE_OFF 入账（额度来自转让而非普通批准）
            repository.insertLedger(lockedSource.allocationKey(), lockedSource.windowId(), "TRANSFER_OUT", null,
                    amount.negate(), lockedSource.heldAmount().subtract(amount), now);
            repository.insertLedger(lockedTarget.allocationKey(), lockedTarget.windowId(), "WRITE_OFF", null,
                    amount, amount, now);
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
    // 计量更正
    // ------------------------------------------------------------------

    /**
     * 登记计量更正申请。meterKey 为幂等指纹键：指纹含原核销版本、校正数、读表时刻、原因和操作者，
     * 同键同参重放首次结果，同键改参 409，校验失败事务回滚不占键。窗口关闭后仍可登记。
     */
    public CorrectionResponse submitCorrection(String meterKey, String allocationKey, Long baseVersion,
                                               String correctedAmount, String readingUtc, String reason,
                                               String actor) {
        requireKey("meterKey", meterKey);
        requireKey("allocationKey", allocationKey);
        requireKey("X-Actor-Id", actor);
        if (baseVersion == null || baseVersion < 1) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "baseVersion 必须为大于等于 1 的整数");
        }
        BigDecimal corrected = parseNonNegativeAmount("correctedAmount", correctedAmount);
        long readingNanos = parseInstant("readingUtc", readingUtc);
        String reasonText = requireReason(reason);
        String params = "CORRECTION_SUBMIT|" + meterKey + "|" + allocationKey + "|" + baseVersion + "|"
                + corrected.toPlainString() + "|" + readingNanos + "|" + reasonText + "|" + actor;
        return runCommand("CORRECTION_SUBMIT", meterKey, params, CorrectionResponse.class, () -> {
            AllocationRow allocation = repository.findAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "核销记录不存在: " + allocationKey);
            }
            lockWindowOf(allocation);
            // 窗口锁内重读，避免与取消/转让并发时使用过期状态
            allocation = repository.lockAllocationByKey(allocationKey);
            if (!STATUS_APPROVED.equals(allocation.status())) {
                throw ApiException.conflict("ALLOCATION_NOT_APPROVED",
                        "只有已批准（APPROVED）的核销记录才能登记计量更正");
            }
            repository.insertCorrection(meterKey, allocationKey, allocation.windowId(), baseVersion,
                    corrected, readingNanos, reasonText, actor, nowNanos());
            return toCorrectionResponse(repository.findCorrectionByKey(meterKey));
        });
    }

    /**
     * 批量批准计量更正：批内按更正提交顺序裁决，逐条重算最终余额、已结算转让后的持有额度与窗口储备；
     * 任一更正使历史时点之后可用量为负或侵占储备即 422，全部更正、额度和流水同事务回滚。
     */
    public CorrectionBatchResponse approveCorrections(String commandKey, List<String> meterKeys) {
        requireKey("commandKey", commandKey);
        requireMeterKeys(meterKeys);
        String params = "CORRECTION_APPROVE|" + String.join(",", meterKeys);
        return runCommand("CORRECTION_APPROVE", commandKey, params, CorrectionBatchResponse.class, () -> {
            List<CorrectionRow> batch = loadBatch(meterKeys);
            Map<Long, WindowRow> windows = lockWindowsOf(batch);
            long now = nowNanos();
            for (CorrectionRow correction : batch) {
                if (isClosed(windows.get(correction.windowId()), now)) {
                    throw ApiException.conflict("WINDOW_CLOSED",
                            "窗口已关闭，不能批准影响已结算余额的更正: " + correction.meterKey());
                }
            }
            List<CorrectionResponse> responses = new ArrayList<>();
            // 试算并逐条入账：任一失败抛异常，整批事务回滚
            Map<Long, BigDecimal> projectedByWindow = new HashMap<>();
            for (CorrectionRow correction : batch) {
                CorrectionRow locked = repository.lockCorrectionByKey(correction.meterKey());
                requireRequestable(locked);
                AllocationRow allocation = repository.lockAllocationByKey(locked.allocationKey());
                if (!STATUS_APPROVED.equals(allocation.status())) {
                    throw ApiException.conflict("ALLOCATION_NOT_APPROVED",
                            "核销记录当前不是 APPROVED，不能批准更正: " + locked.meterKey());
                }
                if (locked.baseVersion() != allocation.version()) {
                    throw ApiException.conflict("STALE_VERSION",
                            "原核销版本 " + locked.baseVersion() + " 与当前版本 " + allocation.version()
                                    + " 不一致: " + locked.meterKey());
                }
                WindowRow window = windows.get(locked.windowId());
                BigDecimal projected = projectedByWindow
                        .computeIfAbsent(window.id(), repository::sumApprovedAmount)
                        .subtract(allocation.heldAmount()).add(locked.correctedAmount());
                BigDecimal available = availableTotal(window);
                if (projected.compareTo(available) > 0) {
                    throw ApiException.unprocessable("RESERVE_VIOLATION",
                            "更正 " + locked.meterKey() + " 使历史时点之后可用量为负并侵占储备："
                                    + "更正后已结算总量 " + fmt(projected) + " 超过可用总量 " + fmt(available));
                }
                projectedByWindow.put(window.id(), projected);
                repository.setHeldAmountAndVersion(allocation.id(), locked.correctedAmount(),
                        allocation.version() + 1, now);
                repository.markCorrectionApproved(locked.id(), allocation.heldAmount(), now);
                repository.insertSnapshot(locked.meterKey(), locked.allocationKey(), locked.windowId(),
                        locked.correctedAmount(), locked.readingNanos(), locked.reason(), locked.actor(), now);
                // 更正反向流水：不覆盖原核销，以差额反向入账
                repository.insertLedger(locked.allocationKey(), locked.windowId(), "CORRECTION",
                        locked.meterKey(), locked.correctedAmount().subtract(allocation.heldAmount()),
                        locked.correctedAmount(), now);
                responses.add(toCorrectionResponse(repository.findCorrectionByKey(locked.meterKey())));
            }
            return new CorrectionBatchResponse(responses);
        });
    }

    /**
     * 撤销已批准更正：恢复批准前持有额度并产生撤销反向流水，须同样通过最终态储备校验；
     * 窗口关闭后不能撤销影响已结算余额的更正。
     */
    public CorrectionResponse revokeCorrection(String commandKey, String meterKey) {
        requireKey("commandKey", commandKey);
        requireKey("meterKey", meterKey);
        String params = "CORRECTION_REVOKE|" + meterKey;
        return runCommand("CORRECTION_REVOKE", commandKey, params, CorrectionResponse.class, () -> {
            CorrectionRow correction = repository.findCorrectionByKey(meterKey);
            if (correction == null) {
                throw ApiException.notFound("CORRECTION_NOT_FOUND", "计量更正不存在: " + meterKey);
            }
            WindowRow window = repository.lockWindowById(correction.windowId());
            long now = nowNanos();
            if (isClosed(window, now)) {
                throw ApiException.conflict("WINDOW_CLOSED", "窗口已关闭，不能撤销影响已结算余额的更正");
            }
            CorrectionRow locked = repository.lockCorrectionByKey(meterKey);
            if (STATUS_REVOKED.equals(locked.status())) {
                throw ApiException.conflict("CORRECTION_ALREADY_REVOKED", "更正已撤销，不能重复撤销");
            }
            if (!STATUS_APPROVED.equals(locked.status())) {
                throw ApiException.conflict("CORRECTION_NOT_APPROVED", "只有已批准的更正才能撤销");
            }
            AllocationRow allocation = repository.lockAllocationByKey(locked.allocationKey());
            if (!STATUS_APPROVED.equals(allocation.status())) {
                throw ApiException.conflict("ALLOCATION_NOT_APPROVED",
                        "核销记录当前不是 APPROVED，不能撤销更正");
            }
            BigDecimal restored = locked.previousAmount();
            BigDecimal projected = repository.sumApprovedAmount(window.id())
                    .subtract(allocation.heldAmount()).add(restored);
            BigDecimal available = availableTotal(window);
            if (projected.compareTo(available) > 0) {
                throw ApiException.unprocessable("RESERVE_VIOLATION",
                        "撤销后已结算总量 " + fmt(projected) + " 超过可用总量 " + fmt(available)
                                + "，侵占储备，撤销被拒绝");
            }
            repository.setHeldAmountAndVersion(allocation.id(), restored, allocation.version() + 1, now);
            repository.markCorrectionRevoked(locked.id(), now);
            // 撤销反向流水：把更正差额再反向入账
            repository.insertLedger(allocation.allocationKey(), window.id(), "REVERSAL", meterKey,
                    restored.subtract(allocation.heldAmount()), restored, now);
            return toCorrectionResponse(repository.findCorrectionByKey(meterKey));
        });
    }

    /**
     * 批量批准预检（只读不落库）：按提交顺序试算每个更正，返回可区分的拒绝原因；
     * 已判定可批准的更正计入后续试算状态。
     */
    public CorrectionPreviewResponse previewCorrections(List<String> meterKeys) {
        requireMeterKeys(meterKeys);
        List<CorrectionRow> found = new ArrayList<>();
        Map<String, CorrectionRow> byKey = new LinkedHashMap<>();
        for (String meterKey : meterKeys) {
            CorrectionRow correction = repository.findCorrectionByKey(meterKey);
            if (correction != null) {
                found.add(correction);
                byKey.put(meterKey, correction);
            }
        }
        found.sort(Comparator.comparingLong(CorrectionRow::id));
        long now = nowNanos();
        Map<Long, WindowRow> windows = new HashMap<>();
        Map<Long, BigDecimal> projectedByWindow = new HashMap<>();
        Map<Long, Long> versions = new HashMap<>();
        Map<Long, BigDecimal> helds = new HashMap<>();
        Map<String, CorrectionPreviewItem> results = new LinkedHashMap<>();
        for (CorrectionRow correction : found) {
            results.put(correction.meterKey(), previewOne(correction, now, windows, projectedByWindow,
                    versions, helds));
        }
        List<CorrectionPreviewItem> items = new ArrayList<>();
        for (String meterKey : meterKeys) {
            CorrectionRow correction = byKey.get(meterKey);
            items.add(correction == null
                    ? new CorrectionPreviewItem(meterKey, false, "CORRECTION_NOT_FOUND", "计量更正不存在: " + meterKey)
                    : results.get(meterKey));
        }
        return new CorrectionPreviewResponse(items);
    }

    /** 查询单个计量更正视图。 */
    public CorrectionResponse getCorrection(String meterKey) {
        CorrectionRow correction = repository.findCorrectionByKey(meterKey);
        if (correction == null) {
            throw ApiException.notFound("CORRECTION_NOT_FOUND", "计量更正不存在: " + meterKey);
        }
        return toCorrectionResponse(correction);
    }

    /** 查询更正对应的不可变读表快照。 */
    public SnapshotResponse getSnapshot(String meterKey) {
        SnapshotRow snapshot = repository.findSnapshotByMeterKey(meterKey);
        if (snapshot == null) {
            throw ApiException.notFound("SNAPSHOT_NOT_FOUND", "读表快照不存在（更正尚未批准）: " + meterKey);
        }
        return new SnapshotResponse(snapshot.meterKey(), snapshot.allocationKey(), snapshot.windowId(),
                fmt(snapshot.correctedAmount()), toIso(snapshot.readingNanos()), snapshot.reason(),
                snapshot.actor(), toIso(snapshot.createdNanos()));
    }

    /** 查询核销记录全部流水（原核销、转出、取消、更正与撤销反向流水），按入账顺序即余额演算。 */
    public AllocationLedgerResponse getAllocationLedger(String allocationKey) {
        if (repository.findAllocationByKey(allocationKey) == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND", "核销记录不存在: " + allocationKey);
        }
        List<LedgerEntryResponse> entries = repository.listLedger(allocationKey).stream()
                .map(row -> new LedgerEntryResponse(row.id(), row.entryType(), row.meterKey(),
                        fmt(row.delta()), fmt(row.balanceAfter()), toIso(row.createdNanos())))
                .toList();
        return new AllocationLedgerResponse(allocationKey, entries);
    }

    /** 预检单个更正；通过则把试算结果计入后续更正的试算状态。 */
    private CorrectionPreviewItem previewOne(CorrectionRow correction, long now,
                                             Map<Long, WindowRow> windows,
                                             Map<Long, BigDecimal> projectedByWindow,
                                             Map<Long, Long> versions,
                                             Map<Long, BigDecimal> helds) {
        String meterKey = correction.meterKey();
        if (!STATUS_REQUESTED.equals(correction.status())) {
            String code = STATUS_APPROVED.equals(correction.status())
                    ? "CORRECTION_ALREADY_APPROVED" : "CORRECTION_ALREADY_REVOKED";
            return new CorrectionPreviewItem(meterKey, false, code, "更正当前状态为 " + correction.status());
        }
        WindowRow window = windows.computeIfAbsent(correction.windowId(), repository::findWindowById);
        if (window == null) {
            return new CorrectionPreviewItem(meterKey, false, "WINDOW_NOT_FOUND", "供水窗口不存在");
        }
        if (isClosed(window, now)) {
            return new CorrectionPreviewItem(meterKey, false, "WINDOW_CLOSED",
                    "窗口已关闭，不能批准影响已结算余额的更正");
        }
        AllocationRow allocation = repository.findAllocationByKey(correction.allocationKey());
        if (allocation == null || !STATUS_APPROVED.equals(allocation.status())) {
            return new CorrectionPreviewItem(meterKey, false, "ALLOCATION_NOT_APPROVED",
                    "核销记录当前不是 APPROVED");
        }
        long version = versions.getOrDefault(allocation.id(), allocation.version());
        if (correction.baseVersion() != version) {
            return new CorrectionPreviewItem(meterKey, false, "STALE_VERSION",
                    "原核销版本 " + correction.baseVersion() + " 与当前版本 " + version + " 不一致");
        }
        BigDecimal held = helds.getOrDefault(allocation.id(), allocation.heldAmount());
        BigDecimal projected = projectedByWindow
                .computeIfAbsent(window.id(), repository::sumApprovedAmount)
                .subtract(held).add(correction.correctedAmount());
        BigDecimal available = availableTotal(window);
        if (projected.compareTo(available) > 0) {
            return new CorrectionPreviewItem(meterKey, false, "RESERVE_VIOLATION",
                    "更正后已结算总量 " + fmt(projected) + " 超过可用总量 " + fmt(available) + "，侵占储备");
        }
        projectedByWindow.put(window.id(), projected);
        versions.put(allocation.id(), version + 1);
        helds.put(allocation.id(), correction.correctedAmount());
        return new CorrectionPreviewItem(meterKey, true, null, null);
    }

    /** 加载批内更正并按提交顺序（主键升序）排序；任一不存在即 404。 */
    private List<CorrectionRow> loadBatch(List<String> meterKeys) {
        List<CorrectionRow> batch = new ArrayList<>();
        for (String meterKey : meterKeys) {
            CorrectionRow correction = repository.findCorrectionByKey(meterKey);
            if (correction == null) {
                throw ApiException.notFound("CORRECTION_NOT_FOUND", "计量更正不存在: " + meterKey);
            }
            batch.add(correction);
        }
        batch.sort(Comparator.comparingLong(CorrectionRow::id));
        return batch;
    }

    /** 按窗口 ID 升序锁定批内全部涉及窗口，与批准/转让/限供按事务提交顺序串行裁决。 */
    private Map<Long, WindowRow> lockWindowsOf(List<CorrectionRow> batch) {
        Map<Long, WindowRow> windows = new HashMap<>();
        batch.stream().map(CorrectionRow::windowId).distinct().sorted().forEach(windowId -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            windows.put(windowId, window);
        });
        return windows;
    }

    /** 更正须为 REQUESTED，否则按当前状态给出可区分冲突。 */
    private void requireRequestable(CorrectionRow correction) {
        if (STATUS_APPROVED.equals(correction.status())) {
            throw ApiException.conflict("CORRECTION_ALREADY_APPROVED",
                    "更正已批准，不能重复批准: " + correction.meterKey());
        }
        if (STATUS_REVOKED.equals(correction.status())) {
            throw ApiException.conflict("CORRECTION_ALREADY_REVOKED",
                    "更正已撤销，不能再次批准: " + correction.meterKey());
        }
    }

    private void requireMeterKeys(List<String> meterKeys) {
        if (meterKeys == null || meterKeys.isEmpty()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "meterKeys 不能为空");
        }
        meterKeys.forEach(meterKey -> requireKey("meterKey", meterKey));
    }

    /** 窗口关闭判定：当前时刻不早于窗口结束时刻。 */
    private boolean isClosed(WindowRow window, long nowNanos) {
        return nowNanos >= window.endNanos();
    }

    private CorrectionResponse toCorrectionResponse(CorrectionRow row) {
        return new CorrectionResponse(row.meterKey(), row.allocationKey(), row.windowId(), row.baseVersion(),
                row.previousAmount() == null ? null : fmt(row.previousAmount()), fmt(row.correctedAmount()),
                toIso(row.readingNanos()), row.reason(), row.actor(), row.status(),
                toIso(row.createdNanos()), toIso(row.updatedNanos()));
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
                fmt(row.heldAmount()), row.requester(), row.status(), row.version(),
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

    /** 可替换时钟（UTC 纳秒），测试用于窗口关闭边界；默认系统时钟。 */
    static volatile java.util.function.LongSupplier clockNanos = () -> toNanos(Instant.now());

    static long nowNanos() {
        return clockNanos.getAsLong();
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

    /** 解析校正数量：十进制字符串，大于等于 0，最多 3 位小数。 */
    static BigDecimal parseNonNegativeAmount(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不能为空");
        }
        String trimmed = value.trim();
        if (!AMOUNT_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 必须为大于等于 0 的十进制字符串，最多 3 位小数: " + value);
        }
        return new BigDecimal(trimmed);
    }

    /** 校验更正原因：去空白后 1..512 字符。 */
    static String requireReason(String reason) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty() || trimmed.length() > 512) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "reason 不能为空且最长 512 字符");
        }
        return trimmed;
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
