package com.example.starter.water;

import com.example.starter.water.HydraulicCalculator.HeadExceededException;
import com.example.starter.water.HydraulicCalculator.Request;
import com.example.starter.water.HydraulicCalculator.Segment;
import com.example.starter.water.HydraulicCalculator.SliceFact;
import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.PathRow;
import com.example.starter.water.WaterRepository.SegmentRow;
import com.example.starter.water.WaterRepository.SliceRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.HydraulicAllocationResponse;
import com.example.starter.water.dto.Dtos.HydraulicDetailResponse;
import com.example.starter.water.dto.Dtos.SegmentResponse;
import com.example.starter.water.dto.Dtos.SliceView;
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
import java.util.LinkedHashSet;
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
            if (allocation.flowRate() != null) {
                // 水力型申请：全局锁内把全部已批曲线按起止切片重算，本单或任一旧单水头不足都整体回滚
                repository.lockHydraulic();
                try {
                    refreshSnapshots(checkHydraulicSet());
                } catch (HeadExceededException e) {
                    throw headInsufficient(e);
                } catch (ArithmeticException e) {
                    throw ApiException.unprocessable("HYDRAULIC_OVERFLOW", "水力核算超出精确整数范围");
                }
            }
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
            if (allocation.flowRate() != null) {
                // 水力型申请取消：移出最终集合并清理其冻结时片（只减少流量，不会使他人越限）
                repository.lockHydraulic();
                repository.deleteSlices(allocation.id());
            }
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
            boolean sourceHydraulic = lockedSource.flowRate() != null;
            boolean targetHydraulic = lockedTarget.flowRate() != null;
            if (sourceHydraulic || targetHydraulic) {
                repository.lockHydraulic();
            }
            if (sourceHydraulic) {
                // 源为水力型：转让水量必须能按源时段折算为恒定正整数流量，目标继承原路径/时段/水头
                long minutes = HydraulicCalculator.minutesBetween(lockedSource.supplyStartNanos(),
                        lockedSource.supplyEndNanos());
                BigDecimal[] quotient = amount.divideAndRemainder(BigDecimal.valueOf(minutes));
                if (quotient[1].signum() != 0 || quotient[0].signum() <= 0) {
                    throw ApiException.unprocessable("FLOW_VOLUME_MISMATCH",
                            "转让水量 " + fmt(amount) + " 无法按源供水时段 " + minutes
                                    + " 分钟折算为恒定正整数流量");
                }
                long targetFlow = quotient[0].longValueExact();
                BigDecimal heldAfter = lockedSource.heldAmount().subtract(amount);
                long sourceFlowAfter = heldAfter.divide(BigDecimal.valueOf(minutes)).longValueExact();
                if (!targetHydraulic) {
                    repository.setAllocationHydraulic(lockedTarget.id(), targetFlow,
                            lockedSource.supplyStartNanos(), lockedSource.supplyEndNanos(),
                            lockedSource.availableHead(), now);
                    repository.copyPath(lockedSource.id(), lockedTarget.id());
                }
                repository.updateAllocationFlow(lockedSource.id(), sourceFlowAfter, now);
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
            if (sourceHydraulic || targetHydraulic) {
                // 继承/自有路径的目标加入最终集合后重算（含候选内部共用段流量聚合），越限整体回滚
                try {
                    refreshSnapshots(checkHydraulicSet());
                } catch (HeadExceededException e) {
                    throw headInsufficient(e);
                } catch (ArithmeticException e) {
                    throw ApiException.unprocessable("HYDRAULIC_OVERFLOW", "水力核算超出精确整数范围");
                }
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
    // 简化水力模型（合成参数）
    // ------------------------------------------------------------------

    /** 创建渠段：阻力 k 为正整数，版本从 1 起。 */
    public SegmentResponse createSegment(String requestId, String segmentKey, Long resistance, String actor) {
        requireKey("requestId", requestId);
        requireKey("segmentKey", segmentKey);
        requireKey("X-Actor-Id", actor);
        long k = requirePositive("resistance", resistance);
        String params = "SEGMENT_CREATE|" + segmentKey + "|" + k + "|" + actor;
        return runCommand("SEGMENT_CREATE", requestId, params, SegmentResponse.class, () -> {
            if (repository.findSegmentByKey(segmentKey) != null) {
                throw ApiException.conflict("SEGMENT_EXISTS", "渠段已存在: " + segmentKey);
            }
            long id = repository.insertSegment(segmentKey, k, nowNanos());
            return toSegmentResponse(repository.findSegmentById(id));
        });
    }

    /**
     * 修改渠段阻力：携带 expectedVersion，与当前版本不一致返回 409；
     * 修改后任一现存已批申请水头不足同样返回 409，阻力与版本均不变。
     */
    public SegmentResponse updateSegmentResistance(String requestId, String segmentKey, Long resistance,
                                                   Integer expectedVersion, String actor) {
        requireKey("requestId", requestId);
        requireKey("segmentKey", segmentKey);
        requireKey("X-Actor-Id", actor);
        long k = requirePositive("resistance", resistance);
        if (expectedVersion == null || expectedVersion < 1) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "expectedVersion 必须为不小于 1 的整数");
        }
        String params = "SEGMENT_RESISTANCE|" + segmentKey + "|" + k + "|" + expectedVersion + "|" + actor;
        return runCommand("SEGMENT_RESISTANCE", requestId, params, SegmentResponse.class, () -> {
            repository.lockHydraulic();
            SegmentRow segment = repository.lockSegmentByKey(segmentKey);
            if (segment == null) {
                throw ApiException.notFound("SEGMENT_NOT_FOUND", "渠段不存在: " + segmentKey);
            }
            if (segment.version() != expectedVersion) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "渠段当前版本 " + segment.version() + " 与期望版本 " + expectedVersion + " 不一致");
            }
            repository.updateSegmentResistance(segment.id(), k, segment.version() + 1, nowNanos());
            try {
                refreshSnapshots(checkHydraulicSet());
            } catch (HeadExceededException e) {
                throw ApiException.conflict("RESISTANCE_CONFLICT",
                        "修改后申请 " + e.allocationKey() + " 时片 " + toIso(e.sliceStartNanos())
                                + " 路径损失 " + e.loss() + " 超过可用水头 " + e.availableHead() + "，拒绝修改");
            } catch (ArithmeticException e) {
                throw ApiException.conflict("RESISTANCE_CONFLICT", "修改后水力核算超出精确整数范围，拒绝修改");
            }
            return toSegmentResponse(repository.findSegmentById(segment.id()));
        });
    }

    /**
     * 提交水力型配水申请：1 至 5 段有序无重复渠段、恒定正整数流量、UTC 整分钟左闭右开时段、
     * 正整数可用水头；原申请水量 = 流量 × 时段分钟数。
     */
    public HydraulicAllocationResponse submitHydraulicAllocation(String requestId, String allocationKey,
                                                                 Long windowId, String userId, List<String> path,
                                                                 Long flowRate, String supplyStartUtc,
                                                                 String supplyEndUtc, Long availableHead,
                                                                 String actor) {
        requireKey("requestId", requestId);
        requireKey("allocationKey", allocationKey);
        requireKey("userId", userId);
        requireKey("X-Actor-Id", actor);
        if (windowId == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "windowId 不能为空");
        }
        if (path == null || path.isEmpty() || path.size() > 5) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "path 必须为 1 至 5 段有序渠段");
        }
        List<String> segmentKeys = new ArrayList<>(path.size());
        for (String segmentKey : path) {
            requireKey("path 元素", segmentKey);
            segmentKeys.add(segmentKey);
        }
        if (new LinkedHashSet<>(segmentKeys).size() != segmentKeys.size()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "path 内渠段不得重复");
        }
        long flow = requirePositive("flowRate", flowRate);
        long head = requirePositive("availableHead", availableHead);
        long startNanos = parseInstant("supplyStartUtc", supplyStartUtc);
        long endNanos = parseInstant("supplyEndUtc", supplyEndUtc);
        if (!HydraulicCalculator.isMinuteAligned(startNanos) || !HydraulicCalculator.isMinuteAligned(endNanos)) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "供水时段必须按 UTC 整分钟对齐");
        }
        if (startNanos >= endNanos) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "supplyStartUtc 必须早于 supplyEndUtc");
        }
        long minutes = HydraulicCalculator.minutesBetween(startNanos, endNanos);
        long amount;
        try {
            amount = Math.multiplyExact(flow, minutes);
        } catch (ArithmeticException e) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "流量 × 分钟数超出可表示范围");
        }
        if (amount > 999_999_999_999_999L) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "原申请水量超出 DECIMAL(19,3) 可表示范围");
        }
        String params = "HYDRAULIC_SUBMIT|" + allocationKey + "|" + windowId + "|" + userId + "|" + flow + "|"
                + startNanos + "|" + endNanos + "|" + head + "|" + String.join(",", segmentKeys) + "|" + actor;
        return runCommand("HYDRAULIC_SUBMIT", requestId, params, HydraulicAllocationResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            List<SegmentRow> segments = new ArrayList<>(segmentKeys.size());
            for (String segmentKey : segmentKeys) {
                SegmentRow segment = repository.findSegmentByKey(segmentKey);
                if (segment == null) {
                    throw ApiException.notFound("SEGMENT_NOT_FOUND", "渠段不存在: " + segmentKey);
                }
                segments.add(segment);
            }
            long id = repository.insertHydraulicAllocation(allocationKey, windowId, userId,
                    BigDecimal.valueOf(amount), actor, flow, startNanos, endNanos, head, nowNanos());
            for (int i = 0; i < segments.size(); i++) {
                repository.insertPathSegment(id, i + 1, segments.get(i).id());
            }
            return toHydraulicAllocationResponse(repository.findAllocationByKey(allocationKey));
        });
    }

    /** 查询水力型申请明细：冻结路径、流量曲线与审批冻结的时片快照。 */
    public HydraulicDetailResponse getHydraulicDetail(String allocationKey) {
        AllocationRow allocation = repository.findAllocationByKey(allocationKey);
        if (allocation == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
        }
        if (allocation.flowRate() == null) {
            throw ApiException.notFound("NOT_HYDRAULIC", "申请不参与水力模型: " + allocationKey);
        }
        List<String> path = repository.listPath(allocation.id()).stream().map(PathRow::segmentKey).toList();
        List<SliceView> slices = repository.listSlices(allocation.id()).stream()
                .map(this::toSliceView).toList();
        return new HydraulicDetailResponse(allocation.allocationKey(), path, allocation.flowRate(),
                toIso(allocation.supplyStartNanos()), toIso(allocation.supplyEndNanos()),
                allocation.availableHead(), allocation.status(), slices);
    }

    /** 汇总当前全部 APPROVED 水力型申请并重算时片事实；越限抛 HeadExceededException。 */
    private List<SliceFact> checkHydraulicSet() {
        List<AllocationRow> approved = repository.listApprovedHydraulic();
        List<Request> requests = new ArrayList<>(approved.size());
        for (AllocationRow allocation : approved) {
            List<Segment> segments = new ArrayList<>();
            for (PathRow pathRow : repository.listPath(allocation.id())) {
                SegmentRow segment = repository.findSegmentById(pathRow.segmentId());
                segments.add(new Segment(segment.id(), segment.segmentKey(), segment.resistance(),
                        segment.version()));
            }
            requests.add(new Request(allocation.id(), allocation.allocationKey(), allocation.flowRate(),
                    allocation.supplyStartNanos(), allocation.supplyEndNanos(), allocation.availableHead(),
                    segments));
        }
        return HydraulicCalculator.recompute(requests);
    }

    /** 用最新核算事实替换参与集合的冻结时片快照（同事务）。 */
    private void refreshSnapshots(List<SliceFact> facts) {
        List<Long> allocationIds = facts.stream().map(SliceFact::allocationId).distinct().toList();
        for (Long allocationId : allocationIds) {
            repository.deleteSlices(allocationId);
        }
        long now = nowNanos();
        for (SliceFact fact : facts) {
            repository.insertSlice(fact.allocationId(), fact.segmentId(), fact.sliceStartNanos(),
                    fact.sliceEndNanos(), fact.totalFlow(), fact.resistance(), fact.resistanceVersion(),
                    fact.loss(), now);
        }
    }

    private static ApiException headInsufficient(HeadExceededException e) {
        return ApiException.unprocessable("HEAD_INSUFFICIENT",
                "申请 " + e.allocationKey() + " 时片 " + toIso(e.sliceStartNanos()) + " 路径损失 " + e.loss()
                        + " 超过可用水头 " + e.availableHead());
    }

    private SegmentResponse toSegmentResponse(SegmentRow row) {
        return new SegmentResponse(row.segmentKey(), row.resistance(), row.version(),
                toIso(row.createdNanos()), toIso(row.updatedNanos()));
    }

    private HydraulicAllocationResponse toHydraulicAllocationResponse(AllocationRow row) {
        List<String> path = repository.listPath(row.id()).stream().map(PathRow::segmentKey).toList();
        return new HydraulicAllocationResponse(row.allocationKey(), row.windowId(), row.userId(),
                fmt(row.amount()), path, row.flowRate(), toIso(row.supplyStartNanos()),
                toIso(row.supplyEndNanos()), row.availableHead(), row.requester(), row.status(),
                toIso(row.createdNanos()));
    }

    private SliceView toSliceView(SliceRow row) {
        return new SliceView(row.segmentKey(), toIso(row.sliceStartNanos()), toIso(row.sliceEndNanos()),
                row.totalFlow(), row.resistance(), (int) row.resistanceVersion(), row.loss());
    }

    /** 校验正整数参数（JSON 数值），非法抛 400。 */
    static long requirePositive(String field, Long value) {
        if (value == null || value < 1) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 必须为正整数");
        }
        return value;
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
