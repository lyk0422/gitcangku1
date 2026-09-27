package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationHourRow;
import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.CurvePointRow;
import com.example.starter.water.WaterRepository.CurveRow;
import com.example.starter.water.WaterRepository.HourLoadRow;
import com.example.starter.water.WaterRepository.SnapshotRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.dto.Dtos.AllocationDetailResponse;
import com.example.starter.water.dto.Dtos.AllocationHourView;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.ApprovalSnapshotView;
import com.example.starter.water.dto.Dtos.BatchApproveResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.CurvePointRequest;
import com.example.starter.water.dto.Dtos.CurvePointResponse;
import com.example.starter.water.dto.Dtos.CurveResponse;
import com.example.starter.water.dto.Dtos.CurveVersionsResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.HourAmountRequest;
import com.example.starter.water.dto.Dtos.HourDiagnostics;
import com.example.starter.water.dto.Dtos.HourViolation;
import com.example.starter.water.dto.Dtos.RedemptionResponse;
import com.example.starter.water.dto.Dtos.SnapshotHourView;
import com.example.starter.water.dto.Dtos.TransferListResponse;
import com.example.starter.water.dto.Dtos.TransferResponse;
import com.example.starter.water.dto.Dtos.WindowHoursResponse;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
    private static final long HOUR_NANOS = 3_600_000_000_000L;
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("\\d{1,16}(\\.\\d{1,3})?");
    private static final Pattern LIMIT_PATTERN = Pattern.compile("\\d{1,16}(\\.\\d{1,2})?");
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

    /** 提交配水申请，申请人为 actor；hourlyAmounts 为可选逐小时预留分布（合计须等于申请水量）。 */
    public AllocationResponse submitAllocation(String commandKey, String allocationKey, Long windowId,
                                               String userId, String amount, String actor,
                                               List<HourAmountRequest> hourlyAmounts) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        requireKey("userId", userId);
        requireKey("X-Actor-Id", actor);
        if (windowId == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "windowId 不能为空");
        }
        BigDecimal qty = parseAmount("amount", amount);
        Map<Long, BigDecimal> hourly = parseHourly(hourlyAmounts, qty);
        StringBuilder params = new StringBuilder("ALLOCATION_SUBMIT|").append(allocationKey).append('|')
                .append(windowId).append('|').append(userId).append('|').append(qty.toPlainString())
                .append('|').append(actor);
        if (hourly != null) {
            hourly.forEach((hour, value) -> params.append('|').append(hour).append('=').append(value.toPlainString()));
        }
        return runCommand("ALLOCATION_SUBMIT", commandKey, params.toString(), AllocationResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            if (hourly != null) {
                Set<Long> windowHours = new HashSet<>(windowHours(window.startNanos(), window.endNanos()));
                List<String> outside = new ArrayList<>();
                for (long hour : hourly.keySet()) {
                    if (!windowHours.contains(hour)) {
                        outside.add(toIso(hour));
                    }
                }
                if (!outside.isEmpty()) {
                    throw ApiException.badRequest("HOURLY_HOUR_OUTSIDE_WINDOW",
                            "逐小时预留存在跨窗口小时点: " + String.join(", ", outside));
                }
            }
            long id = repository.insertAllocation(allocationKey, windowId, userId, qty, actor, nowNanos());
            if (hourly != null) {
                hourly.forEach((hour, value) -> repository.insertAllocationHour(id, hour, value));
            }
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
            long now = nowNanos();
            checkHourlyAndSnapshot(window, allocation, now);
            repository.updateAllocationStatus(allocation.id(), STATUS_APPROVED, now);
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
            long now = nowNanos();
            if (STATUS_APPROVED.equals(allocation.status())) {
                // 释放未核销预留：逐小时预留压降为已核销量，已核销部分保留为历史事实
                repository.releaseAllocationHours(allocation.id());
            }
            repository.updateAllocationStatus(allocation.id(), STATUS_CANCELLED, now);
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
            moveHourlyReservation(lockedSource, lockedTarget, now);
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
    // 下泄曲线
    // ------------------------------------------------------------------

    /**
     * 全量替换窗口下泄曲线：小时点必须恰好覆盖窗口全部小时（整点对齐、左闭右开），
     * 不得重复、缺失或跨窗口；曲线已存在时已开始小时（hourStart &lt;= now）不得修改，否则 409。
     * 每次成功替换产生新的递增版本，历史版本不可改写。
     */
    public CurveResponse putCurve(String commandKey, long windowId, List<CurvePointRequest> points) {
        requireKey("commandKey", commandKey);
        if (points == null || points.isEmpty()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "points 不能为空");
        }
        Map<Long, BigDecimal> provided = new TreeMap<>();
        for (CurvePointRequest point : points) {
            if (point == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "points 不能包含空项");
            }
            long hour = parseHour("hourStartUtc", point.hourStartUtc());
            BigDecimal limit = parseLimit("limit", point.limit());
            if (provided.put(hour, limit) != null) {
                throw ApiException.badRequest("CURVE_HOUR_DUPLICATE",
                        "曲线存在重复小时点: " + toIso(hour));
            }
        }
        StringBuilder params = new StringBuilder("CURVE_PUT|").append(windowId);
        provided.forEach((hour, limit) -> params.append('|').append(hour).append('=').append(limit.toPlainString()));
        return runCommand("CURVE_PUT", commandKey, params.toString(), CurveResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            List<Long> required = windowHours(window.startNanos(), window.endNanos());
            Set<Long> requiredSet = new HashSet<>(required);
            List<String> missing = new ArrayList<>();
            for (long hour : required) {
                if (!provided.containsKey(hour)) {
                    missing.add(toIso(hour));
                }
            }
            if (!missing.isEmpty()) {
                throw ApiException.badRequest("CURVE_HOUR_MISSING",
                        "曲线缺失窗口小时点: " + String.join(", ", missing));
            }
            List<String> extra = new ArrayList<>();
            for (long hour : provided.keySet()) {
                if (!requiredSet.contains(hour)) {
                    extra.add(toIso(hour));
                }
            }
            if (!extra.isEmpty()) {
                throw ApiException.badRequest("CURVE_HOUR_OUTSIDE_WINDOW",
                        "曲线存在跨窗口小时点: " + String.join(", ", extra));
            }
            CurveRow existing = repository.findLatestCurve(windowId);
            if (existing != null) {
                long now = nowNanos();
                List<String> started = new ArrayList<>();
                for (CurvePointRow point : repository.listCurvePoints(existing.id())) {
                    if (point.hourStartNanos() <= now
                            && provided.get(point.hourStartNanos()).compareTo(point.limitAmount()) != 0) {
                        started.add(toIso(point.hourStartNanos()) + " 当前上限 " + fmt(point.limitAmount()));
                    }
                }
                if (!started.isEmpty()) {
                    throw ApiException.conflict("CURVE_HOUR_STARTED",
                            "已开始小时不得修改: " + String.join(", ", started));
                }
            }
            int version = existing == null ? 1 : existing.version() + 1;
            long curveId = repository.insertCurve(windowId, version, nowNanos());
            provided.forEach((hour, limit) -> repository.insertCurvePoint(curveId, hour, limit));
            return toCurveResponse(repository.findLatestCurve(windowId));
        });
    }

    /** 查询窗口当前下泄曲线（最新版本）。 */
    public CurveResponse getCurve(long windowId) {
        if (repository.findWindowById(windowId) == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        CurveRow curve = repository.findLatestCurve(windowId);
        if (curve == null) {
            throw ApiException.notFound("CURVE_NOT_FOUND", "窗口尚未配置下泄曲线: " + windowId);
        }
        return toCurveResponse(curve);
    }

    /** 查询窗口下泄曲线全部历史版本，按版本号升序。 */
    public CurveVersionsResponse getCurveVersions(long windowId) {
        if (repository.findWindowById(windowId) == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        List<CurveResponse> versions = repository.listCurves(windowId).stream()
                .map(this::toCurveResponse).toList();
        return new CurveVersionsResponse(windowId, versions);
    }

    /** 窗口逐小时诊断：上限、已批准未核销、已核销与剩余，只读不改状态。 */
    public WindowHoursResponse getWindowHours(long windowId) {
        WindowRow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        CurveRow curve = repository.findLatestCurve(windowId);
        Map<Long, BigDecimal> limits = new HashMap<>();
        if (curve != null) {
            for (CurvePointRow point : repository.listCurvePoints(curve.id())) {
                limits.put(point.hourStartNanos(), point.limitAmount());
            }
        }
        Map<Long, HourLoadRow> loads = new HashMap<>();
        for (HourLoadRow load : repository.sumHourLoad(windowId)) {
            loads.put(load.hourStartNanos(), load);
        }
        List<HourDiagnostics> hours = new ArrayList<>();
        for (long hour : windowHours(window.startNanos(), window.endNanos())) {
            BigDecimal limit = limits.get(hour);
            HourLoadRow load = loads.get(hour);
            BigDecimal reserved = load == null ? BigDecimal.ZERO : load.reserved();
            BigDecimal redeemed = load == null ? BigDecimal.ZERO : load.redeemed();
            BigDecimal remaining = limit == null ? null : limit.subtract(reserved).subtract(redeemed);
            hours.add(new HourDiagnostics(toIso(hour), limit == null ? null : fmt(limit),
                    fmt(reserved), fmt(redeemed), remaining == null ? null : fmt(remaining)));
        }
        return new WindowHoursResponse(windowId, hours);
    }

    // ------------------------------------------------------------------
    // 核销、批量批准与明细查询
    // ------------------------------------------------------------------

    /**
     * 实际核销：按发生小时扣减同一小时额度（不能挪用其他小时余量），
     * 同步扣减申请持有额度并写不可变核销流水；超过该小时剩余额度返回 422 及差额。
     */
    public RedemptionResponse redeemAllocation(String commandKey, String allocationKey, String hourStartUtc,
                                               String amount, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        requireKey("X-Actor-Id", actor);
        long hour = parseHour("hourStartUtc", hourStartUtc);
        BigDecimal qty = parseAmount("amount", amount);
        String params = "ALLOCATION_REDEEM|" + allocationKey + "|" + hour + "|" + qty.toPlainString()
                + "|" + actor;
        return runCommand("ALLOCATION_REDEEM", commandKey, params, RedemptionResponse.class, () -> {
            AllocationRow allocation = repository.findAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            lockWindowOf(allocation);
            // 窗口锁内重读申请，避免与取消/转让并发使用过期状态
            allocation = repository.lockAllocationByKey(allocationKey);
            if (!STATUS_APPROVED.equals(allocation.status())) {
                throw ApiException.conflict("ALLOCATION_NOT_APPROVED",
                        "仅已批准申请可以核销，当前状态: " + allocation.status());
            }
            AllocationHourRow hourRow = repository.findAllocationHour(allocation.id(), hour);
            BigDecimal remaining = hourRow == null ? BigDecimal.ZERO
                    : hourRow.amount().subtract(hourRow.redeemed());
            if (qty.compareTo(remaining) > 0) {
                throw ApiException.hourViolation("REDEEM_EXCEEDS_HOUR",
                        "核销量 " + fmt(qty) + " 超过该小时剩余额度 " + fmt(remaining) + "，差额 "
                                + fmt(qty.subtract(remaining)),
                        List.of(new HourViolation(toIso(hour), fmt(remaining), fmt(qty),
                                fmt(qty.subtract(remaining)))));
            }
            long now = nowNanos();
            repository.addRedeemed(allocation.id(), hour, qty);
            repository.decrementHeldAmount(allocation.id(), qty, now);
            long id = repository.insertRedemption(allocation.id(), hour, qty, actor, now);
            return new RedemptionResponse(id, allocationKey, toIso(hour), fmt(qty), actor, toIso(now));
        });
    }

    /**
     * 批量批准：先校验全部申请的完整最终状态（窗口配额 + 逐小时预留合并校验），
     * 任一失败整次回滚；全部通过后在同一事务内提交。
     */
    public BatchApproveResponse approveBatch(String commandKey, List<String> allocationKeys) {
        requireKey("commandKey", commandKey);
        if (allocationKeys == null || allocationKeys.isEmpty()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "allocationKeys 不能为空");
        }
        Set<String> seen = new HashSet<>();
        for (String key : allocationKeys) {
            requireKey("allocationKeys", key);
            if (!seen.add(key)) {
                throw ApiException.badRequest("BATCH_DUPLICATE_KEY", "批量批准存在重复申请键: " + key);
            }
        }
        String params = "ALLOCATION_APPROVE_BATCH|" + String.join(",", allocationKeys);
        return runCommand("ALLOCATION_APPROVE_BATCH", commandKey, params, BatchApproveResponse.class, () -> {
            List<AllocationRow> allocations = new ArrayList<>();
            for (String key : allocationKeys) {
                AllocationRow row = repository.findAllocationByKey(key);
                if (row == null) {
                    throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + key);
                }
                allocations.add(row);
            }
            // 按窗口 ID 升序锁定窗口行，避免并发批间死锁
            List<Long> windowIds = allocations.stream().map(AllocationRow::windowId).distinct().sorted()
                    .toList();
            Map<Long, WindowRow> windows = new HashMap<>();
            for (long windowId : windowIds) {
                WindowRow window = repository.lockWindowById(windowId);
                if (window == null) {
                    throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
                }
                windows.put(windowId, window);
            }
            // 窗口锁内重读申请，校验全部处于 REQUESTED
            List<AllocationRow> locked = new ArrayList<>();
            for (String key : allocationKeys) {
                AllocationRow row = repository.lockAllocationByKey(key);
                switch (row.status()) {
                    case STATUS_CANCELLED ->
                            throw ApiException.conflict("ALLOCATION_CANCELLED",
                                    "已取消的申请不能批准: " + key);
                    case STATUS_APPROVED ->
                            throw ApiException.conflict("ALLOCATION_ALREADY_APPROVED",
                                    "申请已批准，不能重复批准: " + key);
                    default -> locked.add(row);
                }
            }
            // 窗口配额校验：按窗口汇总本批完整最终额度
            for (long windowId : windowIds) {
                BigDecimal approved = repository.sumApprovedAmount(windowId);
                BigDecimal additions = locked.stream().filter(a -> a.windowId() == windowId)
                        .map(AllocationRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal available = availableTotal(windows.get(windowId));
                if (approved.add(additions).compareTo(available) > 0) {
                    throw ApiException.quotaExceeded("窗口 " + windowId + " 批量批准后将超过当前可用总量 "
                            + fmt(available) + "（已批准 " + fmt(approved) + "，本批 " + fmt(additions) + "）");
                }
            }
            // 逐小时校验：按窗口合并本批新增预留，基于完整最终状态判定
            long now = nowNanos();
            for (long windowId : windowIds) {
                CurveRow curve = repository.findLatestCurve(windowId);
                Map<Long, BigDecimal> additions = new TreeMap<>();
                for (AllocationRow allocation : locked) {
                    if (allocation.windowId() != windowId) {
                        continue;
                    }
                    List<AllocationHourRow> hours = repository.listAllocationHours(allocation.id());
                    requireHourlyIfCurve(curve, allocation, hours);
                    for (AllocationHourRow hour : hours) {
                        additions.merge(hour.hourStartNanos(), hour.amount(), BigDecimal::add);
                    }
                }
                if (curve != null) {
                    checkCurveLimit(windows.get(windowId), curve, additions);
                }
            }
            // 全部校验通过：固化快照并统一提交
            List<AllocationResponse> responses = new ArrayList<>();
            for (AllocationRow allocation : locked) {
                insertApprovalSnapshot(allocation.id(), allocation.windowId(), now);
                repository.updateAllocationStatus(allocation.id(), STATUS_APPROVED, now);
                responses.add(toAllocationResponse(repository.findAllocationByKey(allocation.allocationKey())));
            }
            return new BatchApproveResponse(responses);
        });
    }

    /** 申请明细：申请本体 + 逐小时状态 + 批准快照 + 核销流水，只读不改状态。 */
    public AllocationDetailResponse getAllocationDetail(String allocationKey) {
        AllocationRow allocation = repository.findAllocationByKey(allocationKey);
        if (allocation == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
        }
        List<AllocationHourView> hours = repository.listAllocationHours(allocation.id()).stream()
                .map(h -> new AllocationHourView(toIso(h.hourStartNanos()), fmt(h.amount()),
                        fmt(h.redeemed()), fmt(h.amount().subtract(h.redeemed()))))
                .toList();
        SnapshotRow snapshot = repository.findSnapshot(allocation.id());
        ApprovalSnapshotView snapshotView = null;
        if (snapshot != null) {
            List<SnapshotHourView> snapshotHours = repository.listSnapshotHours(allocation.id()).stream()
                    .map(h -> new SnapshotHourView(toIso(h.hourStartNanos()), fmt(h.amount())))
                    .toList();
            snapshotView = new ApprovalSnapshotView(snapshot.curveVersion(), snapshotHours,
                    toIso(snapshot.createdNanos()));
        }
        List<RedemptionResponse> redemptions = repository.listRedemptions(allocation.id()).stream()
                .map(r -> new RedemptionResponse(r.id(), allocationKey, toIso(r.hourStartNanos()),
                        fmt(r.amount()), r.actor(), toIso(r.createdNanos())))
                .toList();
        return new AllocationDetailResponse(toAllocationResponse(allocation), hours, snapshotView,
                redemptions);
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

    /**
     * 解析逐小时预留分布：小时整点对齐、不得重复，各项大于 0 且合计必须等于申请水量。
     * 输入为 null 或空列表时返回 null（不声明分布）。
     */
    static Map<Long, BigDecimal> parseHourly(List<HourAmountRequest> hourlyAmounts, BigDecimal total) {
        if (hourlyAmounts == null || hourlyAmounts.isEmpty()) {
            return null;
        }
        Map<Long, BigDecimal> hourly = new TreeMap<>();
        for (HourAmountRequest item : hourlyAmounts) {
            if (item == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "hourlyAmounts 不能包含空项");
            }
            long hour = parseHour("hourStartUtc", item.hourStartUtc());
            BigDecimal value = parseAmount("hourlyAmounts.amount", item.amount());
            if (hourly.put(hour, value) != null) {
                throw ApiException.badRequest("HOURLY_HOUR_DUPLICATE",
                        "逐小时预留存在重复小时点: " + toIso(hour));
            }
        }
        BigDecimal sum = hourly.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(total) != 0) {
            throw ApiException.badRequest("HOURLY_SUM_MISMATCH",
                    "逐小时预留合计 " + fmt(sum) + " 与申请水量 " + fmt(total) + " 不一致，差额 "
                            + fmt(sum.subtract(total)));
        }
        return hourly;
    }

    /**
     * 批准前逐小时校验并固化快照：窗口已配置曲线时申请必须声明逐小时分布；
     * 任一小时（已核销 + 已批准未核销 + 本次新增预留）超过上限返回 422 并列出全部超限小时及差额。
     */
    private void checkHourlyAndSnapshot(WindowRow window, AllocationRow allocation, long now) {
        List<AllocationHourRow> hours = repository.listAllocationHours(allocation.id());
        CurveRow curve = repository.findLatestCurve(window.id());
        requireHourlyIfCurve(curve, allocation, hours);
        if (!hours.isEmpty() && curve != null) {
            Map<Long, BigDecimal> additions = new TreeMap<>();
            for (AllocationHourRow hour : hours) {
                additions.put(hour.hourStartNanos(), hour.amount());
            }
            checkCurveLimit(window, curve, additions);
        }
        insertApprovalSnapshot(allocation.id(), window.id(), now);
    }

    /** 窗口已配置曲线时申请必须声明逐小时分布，否则 409。 */
    private void requireHourlyIfCurve(CurveRow curve, AllocationRow allocation, List<AllocationHourRow> hours) {
        if (hours.isEmpty() && curve != null) {
            throw ApiException.conflict("ALLOCATION_HOURLY_MISSING",
                    "窗口已配置下泄曲线，申请 " + allocation.allocationKey() + " 缺少逐小时预留分布");
        }
    }

    /** 逐小时上限校验：现有负载 + 新增预留超过上限的小时全部列入 422 明细。 */
    private void checkCurveLimit(WindowRow window, CurveRow curve, Map<Long, BigDecimal> additions) {
        if (additions.isEmpty()) {
            return;
        }
        Map<Long, BigDecimal> limits = limitMap(curve);
        Map<Long, HourLoadRow> loads = hourLoadMap(window.id());
        List<HourViolation> violations = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> addition : additions.entrySet()) {
            BigDecimal limit = limits.get(addition.getKey());
            HourLoadRow load = loads.get(addition.getKey());
            BigDecimal existing = load == null ? BigDecimal.ZERO : load.reserved().add(load.redeemed());
            BigDecimal required = existing.add(addition.getValue());
            if (required.compareTo(limit) > 0) {
                violations.add(new HourViolation(toIso(addition.getKey()), fmt(limit), fmt(required),
                        fmt(required.subtract(limit))));
            }
        }
        if (!violations.isEmpty()) {
            throw ApiException.hourViolation("CURVE_LIMIT_EXCEEDED",
                    "逐小时下泄上限超限，共 " + violations.size() + " 个小时", violations);
        }
    }

    /** 固化批准快照：曲线版本与逐小时预留分布，后续不得改写。 */
    private void insertApprovalSnapshot(long allocationId, long windowId, long now) {
        List<AllocationHourRow> hours = repository.listAllocationHours(allocationId);
        if (hours.isEmpty()) {
            return;
        }
        CurveRow curve = repository.findLatestCurve(windowId);
        repository.insertSnapshot(allocationId, curve == null ? null : curve.version(), now);
        for (AllocationHourRow hour : hours) {
            repository.insertSnapshotHour(allocationId, hour.hourStartNanos(), hour.amount());
        }
    }

    /**
     * 转让逐小时预留移动：目标声明的逐小时分布等额从源申请对应小时扣减（源该小时未核销预留
     * 必须足额，否则 422），窗口逐小时总负载净变化为零；曲线已配置时再基于完整最终负载校验。
     * 同时为目标固化批准快照。
     */
    private void moveHourlyReservation(AllocationRow source, AllocationRow target, long now) {
        List<AllocationHourRow> targetHours = repository.listAllocationHours(target.id());
        CurveRow curve = repository.findLatestCurve(source.windowId());
        if (targetHours.isEmpty()) {
            if (curve != null) {
                throw ApiException.conflict("ALLOCATION_HOURLY_MISSING",
                        "窗口已配置下泄曲线，申请 " + target.allocationKey() + " 缺少逐小时预留分布");
            }
            return;
        }
        Map<Long, AllocationHourRow> sourceHours = new HashMap<>();
        for (AllocationHourRow row : repository.listAllocationHours(source.id())) {
            sourceHours.put(row.hourStartNanos(), row);
        }
        List<HourViolation> insufficient = new ArrayList<>();
        for (AllocationHourRow targetHour : targetHours) {
            AllocationHourRow sourceHour = sourceHours.get(targetHour.hourStartNanos());
            BigDecimal available = sourceHour == null ? BigDecimal.ZERO
                    : sourceHour.amount().subtract(sourceHour.redeemed());
            if (available.compareTo(targetHour.amount()) < 0) {
                insufficient.add(new HourViolation(toIso(targetHour.hourStartNanos()), fmt(available),
                        fmt(targetHour.amount()), fmt(targetHour.amount().subtract(available))));
            }
        }
        if (!insufficient.isEmpty()) {
            throw ApiException.hourViolation("SOURCE_HOUR_INSUFFICIENT",
                    "源申请逐小时未核销预留不足，共 " + insufficient.size() + " 个小时不足", insufficient);
        }
        if (curve != null) {
            Map<Long, BigDecimal> limits = limitMap(curve);
            Map<Long, HourLoadRow> loads = hourLoadMap(source.windowId());
            List<HourViolation> violations = new ArrayList<>();
            for (AllocationHourRow targetHour : targetHours) {
                BigDecimal limit = limits.get(targetHour.hourStartNanos());
                HourLoadRow load = loads.get(targetHour.hourStartNanos());
                BigDecimal existing = load == null ? BigDecimal.ZERO
                        : load.reserved().add(load.redeemed());
                if (existing.compareTo(limit) > 0) {
                    violations.add(new HourViolation(toIso(targetHour.hourStartNanos()), fmt(limit),
                            fmt(existing), fmt(existing.subtract(limit))));
                }
            }
            if (!violations.isEmpty()) {
                throw ApiException.hourViolation("CURVE_LIMIT_EXCEEDED",
                        "转让后逐小时下泄负载超过上限，共 " + violations.size() + " 个小时超限", violations);
            }
        }
        for (AllocationHourRow targetHour : targetHours) {
            repository.decrementAllocationHour(source.id(), targetHour.hourStartNanos(), targetHour.amount());
        }
        repository.insertSnapshot(target.id(), curve == null ? null : curve.version(), now);
        for (AllocationHourRow targetHour : targetHours) {
            repository.insertSnapshotHour(target.id(), targetHour.hourStartNanos(), targetHour.amount());
        }
    }

    /** 曲线版本的小时上限映射。 */
    private Map<Long, BigDecimal> limitMap(CurveRow curve) {
        Map<Long, BigDecimal> limits = new HashMap<>();
        for (CurvePointRow point : repository.listCurvePoints(curve.id())) {
            limits.put(point.hourStartNanos(), point.limitAmount());
        }
        return limits;
    }

    /** 窗口逐小时负载映射（已批准未核销 + 已核销）。 */
    private Map<Long, HourLoadRow> hourLoadMap(long windowId) {
        Map<Long, HourLoadRow> loads = new HashMap<>();
        for (HourLoadRow load : repository.sumHourLoad(windowId)) {
            loads.put(load.hourStartNanos(), load);
        }
        return loads;
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

    private CurveResponse toCurveResponse(CurveRow row) {
        List<CurvePointResponse> points = repository.listCurvePoints(row.id()).stream()
                .map(p -> new CurvePointResponse(toIso(p.hourStartNanos()), fmt(p.limitAmount())))
                .toList();
        return new CurveResponse(row.windowId(), row.version(), points, toIso(row.createdNanos()));
    }

    /** 窗口覆盖的全部 UTC 整点小时起点（左闭右开），升序。 */
    static List<Long> windowHours(long startNanos, long endNanos) {
        List<Long> hours = new ArrayList<>();
        long first = Math.floorDiv(startNanos, HOUR_NANOS) * HOUR_NANOS;
        for (long hour = first; hour < endNanos; hour += HOUR_NANOS) {
            hours.add(hour);
        }
        return hours;
    }

    /** 解析整点小时：必须是合法 UTC 时刻且整点对齐。 */
    static long parseHour(String field, String value) {
        long nanos = parseInstant(field, value);
        if (Math.floorMod(nanos, HOUR_NANOS) != 0) {
            throw ApiException.badRequest("CURVE_HOUR_NOT_ALIGNED",
                    field + " 必须整点对齐（UTC 小时起点）: " + value);
        }
        return nanos;
    }

    /** 解析下泄上限：十进制字符串，非负，最多 2 位小数。 */
    static BigDecimal parseLimit(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不能为空");
        }
        String trimmed = value.trim();
        if (!LIMIT_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.badRequest("CURVE_LIMIT_INVALID",
                    field + " 必须为非负十进制字符串，最多 2 位小数: " + value);
        }
        return new BigDecimal(trimmed);
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
