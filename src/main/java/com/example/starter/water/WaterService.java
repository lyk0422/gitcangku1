package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.EvaporationRateRow;
import com.example.starter.water.WaterRepository.SettlementRow;
import com.example.starter.water.WaterRepository.SettlementTotals;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.BatchSettleResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.EvaporationDiagnosticsResponse;
import com.example.starter.water.dto.Dtos.EvaporationRateResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.SettleItemRequest;
import com.example.starter.water.dto.Dtos.SettlementListResponse;
import com.example.starter.water.dto.Dtos.SettlementResponse;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    static final String KIND_SETTLEMENT = "SETTLEMENT";
    static final String KIND_CORRECTION = "CORRECTION";

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("\\d{1,16}(\\.\\d{1,3})?");
    private static final Pattern RATE_PATTERN = Pattern.compile("\\d(\\.\\d{1,4})?");
    private static final BigDecimal MAX_RATE = new BigDecimal("0.2");
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
    // 蒸发率登记
    // ------------------------------------------------------------------

    /**
     * 登记渠道某日（UTC 自然日）蒸发率新版本。该日首次核销后禁止再登记（409 RATE_LOCKED）；
     * 版本行登记后不可变，核销记录固化当时版本。
     */
    public EvaporationRateResponse registerEvaporationRate(String commandKey, String channelId, String dateStr,
                                                           String rateStr) {
        requireKey("commandKey", commandKey);
        requireKey("channelId", channelId);
        LocalDate date = parseRateDate("date", dateStr);
        BigDecimal rate = parseRate("rate", rateStr);
        String params = "EVAP_RATE_REGISTER|" + channelId + "|" + date + "|" + rate.toPlainString();
        return runCommand("EVAP_RATE_REGISTER", commandKey, params, EvaporationRateResponse.class, () -> {
            // 锁定当日最新版本行：与核销（同样锁该行）按事务提交顺序串行裁决
            EvaporationRateRow latest = repository.lockLatestRateVersion(channelId, date);
            if (latest != null && repository.existsSettlementForChannelDate(channelId, date)) {
                throw ApiException.conflict("RATE_LOCKED",
                        "渠道 " + channelId + " 在 " + date + " 已发生首次核销，蒸发率不可再修改");
            }
            int version = latest == null ? 1 : latest.version() + 1;
            long id;
            try {
                id = repository.insertEvaporationRate(channelId, date, version, rate, nowNanos());
            } catch (DuplicateKeyException e) {
                // 并发首次登记同一渠道同一日：唯一约束兜底
                throw ApiException.conflict("RATE_REGISTER_CONFLICT",
                        "渠道 " + channelId + " 在 " + date + " 的蒸发率正在并发登记，请重试");
            }
            return toRateResponse(repository.findRateById(id));
        });
    }

    // ------------------------------------------------------------------
    // 蒸发损耗核销
    // ------------------------------------------------------------------

    /** 单笔核销送达水量：上游扣减=送达+损耗（损耗按银行家舍入到两位小数），同事务写入。 */
    public SettlementResponse settle(String commandKey, String allocationKey, String dateStr,
                                     String deliveredStr) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        LocalDate date = parseRateDate("date", dateStr);
        BigDecimal delivered = parseAmount("delivered", deliveredStr);
        String params = "SETTLEMENT|" + allocationKey + "|" + date + "|" + delivered.toPlainString();
        return runCommand("SETTLEMENT", commandKey, params, SettlementResponse.class, () -> {
            List<SettlementRow> rows = doSettle(
                    List.of(new SettleItem(allocationKey, date, delivered)), commandKey);
            return toSettlementResponse(rows.get(0));
        });
    }

    /**
     * 批量核销：锁内按完整最终余额与逐笔舍入口径预校验，任一失败整批回滚；
     * 全部通过后在一个事务内提交所有上游扣减与核销记录。
     */
    public BatchSettleResponse settleBatch(String commandKey, List<SettleItemRequest> items) {
        requireKey("commandKey", commandKey);
        if (items == null || items.isEmpty()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "items 不能为空");
        }
        List<SettleItem> parsed = new ArrayList<>();
        for (SettleItemRequest item : items) {
            if (item == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "items 不能包含空项");
            }
            requireKey("allocationKey", item.allocationKey());
            parsed.add(new SettleItem(item.allocationKey(), parseRateDate("date", item.date()),
                    parseAmount("delivered", item.delivered())));
        }
        String params = "SETTLEMENT_BATCH|" + parsed.stream()
                .map(i -> i.allocationKey() + "|" + i.date() + "|" + i.delivered().toPlainString())
                .collect(Collectors.joining(";"));
        return runCommand("SETTLEMENT_BATCH", commandKey, params, BatchSettleResponse.class, () -> {
            List<SettlementRow> rows = doSettle(parsed, commandKey);
            return new BatchSettleResponse(rows.stream().map(this::toSettlementResponse).toList());
        });
    }

    /**
     * 反向更正：已结算核销不可改写，只能新增一条 CORRECTION 记录（金额为原记录相反数）并回补上游额度。
     * 同一核销只能更正一次；更正记录本身不能再被更正。
     */
    public SettlementResponse correctSettlement(String commandKey, long settlementId) {
        requireKey("commandKey", commandKey);
        String params = "SETTLEMENT_CORRECT|" + settlementId;
        return runCommand("SETTLEMENT_CORRECT", commandKey, params, SettlementResponse.class, () -> {
            SettlementRow original = repository.findSettlementById(settlementId);
            if (original == null) {
                throw ApiException.notFound("SETTLEMENT_NOT_FOUND", "核销记录不存在: " + settlementId);
            }
            // 与核销/转让/取消同一窗口锁顺序：窗口 -> 申请 -> 核销记录
            WindowRow window = repository.lockWindowById(original.windowId());
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + original.windowId());
            }
            AllocationRow allocation = repository.lockAllocationByKey(original.allocationKey());
            SettlementRow locked = repository.lockSettlementById(settlementId);
            if (!KIND_SETTLEMENT.equals(locked.kind())) {
                throw ApiException.conflict("NOT_A_SETTLEMENT", "更正记录不能再次被更正: " + settlementId);
            }
            if (repository.existsCorrectionFor(settlementId)) {
                throw ApiException.conflict("ALREADY_CORRECTED", "核销记录已被更正，不能重复更正: " + settlementId);
            }
            BigDecimal newHeld = allocation.heldAmount().add(locked.deduction());
            if (newHeld.compareTo(allocation.amount()) > 0) {
                throw ApiException.unprocessable("CORRECTION_OVERFLOW",
                        "更正回补后持有额度 " + fmt(newHeld) + " 将超过原申请水量 " + fmt(allocation.amount())
                                + "（当前持有 " + fmt(allocation.heldAmount()) + "，回补 "
                                + fmt(locked.deduction()) + "）");
            }
            long now = nowNanos();
            repository.incrementHeldAmount(allocation.id(), locked.deduction(), now);
            long id = repository.insertSettlement(locked.allocationKey(), locked.windowId(), locked.channelId(),
                    locked.rateDate(), locked.rateVersion(), locked.rate(), locked.delivered().negate(),
                    locked.loss().negate(), locked.deduction().negate(), newHeld, KIND_CORRECTION,
                    locked.id(), 0, commandKey, now);
            return toSettlementResponse(repository.findSettlementById(id));
        });
    }

    /** 核销记录明细查询（只读）。 */
    public SettlementResponse getSettlement(long settlementId) {
        SettlementRow row = repository.findSettlementById(settlementId);
        if (row == null) {
            throw ApiException.notFound("SETTLEMENT_NOT_FOUND", "核销记录不存在: " + settlementId);
        }
        return toSettlementResponse(row);
    }

    /** 申请维度核销历史（含更正记录），按发生顺序返回，只读。 */
    public SettlementListResponse getAllocationSettlements(String allocationKey) {
        if (repository.findAllocationByKey(allocationKey) == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
        }
        List<SettlementResponse> settlements = repository.listSettlementsByAllocation(allocationKey).stream()
                .map(this::toSettlementResponse).toList();
        return new SettlementListResponse(allocationKey, settlements);
    }

    /** 渠道日蒸发诊断：版本历史、是否已锁定、核销净额汇总，只读。 */
    public EvaporationDiagnosticsResponse getEvaporationDiagnostics(String channelId, String dateStr) {
        requireKey("channelId", channelId);
        LocalDate date = parseRateDate("date", dateStr);
        List<EvaporationRateRow> versions = repository.listRateVersions(channelId, date);
        EvaporationRateRow latest = versions.isEmpty() ? null : versions.get(versions.size() - 1);
        boolean locked = repository.existsSettlementForChannelDate(channelId, date);
        SettlementTotals totals = repository.sumSettlementsForChannelDate(channelId, date);
        return new EvaporationDiagnosticsResponse(channelId, date.toString(),
                latest == null ? null : latest.version(), latest == null ? null : fmt(latest.rate()), locked,
                versions.stream().map(this::toRateResponse).toList(),
                fmt(totals.netDelivered()), fmt(totals.netLoss()), fmt(totals.netDeduction()),
                totals.settlementCount());
    }

    // ------------------------------------------------------------------
    // 核销内部实现
    // ------------------------------------------------------------------

    /** 批量核销项（已解析）。 */
    private record SettleItem(String allocationKey, LocalDate date, BigDecimal delivered) {
    }

    /** 单笔计算结果。 */
    private record Computed(AllocationRow allocation, WindowRow window, LocalDate date, BigDecimal delivered,
                            EvaporationRateRow rate, BigDecimal loss, BigDecimal deduction) {
    }

    /**
     * 在命令事务内执行批量核销：先按确定顺序锁定窗口与申请，再在锁内按当日最新率逐笔计算、
     * 按完整最终余额预校验，全部通过后才写入上游扣减与不可变核销记录。
     */
    private List<SettlementRow> doSettle(List<SettleItem> items, String commandKey) {
        // 解析申请与窗口（无锁快速失败），并校验核销日落在配水窗口内
        List<AllocationRow> allocations = new ArrayList<>();
        List<WindowRow> windows = new ArrayList<>();
        for (SettleItem item : items) {
            AllocationRow allocation = repository.findAllocationByKey(item.allocationKey());
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + item.allocationKey());
            }
            WindowRow window = repository.findWindowById(allocation.windowId());
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + allocation.windowId());
            }
            checkDateInWindow(item.date(), window);
            allocations.add(allocation);
            windows.add(window);
        }
        // 按确定顺序锁定窗口行与申请行，与转让/取消/更正/限供按事务提交顺序串行裁决
        windows.stream().mapToLong(WindowRow::id).distinct().sorted()
                .forEach(repository::lockWindowById);
        Map<String, AllocationRow> lockedAllocations = new LinkedHashMap<>();
        allocations.stream().map(AllocationRow::allocationKey).distinct().sorted()
                .forEach(key -> lockedAllocations.put(key, repository.lockAllocationByKey(key)));
        // 锁内逐笔计算：锁定当日最新率版本行，阻断并发改率；损耗银行家舍入到两位小数
        Map<String, EvaporationRateRow> rates = new HashMap<>();
        List<Computed> computedList = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            SettleItem item = items.get(i);
            WindowRow window = windows.get(i);
            String rateKey = window.channelId() + "|" + item.date();
            EvaporationRateRow rate = rates.get(rateKey);
            if (rate == null) {
                rate = repository.lockLatestRateVersion(window.channelId(), item.date());
                if (rate == null) {
                    throw ApiException.unprocessable("RATE_NOT_FOUND",
                            "渠道 " + window.channelId() + " 未登记 " + item.date() + " 的蒸发率");
                }
                rates.put(rateKey, rate);
            }
            computedList.add(computeItem(lockedAllocations.get(item.allocationKey()), window, item, rate));
        }
        // 完整最终余额预校验：同一上游多笔扣减累计后不得超过当前持有额度
        Map<String, BigDecimal> requiredByAllocation = new LinkedHashMap<>();
        for (Computed computed : computedList) {
            requiredByAllocation.merge(computed.allocation().allocationKey(), computed.deduction(),
                    BigDecimal::add);
        }
        requiredByAllocation.forEach((allocationKey, required) -> {
            BigDecimal held = lockedAllocations.get(allocationKey).heldAmount();
            if (held.compareTo(required) < 0) {
                throw ApiException.unprocessable("QUOTA_EXCEEDED",
                        "上游剩余额度不足：当前持有 " + fmt(held) + "，本批需要扣减 " + fmt(required)
                                + "，差额 " + fmt(required.subtract(held)) + "（申请 " + allocationKey + "）");
            }
        });
        // 全部通过后统一写入：逐笔扣减上游额度并固化核销记录
        long now = nowNanos();
        Map<String, BigDecimal> runningHeld = new LinkedHashMap<>();
        lockedAllocations.forEach((key, row) -> runningHeld.put(key, row.heldAmount()));
        List<SettlementRow> rows = new ArrayList<>();
        int batchNo = 0;
        for (Computed computed : computedList) {
            String allocationKey = computed.allocation().allocationKey();
            BigDecimal remaining = runningHeld.get(allocationKey).subtract(computed.deduction());
            runningHeld.put(allocationKey, remaining);
            long id = repository.insertSettlement(allocationKey, computed.window().id(),
                    computed.window().channelId(), computed.date(), computed.rate().version(),
                    computed.rate().rate(), computed.delivered(), computed.loss(), computed.deduction(),
                    remaining, KIND_SETTLEMENT, null, batchNo++, commandKey, now);
            rows.add(repository.findSettlementById(id));
        }
        lockedAllocations.forEach((key, row) -> {
            BigDecimal total = row.heldAmount().subtract(runningHeld.get(key));
            if (total.signum() > 0) {
                repository.decrementHeldAmount(row.id(), total, now);
            }
        });
        return rows;
    }

    /** 逐笔计算损耗与上游扣减，并校验舍入后守恒（扣减=送达+损耗，两位小数口径）。 */
    private Computed computeItem(AllocationRow allocation, WindowRow window, SettleItem item,
                                 EvaporationRateRow rate) {
        BigDecimal loss = item.delivered().multiply(rate.rate()).setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal deduction = item.delivered().add(loss);
        if (deduction.stripTrailingZeros().scale() > 2) {
            throw ApiException.unprocessable("CONSERVATION_VIOLATED",
                    "舍入后守恒不成立：送达 " + fmt(item.delivered()) + " + 损耗 " + fmt(loss) + " = "
                            + fmt(deduction) + " 超出两位小数口径，无法保证上游扣减=送达+损耗");
        }
        return new Computed(allocation, window, item.date(), item.delivered(), rate, loss, deduction);
    }

    /** 核销日（UTC 自然日，左闭右开）必须与配水窗口 [start, end) 相交，否则 422。 */
    private void checkDateInWindow(LocalDate date, WindowRow window) {
        long dayStart = toNanos(date.atStartOfDay().toInstant(ZoneOffset.UTC));
        long dayEnd = toNanos(date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        if (!(dayStart < window.endNanos() && window.startNanos() < dayEnd)) {
            throw ApiException.unprocessable("DATE_OUT_OF_WINDOW",
                    "核销日期 " + date + " 不在配水窗口 [" + toIso(window.startNanos()) + ", "
                            + toIso(window.endNanos()) + ") 内");
        }
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

    private EvaporationRateResponse toRateResponse(EvaporationRateRow row) {
        return new EvaporationRateResponse(row.id(), row.channelId(), row.rateDate().toString(), row.version(),
                fmt(row.rate()), toIso(row.createdNanos()));
    }

    private SettlementResponse toSettlementResponse(SettlementRow row) {
        return new SettlementResponse(row.id(), row.allocationKey(), row.windowId(), row.channelId(),
                row.rateDate().toString(), row.rateVersion(), fmt(row.rate()), fmt(row.delivered()),
                fmt(row.loss()), fmt(row.deduction()), row.kind(), row.correctsId(), row.batchNo(),
                fmt(row.upstreamRemaining()), toIso(row.createdNanos()));
    }

    /** 解析 UTC 自然日（yyyy-MM-dd）。 */
    static LocalDate parseRateDate(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不能为空");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 不是合法的 UTC 自然日（yyyy-MM-dd）: " + value);
        }
    }

    /** 解析蒸发率：小数 0～0.2（0～20%），最多四位小数。 */
    static BigDecimal parseRate(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不能为空");
        }
        String trimmed = value.trim();
        if (!RATE_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 必须为 0～0.2 的十进制字符串，最多四位小数: " + value);
        }
        BigDecimal rate = new BigDecimal(trimmed);
        if (rate.compareTo(MAX_RATE) > 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 不能超过 0.2（20%）: " + value);
        }
        return rate;
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
