package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.CurvePointRow;
import com.example.starter.water.WaterRepository.CurveRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.HourTotalRow;
import com.example.starter.water.WaterRepository.PlanItemRow;
import com.example.starter.water.WaterRepository.ReservationRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.WaterRepository.WriteoffRow;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.BatchApproveResponse;
import com.example.starter.water.dto.Dtos.BatchTransferResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.DrawdownCurveResponse;
import com.example.starter.water.dto.Dtos.DrawdownHourStatusResponse;
import com.example.starter.water.dto.Dtos.DrawdownPointResponse;
import com.example.starter.water.dto.Dtos.DrawdownStatusResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.HourViolation;
import com.example.starter.water.dto.Dtos.PlanItemRequest;
import com.example.starter.water.dto.Dtos.DrawdownPointRequest;
import com.example.starter.water.dto.Dtos.ReservationResponse;
import com.example.starter.water.dto.Dtos.TransferItemRequest;
import com.example.starter.water.dto.Dtos.TransferListResponse;
import com.example.starter.water.dto.Dtos.TransferResponse;
import com.example.starter.water.dto.Dtos.WindowResponse;
import com.example.starter.water.dto.Dtos.WriteoffResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 灌区配水业务服务。
 *
 * <p>幂等：每个写命令携带 commandKey（requestId），事务内先占位插入命令行再执行业务并写回响应；
 * 同键同参重放返回首次结果，同键改参返回 409，业务失败回滚不占用命令键。</p>
 *
 * <p>并发：批准、批量批准、转让、批量转让、核销、曲线修改在同一事务内先按窗口 id 升序
 * SELECT ... FOR UPDATE 锁定相关窗口行，再锁申请行，按事务提交顺序裁决；
 * 同一小时的预留占用在窗口锁内基于“已核销 + 已批准未核销”累计校验，保证不超卖。</p>
 *
 * <p>下泄曲线：按窗口版本化，批准/转让在预留行固化曲线版本与逐小时分布快照；
 * 曲线修改只允许作用于尚未开始的小时；核销只扣减同一申请同一小时的未核销预留。</p>
 */
@Service
public class WaterService {

    static final String STATUS_REQUESTED = "REQUESTED";
    static final String STATUS_APPROVED = "APPROVED";
    static final String STATUS_CANCELLED = "CANCELLED";

    static final String CURVE_ACTIVE = "ACTIVE";

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long NANOS_PER_HOUR = 3600L * NANOS_PER_SECOND;
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("\\d{1,16}(\\.\\d{1,3})?");
    private static final Pattern CAP_PATTERN = Pattern.compile("\\d{1,16}(\\.\\d{1,2})?");
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
            long id = repository.insertWindow(windowKey, channelId, startNanos, endNanos, planned, nowNanos());
            WindowRow row = repository.findWindowById(id);
            return toWindowResponse(row, null);
        });
    }

    /** 提交配水申请，申请人为 actor；窗口已配置下泄曲线时必须提交与窗口小时完全对齐的逐小时计划。 */
    public AllocationResponse submitAllocation(String commandKey, String allocationKey, Long windowId,
                                               String userId, String amount, String actor,
                                               List<PlanItemRequest> plan) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        requireKey("userId", userId);
        requireKey("X-Actor-Id", actor);
        if (windowId == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "windowId 不能为空");
        }
        BigDecimal qty = parseAmount("amount", amount);
        String params = "ALLOCATION_SUBMIT|" + allocationKey + "|" + windowId + "|" + userId + "|"
                + qty.toPlainString() + "|" + actor + "|" + canonicalPlan(plan);
        return runCommand("ALLOCATION_SUBMIT", commandKey, params, AllocationResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            CurveRow curve = repository.findActiveCurve(windowId);
            List<PlanItemRow> planItems = validateAndNormalizePlan(window, curve, qty, plan);
            long id = repository.insertAllocation(allocationKey, windowId, userId, qty, actor, nowNanos());
            for (PlanItemRow item : planItems) {
                repository.insertPlanItem(id, windowId, item.hourStartNanos(), item.volume());
            }
            return toAllocationResponse(repository.findAllocationByKey(allocationKey));
        });
    }

    /** 批准申请：除总量配额外，还须逐小时满足下泄上限，否则 422 并列出全部超限小时。 */
    public AllocationResponse approveAllocation(String commandKey, String allocationKey) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        String params = "ALLOCATION_APPROVE|" + allocationKey;
        return runCommand("ALLOCATION_APPROVE", commandKey, params, AllocationResponse.class,
                () -> approveOne(allocationKey));
    }

    /** 批量批准：先核对整批最终状态（总量配额 + 全部新增逐小时预留），一次事务提交，任一失败整批回滚。 */
    public BatchApproveResponse batchApprove(String commandKey, List<String> allocationKeys) {
        requireKey("commandKey", commandKey);
        if (allocationKeys == null || allocationKeys.isEmpty()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "allocationKeys 不能为空");
        }
        List<String> sortedKeys = allocationKeys.stream().sorted().toList();
        TreeSet<String> unique = new TreeSet<>(sortedKeys);
        if (unique.size() != sortedKeys.size()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "批量批准列表存在重复申请");
        }
        sortedKeys.forEach(k -> requireKey("allocationKeys", k));
        String params = "ALLOCATION_BATCH_APPROVE|" + String.join(",", sortedKeys);
        return runCommand("ALLOCATION_BATCH_APPROVE", commandKey, params, BatchApproveResponse.class, () -> {
            approveBatch(sortedKeys);
            List<AllocationResponse> responses = sortedKeys.stream()
                    .map(k -> toAllocationResponse(repository.findAllocationByKey(k))).toList();
            return new BatchApproveResponse(commandKey, sortedKeys.size(), responses);
        });
    }

    /** 取消申请：仅申请人本人可取消 REQUESTED/APPROVED，取消不可恢复并立即释放水量与逐小时预留。 */
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
     * 源扣减、目标批准、不可变流水、源逐小时释放台账在同一事务完成；目标按申请计划逐小时预留，
     * 任一小时超限整笔回滚。
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
            transferBatch(List.of(new TransferItemRequest(transferKey, sourceAllocationKey, targetAllocationKey)),
                    actor);
            return toTransferResponse(repository.findTransferByKey(transferKey));
        });
    }

    /**
     * 批量转让：多笔转让基于完整最终额度（源累计扣减不得超持）与逐小时预留（源释放、目标新增
     * 合并计算）一次裁决、一次事务提交，任一失败整批回滚，不留半成品。
     */
    public BatchTransferResponse batchTransfer(String commandKey, List<TransferItemRequest> items, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("X-Actor-Id", actor);
        if (items == null || items.isEmpty()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "transfers 不能为空");
        }
        List<TransferItemRequest> normalized = new ArrayList<>();
        TreeSet<String> transferKeys = new TreeSet<>();
        for (TransferItemRequest item : items) {
            if (item == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "转让项不能为空");
            }
            requireKey("transferKey", item.transferKey());
            requireKey("sourceAllocationKey", item.sourceAllocationKey());
            requireKey("targetAllocationKey", item.targetAllocationKey());
            if (!transferKeys.add(item.transferKey())) {
                throw ApiException.badRequest("INVALID_ARGUMENT",
                        "批量转让中 transferKey 重复: " + item.transferKey());
            }
            normalized.add(item);
        }
        String params = "TRANSFER_BATCH|" + actor + "|" + normalized.stream()
                .map(i -> i.transferKey() + ":" + i.sourceAllocationKey() + ">" + i.targetAllocationKey())
                .collect(Collectors.joining(";"));
        return runCommand("TRANSFER_BATCH", commandKey, params, BatchTransferResponse.class, () -> {
            transferBatch(normalized, actor);
            List<TransferResponse> responses = normalized.stream()
                    .map(i -> toTransferResponse(repository.findTransferByKey(i.transferKey()))).toList();
            return new BatchTransferResponse(commandKey, normalized.size(), responses);
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
     * 设置/修改窗口下泄曲线。points 必须恰好覆盖窗口全部 UTC 整点小时（窗口起止须为整点），
     * 不得重复、缺失或跨窗口；上限非负、最多两位小数。已存在曲线时，已开始小时的上限不得修改，
     * 否则返回 409；修改通过新增 ACTIVE 版本并把旧版本置为 SUPERSEDED 实现，历史快照不变。
     */
    public DrawdownCurveResponse setDrawdownCurve(String commandKey, long windowId,
                                                  List<DrawdownPointRequest> points) {
        requireKey("commandKey", commandKey);
        if (points == null || points.isEmpty()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "points 不能为空");
        }
        TreeMap<Long, BigDecimal> requested = new TreeMap<>();
        for (DrawdownPointRequest point : points) {
            if (point == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "曲线点不能为空");
            }
            long hour = parseHourUtc("hourUtc", point.hourUtc());
            BigDecimal cap = parseCap(point.capVolume());
            if (requested.put(hour, cap) != null) {
                throw ApiException.badRequest("INVALID_ARGUMENT",
                        "曲线存在重复小时点: " + point.hourUtc());
            }
        }
        String params = "DRAWDOWN_CURVE_SET|" + windowId + "|" + requested.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue().toPlainString())
                .collect(Collectors.joining(";"));
        return runCommand("DRAWDOWN_CURVE_SET", commandKey, params, DrawdownCurveResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            if (window.startNanos() % NANOS_PER_HOUR != 0 || window.endNanos() % NANOS_PER_HOUR != 0) {
                throw ApiException.badRequest("INVALID_ARGUMENT",
                        "配置下泄曲线要求窗口起止时间为 UTC 整点小时");
            }
            List<Long> windowHours = windowHours(window);
            if (!requested.navigableKeySet().equals(new TreeSet<>(windowHours))) {
                throw ApiException.badRequest("INVALID_ARGUMENT",
                        "曲线必须恰好覆盖窗口全部小时且不得跨窗口：要求 " + windowHours.size()
                                + " 个小时点（左闭右开），实际 " + requested.size() + " 个");
            }
            CurveRow previous = repository.lockActiveCurve(windowId);
            if (previous == null) {
                // 首次配置曲线：窗口不得存在已批准未取消申请，其批准未固化逐小时预留，无法纳入小时上限裁决
                Integer approved = repository.countApprovedActive(windowId);
                if (approved != null && approved > 0) {
                    throw ApiException.conflict("CURVE_EXISTING_APPROVALS",
                            "窗口已有 " + approved + " 笔已批准申请，无法首次配置下泄曲线；请先取消相关申请");
                }
            }
            if (previous != null) {
                Map<Long, BigDecimal> oldCaps = repository.listCurvePoints(previous.id()).stream()
                        .collect(Collectors.toMap(CurvePointRow::hourStartNanos, CurvePointRow::capVolume));
                long now = nowNanos();
                List<String> changedStarted = new ArrayList<>();
                for (long hour : windowHours) {
                    // hourStart <= 当前时刻意味着该小时区间 [hourStart, hourStart+1h) 已经开始
                    boolean started = hour <= now;
                    BigDecimal oldCap = oldCaps.get(hour);
                    if (started && oldCap != null && oldCap.compareTo(requested.get(hour)) != 0) {
                        changedStarted.add(toIso(hour));
                    }
                }
                if (!changedStarted.isEmpty()) {
                    throw ApiException.conflict("DRAWDOWN_HOUR_STARTED",
                            "已开始的小时不能修改曲线上限（实际尝试修改 " + changedStarted.size() + " 个）: "
                                    + String.join(", ", changedStarted));
                }
                repository.supersedeActiveCurve(windowId);
            }
            int version = repository.nextCurveVersion(windowId);
            long now = nowNanos();
            long curveId = repository.insertCurve(windowId, version, now);
            for (Map.Entry<Long, BigDecimal> entry : requested.entrySet()) {
                repository.insertCurvePoint(curveId, windowId, entry.getKey(), entry.getValue());
            }
            return toCurveResponse(repository.findCurveById(curveId),
                    repository.listCurvePoints(curveId));
        });
    }

    /**
     * 实际下泄核销：只扣减申请在 hourUtc 所在小时的未核销预留（快照预留 - 转让释放 - 已核销），
     * 不能挪用其他小时余量；申请人本人操作，申请须 APPROVED。超额返回 422 并给出实际值/要求值/差额。
     */
    public WriteoffResponse writeoff(String commandKey, String allocationKey, String writeoffKey,
                                     String hourUtc, String amount, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("writeoffKey", writeoffKey);
        requireKey("allocationKey", allocationKey);
        requireKey("X-Actor-Id", actor);
        long hour = parseHourUtc("hourUtc", hourUtc);
        BigDecimal qty = parseAmount("amount", amount);
        String params = "DRAWDOWN_WRITEOFF|" + writeoffKey + "|" + allocationKey + "|" + hour + "|"
                + qty.toPlainString() + "|" + actor;
        return runCommand("DRAWDOWN_WRITEOFF", commandKey, params, WriteoffResponse.class, () -> {
            if (repository.findWriteoffByKey(writeoffKey) != null) {
                throw ApiException.conflict("WRITEOFF_KEY_REUSED", "writeoffKey 已被使用: " + writeoffKey);
            }
            AllocationRow allocation = repository.findAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            WindowRow window = lockWindowOf(allocation);
            allocation = repository.lockAllocationByKey(allocationKey);
            if (!allocation.requester().equals(actor)) {
                throw ApiException.conflict("NOT_OWNER", "只有申请人本人可以核销该申请");
            }
            if (!STATUS_APPROVED.equals(allocation.status())) {
                throw ApiException.conflict("ALLOCATION_NOT_APPROVED",
                        "只有 APPROVED 状态的申请可以核销，当前状态: " + allocation.status());
            }
            if (hour < window.startNanos() || hour >= window.endNanos()) {
                throw ApiException.badRequest("INVALID_ARGUMENT",
                        "核销小时必须落在窗口 [startUtc, endUtc) 内: " + hourUtc);
            }
            BigDecimal reserved = repository.sumReservation(allocation.id(), hour);
            if (reserved.signum() == 0) {
                throw ApiException.conflict("HOUR_NOT_RESERVED",
                        "该申请在 " + hourUtc + " 小时没有预留额度，不能核销");
            }
            BigDecimal outstanding = repository.outstandingReservation(allocation.id(), hour);
            if (qty.compareTo(outstanding) > 0) {
                BigDecimal deficit = qty.subtract(outstanding);
                throw ApiException.drawdownExceeded(
                        "核销水量 " + fmt(qty) + " 超过该申请该小时未核销预留 " + fmt(outstanding)
                                + "，差额 " + fmt(deficit) + "（要求 " + fmt(qty) + "，实际可核销 "
                                + fmt(outstanding) + "），不能挪用其他小时余量",
                        List.of(new HourViolation(window.id(), toIso(hour), null, fmt(reserved),
                                fmt(qty), fmt(qty), fmt(deficit))));
            }
            long now = nowNanos();
            try {
                repository.insertWriteoff(writeoffKey, allocation.id(), window.id(), hour, qty, actor, now);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("WRITEOFF_KEY_REUSED", "writeoffKey 已被使用: " + writeoffKey);
            }
            return toWriteoffResponse(repository.findWriteoffByKey(writeoffKey), allocationKey);
        });
    }

    /** 查询窗口逐小时下泄诊断（只读）：当前曲线版本、每小时上限、已核销、已批准未核销、占用合计与余量。 */
    public DrawdownStatusResponse getDrawdownStatus(long windowId) {
        WindowRow window = requireWindow(windowId);
        CurveRow curve = repository.findActiveCurve(windowId);
        if (curve == null) {
            return new DrawdownStatusResponse(windowId, null, List.of());
        }
        Map<Long, BigDecimal> caps = repository.listCurvePoints(curve.id()).stream()
                .collect(Collectors.toMap(CurvePointRow::hourStartNanos, CurvePointRow::capVolume));
        Map<Long, HourTotalRow> totals = repository.listHourTotals(windowId).stream()
                .collect(Collectors.toMap(HourTotalRow::hourStartNanos, t -> t));
        List<DrawdownHourStatusResponse> hours = new ArrayList<>();
        for (long hour : windowHours(window)) {
            HourTotalRow total = totals.get(hour);
            BigDecimal consumed = total == null ? BigDecimal.ZERO : total.reservedTotal();
            BigDecimal writtenOff = total == null ? BigDecimal.ZERO : total.writtenOffTotal();
            BigDecimal cap = caps.getOrDefault(hour, BigDecimal.ZERO);
            BigDecimal unwritten = consumed.subtract(writtenOff);
            hours.add(new DrawdownHourStatusResponse(toIso(hour), fmt(cap), fmt(consumed), fmt(writtenOff),
                    fmt(unwritten), fmt(cap.subtract(consumed))));
        }
        return new DrawdownStatusResponse(windowId, curve.version(), hours);
    }

    /** 查询单条申请明细（只读），含批准时固化的曲线版本号与逐小时预留快照。 */
    public AllocationResponse getAllocation(String allocationKey) {
        AllocationRow allocation = repository.findAllocationByKey(allocationKey);
        if (allocation == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
        }
        return toAllocationResponse(allocation);
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

    /** 查询窗口历史明细：窗口 + 全部申请（含预留快照）+ 全部限供 + 当前曲线版本 + 全部核销流水（只读）。 */
    public HistoryResponse getHistory(long windowId) {
        WindowRow window = requireWindow(windowId);
        CurtailmentRow active = repository.findActiveCurtailment(windowId);
        CurveRow activeCurve = repository.findActiveCurve(windowId);
        Map<Long, List<ReservationRow>> reservationsByAllocation = repository.listReservationsByWindow(windowId)
                .stream().collect(Collectors.groupingBy(ReservationRow::allocationId));
        Map<Long, Integer> curveVersions = new HashMap<>();
        List<AllocationResponse> allocations = repository.listAllocations(windowId).stream()
                .map(row -> toAllocationResponse(row,
                        reservationsByAllocation.getOrDefault(row.id(), List.of()), curveVersions))
                .toList();
        List<CurtailmentResponse> curtailments = repository.listCurtailments(windowId).stream()
                .map(this::toCurtailmentResponse).toList();
        Map<Long, String> allocationKeys = repository.listAllocations(windowId).stream()
                .collect(Collectors.toMap(AllocationRow::id, AllocationRow::allocationKey));
        List<WriteoffResponse> writeoffs = repository.listWriteoffs(windowId).stream()
                .map(w -> toWriteoffResponse(w, allocationKeys.get(w.allocationId()))).toList();
        DrawdownCurveResponse curveResponse = activeCurve == null ? null
                : toCurveResponse(activeCurve, repository.listCurvePoints(activeCurve.id()));
        return new HistoryResponse(toWindowResponse(window, active), allocations, curtailments,
                curveResponse, writeoffs);
    }

    // ------------------------------------------------------------------
    // 批准与转让的事务内实现
    // ------------------------------------------------------------------

    /** 窗口锁内单笔批准：状态校验、总量配额、逐小时下泄预留，全部通过后固化快照。 */
    private AllocationResponse approveOne(String allocationKey) {
        AllocationRow allocation = repository.findAllocationByKey(allocationKey);
        if (allocation == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
        }
        lockWindowOf(allocation);
        // 窗口锁内重读申请，避免与转让/取消并发时使用过期状态与持有额度
        allocation = repository.lockAllocationByKey(allocationKey);
        ensureApprovable(allocation);
        Map<Long, WindowRow> windows = Map.of(allocation.windowId(),
                requireWindowLocked(allocation.windowId()));
        checkQuotaAndDrawdown(List.of(allocation), windows);
        applyApprovals(List.of(allocation));
        return toAllocationResponse(repository.findAllocationByKey(allocationKey));
    }

    /** 窗口锁内批量批准：任一申请状态/总量/逐小时不满足即抛异常，整批回滚。 */
    private void approveBatch(List<String> allocationKeys) {
        List<AllocationRow> fetched = new ArrayList<>();
        for (String key : allocationKeys) {
            AllocationRow allocation = repository.findAllocationByKey(key);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + key);
            }
            fetched.add(allocation);
        }
        Map<Long, WindowRow> windows = lockWindows(fetched.stream().map(AllocationRow::windowId).toList());
        List<AllocationRow> locked = new ArrayList<>();
        for (String key : allocationKeys) {
            AllocationRow allocation = repository.lockAllocationByKey(key);
            ensureApprovable(allocation);
            locked.add(allocation);
        }
        checkQuotaAndDrawdown(locked, windows);
        applyApprovals(locked);
    }

    private void ensureApprovable(AllocationRow allocation) {
        switch (allocation.status()) {
            case STATUS_CANCELLED ->
                    throw ApiException.conflict("ALLOCATION_CANCELLED", "已取消的申请不能批准");
            case STATUS_APPROVED ->
                    throw ApiException.conflict("ALLOCATION_ALREADY_APPROVED", "申请已批准，不能重复批准");
            default -> {
            }
        }
    }

    /**
     * 基于整批最终状态校验总量配额与逐小时下泄上限。
     * 新增预留按窗口小时累加：已核销 + 已批准未核销（含本批新增）超过曲线上限即收集为违规，
     * 全部小时检查完后一次性抛出 422。
     */
    private void checkQuotaAndDrawdown(List<AllocationRow> candidates, Map<Long, WindowRow> windows) {
        // 1) 总量配额：窗口已批准持有额度 + 本批新增
        Map<Long, BigDecimal> addedByWindow = candidates.stream()
                .collect(Collectors.groupingBy(AllocationRow::windowId,
                        Collectors.mapping(AllocationRow::amount, Collectors.reducing(BigDecimal.ZERO,
                                BigDecimal::add))));
        for (Map.Entry<Long, BigDecimal> entry : addedByWindow.entrySet()) {
            WindowRow window = windows.get(entry.getKey());
            BigDecimal available = availableTotal(window);
            BigDecimal approved = repository.sumApprovedAmount(window.id());
            if (approved.add(entry.getValue()).compareTo(available) > 0) {
                throw ApiException.quotaExceeded("窗口 " + window.id() + " 批准后将超过当前可用总量 "
                        + fmt(available) + "（已批准 " + fmt(approved) + "，本次要求新增 "
                        + fmt(entry.getValue()) + "，差额 "
                        + fmt(approved.add(entry.getValue()).subtract(available)) + "）");
            }
        }
        // 2) 逐小时下泄上限
        Map<Long, CurveRow> curves = new HashMap<>();
        Map<Long, Map<Long, BigDecimal>> capsByWindow = new HashMap<>();
        Map<Long, Map<Long, BigDecimal>> currentByWindow = new HashMap<>();
        for (Long windowId : windows.keySet()) {
            CurveRow curve = repository.findActiveCurve(windowId);
            if (curve == null) {
                continue;
            }
            curves.put(windowId, curve);
            capsByWindow.put(windowId, repository.listCurvePoints(curve.id()).stream()
                    .collect(Collectors.toMap(CurvePointRow::hourStartNanos, CurvePointRow::capVolume)));
            currentByWindow.put(windowId, repository.listHourTotals(windowId).stream()
                    .collect(Collectors.toMap(HourTotalRow::hourStartNanos, HourTotalRow::reservedTotal)));
        }
        if (curves.isEmpty()) {
            return;
        }
        // 本批每个窗口逐小时新增预留累计
        Map<Long, Map<Long, BigDecimal>> additionsByWindow = new HashMap<>();
        for (AllocationRow candidate : candidates) {
            CurveRow curve = curves.get(candidate.windowId());
            if (curve == null) {
                continue;
            }
            List<PlanItemRow> plan = repository.listPlanItems(candidate.id());
            // 曲线配置之前提交的申请没有逐小时计划，曲线存在后不允许批准（无法按小时预留）
            if (plan.isEmpty()) {
                throw ApiException.conflict("ALLOCATION_HOURLY_PLAN_MISSING",
                        "窗口已配置下泄曲线，但申请 " + candidate.allocationKey()
                                + " 没有逐小时配水计划，不能批准或转入");
            }
            Map<Long, BigDecimal> additions =
                    additionsByWindow.computeIfAbsent(candidate.windowId(), k -> new HashMap<>());
            for (PlanItemRow item : plan) {
                additions.merge(item.hourStartNanos(), item.volume(), BigDecimal::add);
            }
        }
        List<HourViolation> violations = new ArrayList<>();
        for (Map.Entry<Long, Map<Long, BigDecimal>> entry : additionsByWindow.entrySet()) {
            long windowId = entry.getKey();
            Map<Long, BigDecimal> caps = capsByWindow.get(windowId);
            Map<Long, BigDecimal> current = currentByWindow.get(windowId);
            for (Long hour : new TreeSet<>(entry.getValue().keySet())) {
                BigDecimal cap = caps.get(hour);
                if (cap == null) {
                    throw ApiException.badRequest("INVALID_ARGUMENT",
                            "计划小时不在当前曲线覆盖范围内: " + toIso(hour));
                }
                BigDecimal reserved = current.getOrDefault(hour, BigDecimal.ZERO);
                BigDecimal requested = entry.getValue().get(hour);
                BigDecimal required = reserved.add(requested);
                if (required.compareTo(cap) > 0) {
                    violations.add(new HourViolation(windowId, toIso(hour), fmt(cap), fmt(reserved),
                            fmt(requested), fmt(required), fmt(required.subtract(cap))));
                }
            }
        }
        if (!violations.isEmpty()) {
            violations.sort(Comparator.comparingLong(HourViolation::windowId)
                    .thenComparing(HourViolation::hourUtc));
            throw ApiException.drawdownExceeded(
                    "逐小时下泄预留超过上限，共 " + violations.size() + " 个超限小时（区间左闭右开）",
                    violations);
        }
    }

    /** 落库批准：状态 APPROVED、持有额度等于原申请水量、固化曲线版本与逐小时预留快照。 */
    private void applyApprovals(List<AllocationRow> allocations) {
        long now = nowNanos();
        for (AllocationRow allocation : allocations) {
            CurveRow curve = repository.findActiveCurve(allocation.windowId());
            Long curveId = curve == null ? null : curve.id();
            repository.approveAllocation(allocation.id(), curveId, now);
            if (curve != null) {
                for (PlanItemRow item : repository.listPlanItems(allocation.id())) {
                    repository.insertReservation(allocation.id(), allocation.windowId(),
                            item.hourStartNanos(), item.volume(), curve.id(), now);
                }
            }
        }
    }

    /**
     * 批量转让事务体：锁定相关窗口与全部源/目标申请，先在内存中核对完整最终状态
     * （状态、同人/同窗口/不同用水户、源持有额度、逐小时释放与新增预留），全部通过后一次落库。
     */
    private void transferBatch(List<TransferItemRequest> items, String actor) {
        // 业务键预检（无需锁）
        for (TransferItemRequest item : items) {
            if (repository.findTransferByKey(item.transferKey()) != null) {
                throw ApiException.conflict("TRANSFER_KEY_REUSED",
                        "transferKey 已被使用: " + item.transferKey());
            }
        }
        Map<String, AllocationRow> fetched = new HashMap<>();
        for (TransferItemRequest item : items) {
            AllocationRow source = repository.findAllocationByKey(item.sourceAllocationKey());
            if (source == null) {
                throw ApiException.notFound("SOURCE_ALLOCATION_NOT_FOUND",
                        "转出申请不存在: " + item.sourceAllocationKey());
            }
            AllocationRow target = repository.findAllocationByKey(item.targetAllocationKey());
            if (target == null) {
                throw ApiException.notFound("TARGET_ALLOCATION_NOT_FOUND",
                        "转入申请不存在: " + item.targetAllocationKey());
            }
            fetched.put(item.sourceAllocationKey(), source);
            fetched.put(item.targetAllocationKey(), target);
        }
        // 同一申请不能在本批中既转出又转入
        for (TransferItemRequest item : items) {
            if (item.sourceAllocationKey().equals(item.targetAllocationKey())) {
                throw ApiException.conflict("SAME_ALLOCATION", "转让源与目标不能是同一申请");
            }
        }
        Set<String> sourceKeys = items.stream().map(TransferItemRequest::sourceAllocationKey)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> targetKeys = items.stream().map(TransferItemRequest::targetAllocationKey)
                .collect(Collectors.toCollection(TreeSet::new));
        TreeSet<String> bothRoles = new TreeSet<>(sourceKeys);
        bothRoles.retainAll(targetKeys);
        if (!bothRoles.isEmpty()) {
            throw ApiException.conflict("ALLOCATION_ROLE_CONFLICT",
                    "同一申请不能在批量转让中同时作为转出方与转入方: " + String.join(", ", bothRoles));
        }
        TreeSet<String> duplicateTargets = new TreeSet<>();
        TreeSet<String> seenTargets = new TreeSet<>();
        for (String key : targetKeys) {
            if (!seenTargets.add(key)) {
                duplicateTargets.add(key);
            }
        }
        if (!duplicateTargets.isEmpty()) {
            throw ApiException.conflict("DUPLICATE_TARGET_IN_BATCH",
                    "批量转让中同一转入申请出现多次: " + String.join(", ", duplicateTargets));
        }

        // 锁全部相关窗口（按 id 升序，避免死锁），再锁全部相关申请（按业务键升序）
        List<Long> windowIds = fetched.values().stream().map(AllocationRow::windowId).distinct()
                .sorted().toList();
        Map<Long, WindowRow> windows = lockWindows(windowIds);
        TreeSet<String> allKeys = new TreeSet<>();
        allKeys.addAll(sourceKeys);
        allKeys.addAll(targetKeys);
        Map<String, AllocationRow> locked = new LinkedHashMap<>();
        for (String key : allKeys) {
            locked.put(key, repository.lockAllocationByKey(key));
        }

        // 逐笔静态校验
        for (TransferItemRequest item : items) {
            AllocationRow source = locked.get(item.sourceAllocationKey());
            AllocationRow target = locked.get(item.targetAllocationKey());
            if (!source.requester().equals(actor)) {
                throw ApiException.conflict("NOT_OWNER",
                        "只有转出申请人本人可以发起转让: " + item.sourceAllocationKey());
            }
            if (!STATUS_APPROVED.equals(source.status())) {
                throw ApiException.conflict("SOURCE_NOT_APPROVED",
                        "转出申请必须为 APPROVED 状态: " + item.sourceAllocationKey());
            }
            if (!STATUS_REQUESTED.equals(target.status())) {
                throw ApiException.conflict("TARGET_NOT_REQUESTED",
                        "转入申请必须为 REQUESTED 状态: " + item.targetAllocationKey());
            }
            if (source.windowId() != target.windowId()) {
                throw ApiException.conflict("DIFFERENT_WINDOW",
                        "转让双方必须属于同一供水窗口: " + item.transferKey());
            }
            if (source.userId().equals(target.userId())) {
                throw ApiException.conflict("SAME_USER",
                        "转让双方必须属于不同用水户: " + item.transferKey());
            }
            if (repository.findActiveCurve(source.windowId()) != null
                    && repository.listPlanItems(target.id()).isEmpty()) {
                throw ApiException.conflict("ALLOCATION_HOURLY_PLAN_MISSING",
                        "窗口已配置下泄曲线，但转入申请 " + target.allocationKey()
                                + " 没有逐小时配水计划，不能转让批准");
            }
        }

        // 完整最终额度：源累计扣减
        Map<String, BigDecimal> finalHeld = new HashMap<>();
        for (String key : sourceKeys) {
            finalHeld.put(key, locked.get(key).heldAmount());
        }
        for (TransferItemRequest item : items) {
            AllocationRow target = locked.get(item.targetAllocationKey());
            BigDecimal amount = target.amount();
            BigDecimal held = finalHeld.get(item.sourceAllocationKey());
            if (held.compareTo(amount) < 0) {
                throw ApiException.quotaExceeded("源申请 " + item.sourceAllocationKey()
                        + " 批量转让后当前持有额度不足：实际剩余 " + fmt(held) + "，要求再转出 "
                        + fmt(amount) + "，差额 " + fmt(amount.subtract(held)));
            }
            finalHeld.put(item.sourceAllocationKey(), held.subtract(amount));
        }

        // 完整逐小时预留：源按快照比例释放、目标按计划新增，按窗口合并
        Map<Long, Map<Long, BigDecimal>> existingByWindow = new HashMap<>();
        Map<Long, Map<Long, BigDecimal>> capByWindow = new HashMap<>();
        for (Long windowId : windowIds) {
            existingByWindow.put(windowId, repository.listHourTotals(windowId).stream()
                    .collect(Collectors.toMap(HourTotalRow::hourStartNanos, HourTotalRow::reservedTotal)));
            CurveRow curve = repository.findActiveCurve(windowId);
            if (curve != null) {
                capByWindow.put(windowId, repository.listCurvePoints(curve.id()).stream()
                        .collect(Collectors.toMap(CurvePointRow::hourStartNanos, CurvePointRow::capVolume)));
            }
        }
        // 源在本批内累计释放（小时 -> 水量），同一源多笔转让顺序分摊
        Map<String, Map<Long, BigDecimal>> batchReleasedBySource = new HashMap<>();
        // 每笔转让逐小时释放结果，供落库使用
        List<Map<Long, BigDecimal>> releasesPerItem = new ArrayList<>();
        Map<Long, Map<Long, BigDecimal>> netAddByWindow = new HashMap<>();
        for (TransferItemRequest item : items) {
            AllocationRow source = locked.get(item.sourceAllocationKey());
            AllocationRow target = locked.get(item.targetAllocationKey());
            BigDecimal amount = target.amount();
            Map<Long, BigDecimal> releaseHours = computeProportionalRelease(source, amount,
                    batchReleasedBySource.computeIfAbsent(source.allocationKey(), k -> new HashMap<>()));
            releasesPerItem.add(releaseHours);
            Map<Long, BigDecimal> netAdd =
                    netAddByWindow.computeIfAbsent(source.windowId(), k -> new HashMap<>());
            for (Map.Entry<Long, BigDecimal> e : releaseHours.entrySet()) {
                netAdd.merge(e.getKey(), e.getValue().negate(), BigDecimal::add);
            }
            CurveRow curve = repository.findActiveCurve(source.windowId());
            if (curve != null) {
                for (PlanItemRow planItem : repository.listPlanItems(target.id())) {
                    netAdd.merge(planItem.hourStartNanos(), planItem.volume(), BigDecimal::add);
                }
            }
        }

        // 逐小时最终占用校验
        List<HourViolation> violations = new ArrayList<>();
        for (Map.Entry<Long, Map<Long, BigDecimal>> entry : netAddByWindow.entrySet()) {
            long windowId = entry.getKey();
            Map<Long, BigDecimal> existing = existingByWindow.get(windowId);
            Map<Long, BigDecimal> caps = capByWindow.get(windowId);
            for (Long hour : new TreeSet<>(entry.getValue().keySet())) {
                BigDecimal delta = entry.getValue().get(hour);
                BigDecimal required = existing.getOrDefault(hour, BigDecimal.ZERO).add(delta);
                if (caps != null) {
                    BigDecimal cap = caps.get(hour);
                    if (cap != null && required.compareTo(cap) > 0) {
                        BigDecimal reserved = existing.getOrDefault(hour, BigDecimal.ZERO);
                        violations.add(new HourViolation(windowId, toIso(hour), fmt(cap), fmt(reserved),
                                fmt(delta.max(BigDecimal.ZERO)), fmt(required),
                                fmt(required.subtract(cap))));
                    }
                }
                if (required.signum() < 0) {
                    // 释放超过现有占用属于内部不变量被破坏，防御性拦截
                    throw ApiException.conflict("DRAWDOWN_RELEASE_INVALID",
                            "小时 " + toIso(hour) + " 转让释放后占用为负，数据不一致");
                }
            }
        }
        if (!violations.isEmpty()) {
            violations.sort(Comparator.comparingLong(HourViolation::windowId)
                    .thenComparing(HourViolation::hourUtc));
            throw ApiException.drawdownExceeded(
                    "批量转让后逐小时下泄预留超过上限，共 " + violations.size() + " 个超限小时",
                    violations);
        }

        // 一次落库：源扣减、目标批准+快照、流水、释放台账
        long now = nowNanos();
        for (int i = 0; i < items.size(); i++) {
            TransferItemRequest item = items.get(i);
            AllocationRow source = locked.get(item.sourceAllocationKey());
            AllocationRow target = locked.get(item.targetAllocationKey());
            BigDecimal amount = target.amount();
            repository.setHeldAmount(source.id(), finalHeld.get(source.allocationKey()), now);
            CurveRow curve = repository.findActiveCurve(source.windowId());
            Long curveId = curve == null ? null : curve.id();
            repository.approveAllocation(target.id(), curveId, now);
            if (curve != null) {
                for (PlanItemRow planItem : repository.listPlanItems(target.id())) {
                    repository.insertReservation(target.id(), target.windowId(),
                            planItem.hourStartNanos(), planItem.volume(), curve.id(), now);
                }
            }
            long transferId;
            try {
                transferId = repository.insertTransfer(item.transferKey(), source.windowId(),
                        item.sourceAllocationKey(), item.targetAllocationKey(), amount, actor, now);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("TRANSFER_KEY_REUSED",
                        "transferKey 已被使用: " + item.transferKey());
            }
            for (Map.Entry<Long, BigDecimal> e : releasesPerItem.get(i).entrySet()) {
                if (e.getValue().signum() > 0) {
                    repository.insertReservationRelease(transferId, item.transferKey(), source.id(),
                            source.windowId(), e.getKey(), e.getValue(), now);
                }
            }
        }
    }

    /**
     * 按源申请快照预留分布，把一笔转让额度比例分摊到各小时（3 位小数 HALF_UP），
     * 并把舍入余缺集中到余量最大的小时。每小时可释放空间为
     * 快照预留 - 已落库释放 - 本批已释放 - 已核销（已实际下泄的水量不能被转让释放），
     * 分摊总和必须恰为转让额。
     */
    private Map<Long, BigDecimal> computeProportionalRelease(AllocationRow source, BigDecimal amount,
                                                             Map<Long, BigDecimal> batchReleased) {
        List<ReservationRow> snapshot = repository.listReservations(source.id());
        if (snapshot.isEmpty()) {
            // 无曲线窗口批准的申请没有逐小时快照：不写逐小时释放台账（无曲线时不做小时裁决）
            return Map.of();
        }
        BigDecimal scale = amount.divide(source.amount(), 6, RoundingMode.HALF_UP);
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        BigDecimal allocated = BigDecimal.ZERO;
        for (ReservationRow row : snapshot) {
            BigDecimal part = row.reservedVolume().multiply(scale).setScale(3, RoundingMode.HALF_UP);
            BigDecimal available = hourReleaseRoom(source, row.hourStartNanos(), BigDecimal.ZERO,
                    batchReleased);
            if (part.compareTo(available) > 0) {
                part = available;
            }
            result.put(row.hourStartNanos(), part);
            allocated = allocated.add(part);
        }
        BigDecimal diff = amount.subtract(allocated);
        if (diff.signum() != 0) {
            // 在仍有空间的小时中按剩余容量大小依次消化余缺
            List<Long> adjustable = new ArrayList<>(result.keySet());
            adjustable.sort((h1, h2) -> {
                BigDecimal a1 = hourReleaseRoom(source, h1, result.get(h1), batchReleased);
                BigDecimal a2 = hourReleaseRoom(source, h2, result.get(h2), batchReleased);
                return a2.compareTo(a1);
            });
            for (Long hour : adjustable) {
                if (diff.signum() == 0) {
                    break;
                }
                BigDecimal current = result.get(hour);
                if (diff.signum() > 0) {
                    BigDecimal room = hourReleaseRoom(source, hour, current, batchReleased);
                    BigDecimal step = room.min(diff);
                    result.put(hour, current.add(step));
                    diff = diff.subtract(step);
                } else {
                    BigDecimal step = current.min(diff.negate());
                    result.put(hour, current.subtract(step));
                    diff = diff.add(step);
                }
            }
        }
        if (diff.signum() != 0) {
            throw ApiException.quotaExceeded("源申请 " + source.allocationKey()
                    + " 逐小时未核销余量不足以分摊转让额度 " + fmt(amount) + "，未分摊差额 "
                    + fmt(diff.abs()) + "（已核销水量不能转让，且不能挪用其他小时余量）");
        }
        for (Map.Entry<Long, BigDecimal> e : result.entrySet()) {
            batchReleased.merge(e.getKey(), e.getValue(), BigDecimal::add);
        }
        return result;
    }

    /**
     * 某小时还可继续释放的空间 = 快照预留 - 已落库释放 - 本批已释放 - 本次拟释放 - 已核销；
     * 已核销代表已实际下泄，仍占用该小时上限，不得随转让释放。
     */
    private BigDecimal hourReleaseRoom(AllocationRow source, long hour, BigDecimal currentPart,
                                       Map<Long, BigDecimal> batchReleased) {
        BigDecimal snapshot = repository.sumReservation(source.id(), hour);
        BigDecimal used = repository.sumReleased(source.id(), hour)
                .add(batchReleased.getOrDefault(hour, BigDecimal.ZERO))
                .add(repository.sumWriteoff(source.id(), hour));
        return snapshot.subtract(used).subtract(currentPart).max(BigDecimal.ZERO);
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

    private WindowRow requireWindow(long windowId) {
        WindowRow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        return window;
    }

    private WindowRow requireWindowLocked(long windowId) {
        WindowRow window = repository.lockWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        return window;
    }

    private Map<Long, WindowRow> lockWindows(List<Long> windowIds) {
        Map<Long, WindowRow> windows = new LinkedHashMap<>();
        windowIds.stream().distinct().sorted().forEach(id -> windows.put(id, requireWindowLocked(id)));
        return windows;
    }

    private WindowRow lockWindowOf(AllocationRow allocation) {
        return requireWindowLocked(allocation.windowId());
    }

    private BigDecimal availableTotal(WindowRow window) {
        CurtailmentRow active = repository.findActiveCurtailment(window.id());
        return active != null ? active.volume() : window.plannedVolume();
    }

    /** 窗口覆盖的全部 UTC 整点小时（左闭右开），按升序。 */
    private List<Long> windowHours(WindowRow window) {
        List<Long> hours = new ArrayList<>();
        for (long hour = window.startNanos(); hour < window.endNanos(); hour += NANOS_PER_HOUR) {
            hours.add(hour);
        }
        return hours;
    }

    /**
     * 校验申请逐小时计划：有曲线窗口必须提交与窗口小时一一对应的计划，每小时水量大于 0、
     * 最多 3 位小数，且各小时之和恰等于申请水量；无曲线窗口不得携带计划。
     */
    private List<PlanItemRow> validateAndNormalizePlan(WindowRow window, CurveRow curve, BigDecimal amount,
                                                       List<PlanItemRequest> plan) {
        boolean hasPlan = plan != null && !plan.isEmpty();
        if (curve == null) {
            // 无曲线窗口不做逐小时预留，也不持久化计划（配置曲线之前提交的申请不能在有曲线后批准）
            return List.of();
        }
        if (!hasPlan) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "窗口已配置下泄曲线，提交申请必须包含逐小时配水计划 plan");
        }
        TreeMap<Long, BigDecimal> byHour = new TreeMap<>();
        for (PlanItemRequest item : plan) {
            if (item == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "计划点不能为空");
            }
            long hour = parseHourUtc("plan.hourUtc", item.hourUtc());
            BigDecimal volume = parseAmount("plan.volume", item.volume());
            if (byHour.put(hour, volume) != null) {
                throw ApiException.badRequest("INVALID_ARGUMENT",
                        "配水计划存在重复小时: " + item.hourUtc());
            }
        }
        TreeSet<Long> windowHours = new TreeSet<>(windowHours(window));
        for (long hour : byHour.keySet()) {
            if (!windowHours.contains(hour)) {
                throw ApiException.badRequest("INVALID_ARGUMENT",
                        "配水计划小时必须落在窗口 [startUtc, endUtc) 内且为 UTC 整点，不得跨窗口: "
                                + toIso(hour));
            }
        }
        BigDecimal sum = byHour.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(amount) != 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "配水计划各小时水量之和必须等于申请水量：要求 " + fmt(amount) + "，实际 "
                            + fmt(sum) + "，差额 " + fmt(amount.subtract(sum).abs()));
        }
        List<PlanItemRow> rows = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> entry : byHour.entrySet()) {
            rows.add(new PlanItemRow(0, window.id(), entry.getKey(), entry.getValue()));
        }
        return rows;
    }

    /** 计划参数规范化串（按小时排序），用于幂等同键改参比对。 */
    private String canonicalPlan(List<PlanItemRequest> plan) {
        if (plan == null || plan.isEmpty()) {
            return "";
        }
        return plan.stream()
                .filter(p -> p != null)
                .sorted(Comparator.comparing(p -> p.hourUtc() == null ? "" : p.hourUtc()))
                .map(p -> p.hourUtc() + "=" + p.volume())
                .collect(Collectors.joining(";"));
    }

    private WindowResponse toWindowResponse(WindowRow row, CurtailmentRow active) {
        BigDecimal available = active != null ? active.volume() : row.plannedVolume();
        return new WindowResponse(row.id(), row.windowKey(), row.channelId(), toIso(row.startNanos()),
                toIso(row.endNanos()), fmt(row.plannedVolume()),
                active != null ? fmt(active.volume()) : null, fmt(available), toIso(row.createdNanos()));
    }

    private AllocationResponse toAllocationResponse(AllocationRow row) {
        List<ReservationRow> reservations = repository.listReservations(row.id());
        return toAllocationResponse(row, reservations, new HashMap<>());
    }

    private AllocationResponse toAllocationResponse(AllocationRow row, List<ReservationRow> reservations,
                                                    Map<Long, Integer> curveVersionCache) {
        Integer curveVersion = null;
        if (row.approvedCurveId() != null) {
            curveVersion = curveVersionCache.computeIfAbsent(row.approvedCurveId(), id -> {
                CurveRow curve = repository.findCurveById(id);
                return curve == null ? null : curve.version();
            });
        }
        List<ReservationResponse> reservationResponses = reservations.stream()
                .map(r -> new ReservationResponse(toIso(r.hourStartNanos()), fmt(r.reservedVolume()),
                        curveVersionOf(r.curveId(), curveVersionCache), toIso(r.createdNanos())))
                .toList();
        return new AllocationResponse(row.allocationKey(), row.windowId(), row.userId(), fmt(row.amount()),
                fmt(row.heldAmount()), row.requester(), row.status(),
                toIso(row.createdNanos()), toIso(row.updatedNanos()), curveVersion, reservationResponses);
    }

    private int curveVersionOf(long curveId, Map<Long, Integer> cache) {
        return cache.computeIfAbsent(curveId, id -> {
            CurveRow curve = repository.findCurveById(id);
            return curve == null ? 0 : curve.version();
        });
    }

    private TransferResponse toTransferResponse(TransferRow row) {
        return new TransferResponse(row.transferKey(), row.windowId(), row.sourceAllocationKey(),
                row.targetAllocationKey(), fmt(row.amount()), row.actor(), toIso(row.createdNanos()));
    }

    private CurtailmentResponse toCurtailmentResponse(CurtailmentRow row) {
        return new CurtailmentResponse(row.id(), row.windowId(), fmt(row.volume()), row.status(),
                toIso(row.createdNanos()), row.cancelledNanos() == null ? null : toIso(row.cancelledNanos()));
    }

    private DrawdownCurveResponse toCurveResponse(CurveRow curve, List<CurvePointRow> points) {
        List<DrawdownPointResponse> pointResponses = points.stream()
                .map(p -> new DrawdownPointResponse(toIso(p.hourStartNanos()), fmt(p.capVolume())))
                .toList();
        return new DrawdownCurveResponse(curve.id(), curve.windowId(), curve.version(), curve.status(),
                pointResponses, toIso(curve.createdNanos()));
    }

    private WriteoffResponse toWriteoffResponse(WriteoffRow row, String allocationKey) {
        return new WriteoffResponse(row.writeoffKey(), allocationKey, row.windowId(),
                toIso(row.hourStartNanos()), fmt(row.amount()), row.actor(), toIso(row.createdNanos()));
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

    /** 解析 UTC 整点小时：必须是合法 UTC 时刻且恰好落在整点（纳秒部分为 0、秒数为 3600 整数倍）。 */
    static long parseHourUtc(String field, String value) {
        long nanos = parseInstant(field, value);
        if (Math.floorMod(nanos, NANOS_PER_HOUR) != 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 必须为 UTC 整点小时: " + value);
        }
        return nanos;
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

    /** 解析下泄上限：十进制字符串，非负，最多 2 位小数。 */
    static BigDecimal parseCap(String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "capVolume 不能为空");
        }
        String trimmed = value.trim();
        if (!CAP_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "capVolume 必须为非负十进制字符串，最多 2 位小数: " + value);
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
