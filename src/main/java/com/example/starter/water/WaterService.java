package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.ChannelRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.OutageRow;
import com.example.starter.water.WaterRepository.RiskRow;
import com.example.starter.water.WaterRepository.SettlementRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.BatchSettleItem;
import com.example.starter.water.dto.Dtos.BatchSettleResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.OutageImpactResponse;
import com.example.starter.water.dto.Dtos.OutageResponse;
import com.example.starter.water.dto.Dtos.RiskListResponse;
import com.example.starter.water.dto.Dtos.RiskResponse;
import com.example.starter.water.dto.Dtos.SettlementCheckResponse;
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
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
    private final Clock clock;

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
            long now = nowNanos();
            ensureChannel(channelId, now);
            long id = repository.insertWindow(windowKey, channelId, startNanos, endNanos, planned, now);
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
            // 先锁渠道行（与停运变更、核销按事务提交顺序串行裁决），再锁窗口行
            long now = nowNanos();
            WindowRow sourceWindow = repository.findWindowById(source.windowId());
            lockChannel(sourceWindow.channelId(), now);
            // 再锁窗口行，与普通批准、取消、限供调整按事务提交顺序串行裁决
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
            // 转让结算时申请窗口与任何生效停运窗口相交即 422
            requireNoEffectiveOutage(window, now);
            // 风险申请不能再次转让
            if (repository.existsRiskForAllocation(sourceAllocationKey)) {
                throw ApiException.conflict("SUPPLY_RISK",
                        "转出申请存在供应风险，不能再次转让: " + sourceAllocationKey);
            }
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
    // 停运窗口
    // ------------------------------------------------------------------

    /**
     * 下达停运窗口：UTC 左闭右开，同渠道生效窗口不得重叠（端点相接合法）。
     * 渠道行锁内校验 expectedVersion，变更后版本 +1；已批准未结算的受影响申请写入不可变供应风险。
     * 幂等指纹含渠道版本、规范化申请集合、时段与操作；失败随事务回滚不占键。
     */
    public OutageResponse createOutage(String commandKey, String outageKey, String channelId,
                                       Long expectedVersion, String startUtc, String endUtc,
                                       List<String> allocationKeys) {
        requireKey("commandKey", commandKey);
        requireKey("outageKey", outageKey);
        requireKey("channelId", channelId);
        long version = requireVersion(expectedVersion);
        long startNanos = parseInstant("startUtc", startUtc);
        long endNanos = parseInstant("endUtc", endUtc);
        if (startNanos >= endNanos) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "startUtc 必须早于 endUtc");
        }
        List<String> affected = normalizeAllocationKeys(allocationKeys);
        String params = "OUTAGE_CREATE|" + outageKey + "|" + channelId + "|" + version + "|" + startNanos
                + "|" + endNanos + "|" + String.join(",", affected);
        return runCommand("OUTAGE_CREATE", commandKey, params, OutageResponse.class, () -> {
            if (repository.findOutageByKey(outageKey) != null) {
                throw ApiException.conflict("OUTAGE_KEY_REUSED", "outageKey 已被使用: " + outageKey);
            }
            long now = nowNanos();
            ChannelRow channel = lockChannel(channelId, now);
            requireChannelVersion(channel, version);
            if (repository.existsOverlappingOutage(channelId, startNanos, endNanos)) {
                throw ApiException.conflict("OUTAGE_OVERLAP", "同一渠道存在时间重叠的生效停运窗口");
            }
            List<AllocationRow> allocations = new ArrayList<>();
            for (String allocationKey : affected) {
                AllocationRow allocation = repository.findAllocationByKey(allocationKey);
                if (allocation == null) {
                    throw ApiException.notFound("ALLOCATION_NOT_FOUND", "受影响申请不存在: " + allocationKey);
                }
                WindowRow window = repository.findWindowById(allocation.windowId());
                if (!window.channelId().equals(channelId)) {
                    throw ApiException.conflict("ALLOCATION_CHANNEL_MISMATCH",
                            "受影响申请不属于该渠道: " + allocationKey);
                }
                allocations.add(allocation);
            }
            repository.bumpChannelVersion(channelId, now);
            long id = repository.insertOutage(outageKey, channelId, startNanos, endNanos, version + 1, now);
            for (String allocationKey : affected) {
                repository.insertOutageAllocation(id, allocationKey);
            }
            // 已批准未结算的受影响申请写入不可变供应风险，已批准未结算转让不撤销
            for (AllocationRow allocation : allocations) {
                if (STATUS_APPROVED.equals(allocation.status()) && allocation.heldAmount().signum() > 0) {
                    repository.insertRisk(id, allocation.allocationKey(), now);
                }
            }
            return toOutageResponse(repository.findOutageById(id));
        });
    }

    /** 删除停运窗口：仅未开始（当前时刻早于开始时刻）可删除；已开始只能记录提前恢复。 */
    public OutageResponse deleteOutage(String commandKey, String channelId, String outageKey,
                                       Long expectedVersion) {
        requireKey("commandKey", commandKey);
        requireKey("channelId", channelId);
        requireKey("outageKey", outageKey);
        long version = requireVersion(expectedVersion);
        String params = "OUTAGE_DELETE|" + channelId + "|" + outageKey + "|" + version;
        return runCommand("OUTAGE_DELETE", commandKey, params, OutageResponse.class, () -> {
            long now = nowNanos();
            ChannelRow channel = lockChannel(channelId, now);
            requireChannelVersion(channel, version);
            OutageRow outage = requireOutage(channelId, outageKey);
            if ("DELETED".equals(outage.status())) {
                throw ApiException.conflict("OUTAGE_DELETED", "停运窗口已删除: " + outageKey);
            }
            if (now >= outage.startNanos()) {
                throw ApiException.conflict("OUTAGE_ALREADY_STARTED",
                        "已开始停运窗口不可删除，只能记录提前恢复时刻");
            }
            repository.markOutageDeleted(outage.id(), version + 1);
            repository.bumpChannelVersion(channelId, now);
            return toOutageResponse(repository.findOutageById(outage.id()));
        });
    }

    /** 记录提前恢复时刻：不得早于当前时刻，仅影响之后的核销与转让结算。 */
    public OutageResponse recoverOutage(String commandKey, String channelId, String outageKey,
                                        Long expectedVersion, String recoveredUtc) {
        requireKey("commandKey", commandKey);
        requireKey("channelId", channelId);
        requireKey("outageKey", outageKey);
        long version = requireVersion(expectedVersion);
        long recoveredNanos = parseInstant("recoveredUtc", recoveredUtc);
        String params = "OUTAGE_RECOVER|" + channelId + "|" + outageKey + "|" + version + "|" + recoveredNanos;
        return runCommand("OUTAGE_RECOVER", commandKey, params, OutageResponse.class, () -> {
            long now = nowNanos();
            ChannelRow channel = lockChannel(channelId, now);
            requireChannelVersion(channel, version);
            OutageRow outage = requireOutage(channelId, outageKey);
            if ("DELETED".equals(outage.status())) {
                throw ApiException.conflict("OUTAGE_DELETED", "停运窗口已删除: " + outageKey);
            }
            if (outage.recoveredNanos() != null) {
                throw ApiException.conflict("OUTAGE_ALREADY_RECOVERED", "停运窗口已记录提前恢复");
            }
            if (now < outage.startNanos()) {
                throw ApiException.conflict("OUTAGE_NOT_STARTED", "停运窗口尚未开始，可直接删除");
            }
            if (now >= outage.endNanos()) {
                throw ApiException.conflict("OUTAGE_ALREADY_ENDED", "停运窗口已结束，无需恢复");
            }
            if (recoveredNanos < now) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "恢复时刻不得早于当前时刻");
            }
            if (recoveredNanos >= outage.endNanos()) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "恢复时刻必须早于停运结束时刻");
            }
            repository.markOutageRecovered(outage.id(), recoveredNanos, version + 1);
            repository.bumpChannelVersion(channelId, now);
            return toOutageResponse(repository.findOutageById(outage.id()));
        });
    }

    /** 查询停运影响：停运窗口 + 受影响申请当前状态 + 已写入的不可变供应风险。 */
    public OutageImpactResponse getOutageImpact(String channelId, String outageKey) {
        OutageRow outage = repository.findOutageByKey(outageKey);
        if (outage == null || !outage.channelId().equals(channelId)) {
            throw ApiException.notFound("OUTAGE_NOT_FOUND", "停运窗口不存在: " + outageKey);
        }
        List<AllocationResponse> affected = repository.listOutageAllocationKeys(outage.id()).stream()
                .map(repository::findAllocationByKey)
                .map(this::toAllocationResponse)
                .toList();
        List<RiskResponse> risks = repository.listRisksByOutage(outage.id()).stream()
                .map(risk -> toRiskResponse(risk, outage.outageKey()))
                .toList();
        return new OutageImpactResponse(toOutageResponse(outage), affected, risks);
    }

    /** 查询申请的全部不可变供应风险。 */
    public RiskListResponse getAllocationRisks(String allocationKey) {
        if (repository.findAllocationByKey(allocationKey) == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
        }
        List<RiskResponse> risks = repository.listRisksByAllocation(allocationKey).stream()
                .map(risk -> toRiskResponse(risk, repository.findOutageById(risk.outageId()).outageKey()))
                .toList();
        return new RiskListResponse(allocationKey, risks);
    }

    // ------------------------------------------------------------------
    // 核销
    // ------------------------------------------------------------------

    /**
     * 单笔核销：申请必须为 APPROVED，核销额不超过持有余额，且申请窗口不与任何生效停运窗口相交，
     * 否则 422；持有额度扣减与不可变流水在同一事务提交。
     */
    public SettlementResponse settle(String commandKey, String settlementKey, String allocationKey,
                                     String amount) {
        requireKey("commandKey", commandKey);
        requireKey("settlementKey", settlementKey);
        requireKey("allocationKey", allocationKey);
        BigDecimal qty = parseAmount("amount", amount);
        String params = "SETTLE|" + settlementKey + "|" + allocationKey + "|" + qty.toPlainString();
        return runCommand("SETTLE", commandKey, params, SettlementResponse.class, () -> {
            if (repository.findSettlementByKey(settlementKey) != null) {
                throw ApiException.conflict("SETTLEMENT_KEY_REUSED", "settlementKey 已被使用: " + settlementKey);
            }
            AllocationRow allocation = repository.findAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            long now = nowNanos();
            WindowRow window = repository.findWindowById(allocation.windowId());
            lockChannel(window.channelId(), now);
            AllocationRow locked = repository.lockAllocationByKey(allocationKey);
            requireSettable(locked, window, qty, now);
            repository.decrementHeldAmount(locked.id(), qty, now);
            repository.insertSettlement(settlementKey, null, allocationKey, window.id(), qty, now);
            return toSettlementResponse(repository.findSettlementByKey(settlementKey));
        });
    }

    /**
     * 批量核销：先按最终渠道容量、申请余额和停运后态预校验全部明细，任一失败全部水量、
     * 余额和流水随事务回滚；全部通过后在同一事务扣减并写入不可变流水。
     */
    public BatchSettleResponse settleBatch(String commandKey, String batchKey, List<BatchSettleItem> items) {
        requireKey("commandKey", commandKey);
        requireKey("batchKey", batchKey);
        if (items == null || items.isEmpty()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "items 不能为空");
        }
        List<String> allocationKeys = new ArrayList<>();
        List<BigDecimal> amounts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (BatchSettleItem item : items) {
            if (item == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "items 不能包含空明细");
            }
            requireKey("allocationKey", item.allocationKey());
            if (!seen.add(item.allocationKey())) {
                throw ApiException.badRequest("INVALID_ARGUMENT",
                        "同一申请在批量核销中重复: " + item.allocationKey());
            }
            allocationKeys.add(item.allocationKey());
            amounts.add(parseAmount("amount", item.amount()));
        }
        StringBuilder params = new StringBuilder("SETTLE_BATCH|").append(batchKey);
        for (int i = 0; i < allocationKeys.size(); i++) {
            params.append('|').append(allocationKeys.get(i)).append('=').append(amounts.get(i).toPlainString());
        }
        return runCommand("SETTLE_BATCH", commandKey, params.toString(), BatchSettleResponse.class, () -> {
            if (repository.existsSettlementBatch(batchKey)) {
                throw ApiException.conflict("BATCH_KEY_REUSED", "batchKey 已被使用: " + batchKey);
            }
            long now = nowNanos();
            // 解析申请与窗口，任一不存在即 404，事务回滚不留半成品
            List<AllocationRow> found = new ArrayList<>();
            Map<Long, WindowRow> windows = new HashMap<>();
            for (String allocationKey : allocationKeys) {
                AllocationRow allocation = repository.findAllocationByKey(allocationKey);
                if (allocation == null) {
                    throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
                }
                found.add(allocation);
                windows.computeIfAbsent(allocation.windowId(), repository::findWindowById);
            }
            // 按渠道 ID 排序依次锁定渠道行，与停运变更、其他核销按提交顺序串行裁决
            windows.values().stream().map(WindowRow::channelId).distinct().sorted()
                    .forEach(channelId -> lockChannel(channelId, now));
            // 按主键排序锁定申请行，得到最新状态与持有余额
            Map<String, AllocationRow> locked = new HashMap<>();
            found.stream().sorted(Comparator.comparingLong(AllocationRow::id)).forEach(allocation ->
                    locked.put(allocation.allocationKey(),
                            repository.lockAllocationByKey(allocation.allocationKey())));
            // 预校验：申请状态、停运后态、申请余额
            Map<Long, BigDecimal> batchSumByWindow = new HashMap<>();
            for (int i = 0; i < allocationKeys.size(); i++) {
                String allocationKey = allocationKeys.get(i);
                BigDecimal qty = amounts.get(i);
                AllocationRow allocation = locked.get(allocationKey);
                WindowRow window = windows.get(allocation.windowId());
                requireSettable(allocation, window, qty, now);
                batchSumByWindow.merge(window.id(), qty, BigDecimal::add);
            }
            // 预校验：最终渠道容量（限供调整后的可用总量）不得被累计核销超出
            for (Map.Entry<Long, BigDecimal> entry : batchSumByWindow.entrySet()) {
                WindowRow window = windows.get(entry.getKey());
                BigDecimal settled = repository.sumSettledAmount(window.id());
                BigDecimal available = availableTotal(window);
                if (settled.add(entry.getValue()).compareTo(available) > 0) {
                    throw ApiException.unprocessable("CHANNEL_CAPACITY_EXCEEDED",
                            "窗口 " + window.id() + " 累计核销将超过最终可用总量 " + fmt(available));
                }
            }
            // 全部通过后同事务扣减余额并写入流水
            List<SettlementResponse> settlements = new ArrayList<>();
            for (int i = 0; i < allocationKeys.size(); i++) {
                String allocationKey = allocationKeys.get(i);
                BigDecimal qty = amounts.get(i);
                AllocationRow allocation = locked.get(allocationKey);
                String settlementKey = batchKey + "/" + allocationKey;
                repository.decrementHeldAmount(allocation.id(), qty, now);
                repository.insertSettlement(settlementKey, batchKey, allocationKey,
                        allocation.windowId(), qty, now);
                settlements.add(toSettlementResponse(repository.findSettlementByKey(settlementKey)));
            }
            return new BatchSettleResponse(batchKey, settlements);
        });
    }

    /** 查询核销可行性：不可核销时给出可区分拒绝原因（不留下任何状态）。 */
    public SettlementCheckResponse checkSettlement(String allocationKey, String amount) {
        requireKey("allocationKey", allocationKey);
        BigDecimal qty = amount == null ? null : parseAmount("amount", amount);
        AllocationRow allocation = repository.findAllocationByKey(allocationKey);
        if (allocation == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
        }
        WindowRow window = repository.findWindowById(allocation.windowId());
        long now = nowNanos();
        if (!STATUS_APPROVED.equals(allocation.status())) {
            return new SettlementCheckResponse(allocationKey, false, "ALLOCATION_NOT_APPROVED",
                    "申请状态为 " + allocation.status() + "，仅 APPROVED 可核销");
        }
        List<OutageRow> outages = repository.listEffectiveOutages(window.channelId(), window.startNanos(),
                window.endNanos(), now);
        if (!outages.isEmpty()) {
            return new SettlementCheckResponse(allocationKey, false, "OUTAGE_INTERSECT",
                    "申请窗口与生效停运窗口相交: " + outages.get(0).outageKey());
        }
        if (qty != null && allocation.heldAmount().compareTo(qty) < 0) {
            return new SettlementCheckResponse(allocationKey, false, "INSUFFICIENT_BALANCE",
                    "申请余额 " + fmt(allocation.heldAmount()) + " 不足，无法核销 " + fmt(qty));
        }
        return new SettlementCheckResponse(allocationKey, true, null, null);
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

    /** 确保渠道行存在；并发重复创建时忽略唯一键冲突。 */
    private void ensureChannel(String channelId, long now) {
        if (repository.findChannel(channelId) != null) {
            return;
        }
        try {
            repository.insertChannel(channelId, now);
        } catch (DuplicateKeyException ignored) {
            // 并发创建同一渠道：已存在即可
        }
    }

    /** 确保渠道存在并锁定渠道行（FOR UPDATE），串行化停运变更、核销与转让结算。 */
    private ChannelRow lockChannel(String channelId, long now) {
        ensureChannel(channelId, now);
        return repository.lockChannel(channelId);
    }

    /** 校验渠道版本乐观锁：expectedVersion 必须等于当前版本，否则 409。 */
    private void requireChannelVersion(ChannelRow channel, long expectedVersion) {
        if (channel.version() != expectedVersion) {
            throw ApiException.conflict("CHANNEL_VERSION_CONFLICT",
                    "渠道版本已变更为 " + channel.version() + "，请刷新后重试（期望 " + expectedVersion + "）");
        }
    }

    /** 解析渠道版本号：非空且不为负。 */
    private static long requireVersion(Long expectedVersion) {
        if (expectedVersion == null || expectedVersion < 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "expectedVersion 不能为空且不得为负");
        }
        return expectedVersion;
    }

    /** 按业务键获取停运窗口并校验归属渠道，不存在或渠道不符返回 404。 */
    private OutageRow requireOutage(String channelId, String outageKey) {
        OutageRow outage = repository.findOutageByKey(outageKey);
        if (outage == null || !outage.channelId().equals(channelId)) {
            throw ApiException.notFound("OUTAGE_NOT_FOUND", "停运窗口不存在: " + outageKey);
        }
        return outage;
    }

    /** 规范化受影响申请集合：逐个校验格式、去重并排序（幂等指纹成分）。 */
    private static List<String> normalizeAllocationKeys(List<String> allocationKeys) {
        if (allocationKeys == null || allocationKeys.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new TreeSet<>();
        for (String allocationKey : allocationKeys) {
            requireKey("allocationKeys", allocationKey);
            normalized.add(allocationKey);
        }
        return List.copyOf(normalized);
    }

    /** 生效停运窗口与申请窗口相交即 422（核销与转让结算共用）。 */
    private void requireNoEffectiveOutage(WindowRow window, long now) {
        List<OutageRow> outages = repository.listEffectiveOutages(window.channelId(), window.startNanos(),
                window.endNanos(), now);
        if (!outages.isEmpty()) {
            throw ApiException.unprocessable("OUTAGE_INTERSECT",
                    "申请窗口与生效停运窗口 " + outages.get(0).outageKey() + " 相交，禁止核销或转让结算");
        }
    }

    /** 核销前置校验：状态、停运后态、申请余额。 */
    private void requireSettable(AllocationRow allocation, WindowRow window, BigDecimal qty, long now) {
        if (!STATUS_APPROVED.equals(allocation.status())) {
            throw ApiException.conflict("ALLOCATION_NOT_APPROVED",
                    "申请 " + allocation.allocationKey() + " 状态为 " + allocation.status() + "，仅 APPROVED 可核销");
        }
        requireNoEffectiveOutage(window, now);
        if (allocation.heldAmount().compareTo(qty) < 0) {
            throw ApiException.unprocessable("INSUFFICIENT_BALANCE",
                    "申请 " + allocation.allocationKey() + " 余额 " + fmt(allocation.heldAmount())
                            + " 不足，无法核销 " + fmt(qty));
        }
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

    private OutageResponse toOutageResponse(OutageRow row) {
        return new OutageResponse(row.id(), row.outageKey(), row.channelId(), toIso(row.startNanos()),
                toIso(row.endNanos()), row.status(),
                row.recoveredNanos() == null ? null : toIso(row.recoveredNanos()), row.channelVersion(),
                repository.listOutageAllocationKeys(row.id()), toIso(row.createdNanos()));
    }

    private RiskResponse toRiskResponse(RiskRow row, String outageKey) {
        return new RiskResponse(row.allocationKey(), outageKey, toIso(row.createdNanos()));
    }

    private SettlementResponse toSettlementResponse(SettlementRow row) {
        return new SettlementResponse(row.settlementKey(), row.batchKey(), row.allocationKey(), row.windowId(),
                fmt(row.amount()), toIso(row.createdNanos()));
    }

    private long nowNanos() {
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
