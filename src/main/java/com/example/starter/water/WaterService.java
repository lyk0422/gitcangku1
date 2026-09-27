package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.DrawdownCurveRow;
import com.example.starter.water.WaterRepository.DrawdownPointInput;
import com.example.starter.water.WaterRepository.DrawdownPointRow;
import com.example.starter.water.WaterRepository.HourReservationRow;
import com.example.starter.water.WaterRepository.HourUsageRow;
import com.example.starter.water.WaterRepository.ReservationInput;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.WaterRepository.WriteoffRow;
import com.example.starter.water.dto.Dtos.AllocationBatchResponse;
import com.example.starter.water.dto.Dtos.AllocationReservationGroup;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.DrawdownCurveResponse;
import com.example.starter.water.dto.Dtos.DrawdownPointRequest;
import com.example.starter.water.dto.Dtos.DrawdownPointResponse;
import com.example.starter.water.dto.Dtos.DrawdownUsageResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.HourReservationResponse;
import com.example.starter.water.dto.Dtos.HourUsageResponse;
import com.example.starter.water.dto.Dtos.TransferBatchItem;
import com.example.starter.water.dto.Dtos.TransferBatchResponse;
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
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 灌区配水业务服务。
 *
 * <p>幂等：每个写命令携带 commandKey，事务内先占位插入命令行再执行业务并写回响应；
 * 同键同参重放返回首次结果，同键改参返回 409，失败回滚不占用命令键。</p>
 *
 * <p>并发：所有写操作在同一事务内先对窗口行 SELECT ... FOR UPDATE，按事务提交顺序裁决，
 * 逐小时预留聚合在窗口锁内计算，保证同一小时并发预留不超卖。</p>
 *
 * <p>下泄曲线：每个窗口的曲线为全量替换的版本序列，每次修改生成新版本并固化到批准记录；
 * 逐小时预留按申请覆盖小时均分（2 位小数，尾差落在最早的小时），批准后快照不可改写。</p>
 */
@Service
public class WaterService {

    static final String STATUS_REQUESTED = "REQUESTED";
    static final String STATUS_APPROVED = "APPROVED";
    static final String STATUS_CANCELLED = "CANCELLED";

    static final String OP_WINDOW_CREATE = "WINDOW_CREATE";
    static final String OP_ALLOCATION_SUBMIT = "ALLOCATION_SUBMIT";
    static final String OP_ALLOCATION_APPROVE = "ALLOCATION_APPROVE";
    static final String OP_ALLOCATION_APPROVE_BATCH = "ALLOCATION_APPROVE_BATCH";
    static final String OP_ALLOCATION_CANCEL = "ALLOCATION_CANCEL";
    static final String OP_CURTAILMENT_CREATE = "CURTAILMENT_CREATE";
    static final String OP_CURTAILMENT_CANCEL = "CURTAILMENT_CANCEL";
    static final String OP_TRANSFER = "TRANSFER";
    static final String OP_TRANSFER_BATCH = "TRANSFER_BATCH";
    static final String OP_DRAWDOWN_PUT = "DRAWDOWN_PUT";
    static final String OP_WRITEOFF = "WRITEOFF";

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long NANOS_PER_HOUR = 3_600L * NANOS_PER_SECOND;
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("\\d{1,16}(\\.\\d{1,3})?");
    private static final Pattern CAP_PATTERN = Pattern.compile("\\d{1,16}(\\.\\d{1,2})?");
    private static final Pattern KEY_PATTERN = Pattern.compile("[\\w.\\-:]{1,128}");

    private final WaterRepository repository;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    /** 可控时钟：默认系统 UTC 时钟，测试可替换为固定/推进时钟。 */
    private Supplier<Instant> clock = Instant::now;

    public WaterService(WaterRepository repository, PlatformTransactionManager transactionManager,
                        ObjectMapper objectMapper) {
        this.repository = repository;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /** 替换当前时钟（测试用，传 null 恢复系统时钟）。 */
    void setClock(Supplier<Instant> clock) {
        this.clock = clock == null ? Instant::now : clock;
    }

    // ------------------------------------------------------------------
    // 窗口与申请
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
        String params = OP_WINDOW_CREATE + "|" + windowKey + "|" + channelId + "|" + startNanos + "|" + endNanos
                + "|" + planned.toPlainString();
        return runCommand(OP_WINDOW_CREATE, commandKey, params, WindowResponse.class, () -> {
            if (repository.existsOverlappingWindow(channelId, startNanos, endNanos)) {
                throw ApiException.conflict("WINDOW_OVERLAP", "同一渠道存在时间重叠的供水窗口");
            }
            long id = repository.insertWindow(windowKey, channelId, startNanos, endNanos, planned, nowNanos());
            WindowRow row = repository.findWindowById(id);
            return toWindowResponse(row, null);
        });
    }

    /** 提交配水申请，申请人为 actor；曲线窗口必须提供整点使用区间，无曲线窗口必须留空。 */
    public AllocationResponse submitAllocation(String commandKey, String allocationKey, Long windowId,
                                               String userId, String amount, String actor) {
        return submitAllocation(commandKey, allocationKey, windowId, userId, amount, null, null, actor);
    }

    /** 提交配水申请（带逐小时下泄区间），申请人为 actor。 */
    public AllocationResponse submitAllocation(String commandKey, String allocationKey, Long windowId,
                                               String userId, String amount,
                                               String usageStartUtc, String usageEndUtc, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        requireKey("userId", userId);
        requireKey("X-Actor-Id", actor);
        if (windowId == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "windowId 不能为空");
        }
        BigDecimal qty = parseAmount("amount", amount);
        String params = OP_ALLOCATION_SUBMIT + "|" + allocationKey + "|" + windowId + "|" + userId + "|"
                + qty.toPlainString() + "|" + actor + "|" + (usageStartUtc == null ? "" : usageStartUtc.trim())
                + "|" + (usageEndUtc == null ? "" : usageEndUtc.trim());
        return runCommand(OP_ALLOCATION_SUBMIT, commandKey, params, AllocationResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            DrawdownCurveRow curve = repository.findActiveCurve(windowId);
            Long usageStart = null;
            Long usageEnd = null;
            if (curve != null) {
                if (usageStartUtc == null || usageEndUtc == null) {
                    throw ApiException.badRequest("USAGE_REQUIRED",
                            "窗口已配置下泄曲线，usageStartUtc/usageEndUtc 必填");
                }
                usageStart = parseHourStart("usageStartUtc", usageStartUtc);
                usageEnd = parseHourStart("usageEndUtc", usageEndUtc);
                validateHourRange(window, usageStart, usageEnd);
                if (stripScale(qty) > 2) {
                    throw ApiException.badRequest("INVALID_ARGUMENT",
                            "曲线窗口申请水量最多 2 位小数: " + amount);
                }
            } else if (usageStartUtc != null || usageEndUtc != null) {
                throw ApiException.badRequest("USAGE_WITHOUT_CURVE",
                        "窗口未配置下泄曲线，不能指定 usageStartUtc/usageEndUtc");
            }
            long id = repository.insertAllocation(allocationKey, windowId, userId, qty, actor, nowNanos(),
                    usageStart, usageEnd);
            return toAllocationResponse(repository.findAllocationByKey(allocationKey));
        });
    }

    /** 批准申请：总量校验之外，曲线窗口还须逐小时预留不超上限，否则 422 并列出全部超限小时。 */
    public AllocationResponse approveAllocation(String commandKey, String allocationKey) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        String params = OP_ALLOCATION_APPROVE + "|" + allocationKey;
        return runCommand(OP_ALLOCATION_APPROVE, commandKey, params, AllocationResponse.class, () -> {
            AllocationRow allocation = repository.findAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            WindowRow window = lockWindowOf(allocation);
            allocation = repository.lockAllocationByKey(allocationKey);
            rejectUnapprovable(allocation);
            checkTotalQuota(window, Map.of(allocation.id(), allocation.amount()),
                    "批准后将超过当前可用总量");
            long now = nowNanos();
            DrawdownCurveRow curve = repository.lockActiveCurve(window.id());
            if (allocation.usageStartNanos() == null && curve != null) {
                throw ApiException.conflict("ALLOCATION_USAGE_REQUIRED",
                        "窗口已配置下泄曲线，缺少下泄区间的申请不能批准");
            }
            if (allocation.usageStartNanos() != null) {
                if (curve == null) {
                    throw ApiException.conflict("CURVE_NOT_CONFIGURED", "窗口当前缺少生效下泄曲线，无法逐小时预留");
                }
                Map<Long, BigDecimal> caps = capsOf(curve.id());
                Map<Long, BigDecimal> additions = spreadAdditions(allocation);
                List<Map<String, Object>> violations = hourlyViolations(window.id(), caps, additions);
                if (!violations.isEmpty()) {
                    throw ApiException.unprocessable("HOURLY_CAP_EXCEEDED",
                            "按申请覆盖小时累计预留后存在超过下泄上限的小时", violations);
                }
                repository.approveAllocation(allocation.id(), curve.id(), now);
                repository.insertReservations(allocation.id(), window.id(), curve.id(),
                        toReservationInputs(additions), now);
            } else {
                repository.updateAllocationStatus(allocation.id(), STATUS_APPROVED, now);
            }
            return toAllocationResponse(repository.findAllocationByKey(allocationKey));
        });
    }

    /** 批量批准：基于完整最终额度与逐小时预留一次校验，任一失败整批回滚。 */
    public AllocationBatchResponse approveAllocations(String commandKey, List<String> allocationKeys) {
        requireKey("commandKey", commandKey);
        if (allocationKeys == null || allocationKeys.isEmpty()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "allocationKeys 不能为空");
        }
        allocationKeys.forEach(k -> requireKey("allocationKey", k));
        String params = OP_ALLOCATION_APPROVE_BATCH + "|" + String.join(",", allocationKeys);
        return runCommand(OP_ALLOCATION_APPROVE_BATCH, commandKey, params, AllocationBatchResponse.class, () -> {
            // 先取窗口（全部申请必须同窗口），再锁窗口行串行裁决
            Long windowId = null;
            List<AllocationRow> found = new ArrayList<>();
            for (int i = 0; i < allocationKeys.size(); i++) {
                AllocationRow row = repository.findAllocationByKey(allocationKeys.get(i));
                if (row == null) {
                    throw ApiException.notFound("ALLOCATION_NOT_FOUND",
                            "配水申请不存在: " + allocationKeys.get(i),
                            batchIndex(i, allocationKeys.get(i)));
                }
                if (windowId == null) {
                    windowId = row.windowId();
                } else if (windowId != row.windowId()) {
                    throw ApiException.conflict("DIFFERENT_WINDOW",
                            "批量批准的申请必须属于同一供水窗口",
                            batchIndex(i, allocationKeys.get(i)));
                }
                found.add(row);
            }
            WindowRow window = repository.lockWindowById(windowId);
            // 按主键升序加锁申请行，避免同窗口并发批量互锁
            List<AllocationRow> locked = new ArrayList<>();
            found.stream().map(AllocationRow::id).sorted()
                    .forEach(id -> locked.add(repository.lockAllocationById(id)));
            Map<Long, AllocationRow> byId = new LinkedHashMap<>();
            locked.forEach(r -> byId.put(r.id(), r));
            Map<Long, BigDecimal> additionAmounts = new LinkedHashMap<>();
            boolean anyHourly = false;
            for (int i = 0; i < found.size(); i++) {
                AllocationRow current = byId.get(found.get(i).id());
                rejectUnapprovable(current, i);
                additionAmounts.put(current.id(), current.amount());
                anyHourly |= current.usageStartNanos() != null;
            }
            checkTotalQuota(window, additionAmounts, "批量批准后将超过当前可用总量");
            long now = nowNanos();
            DrawdownCurveRow curve = repository.lockActiveCurve(window.id());
            if (curve != null) {
                for (AllocationRow current : locked) {
                    if (current.usageStartNanos() == null) {
                        throw ApiException.conflict("ALLOCATION_USAGE_REQUIRED",
                                "窗口已配置下泄曲线，缺少下泄区间的申请不能批准");
                    }
                }
            }
            if (anyHourly) {
                if (curve == null) {
                    throw ApiException.conflict("CURVE_NOT_CONFIGURED", "窗口当前缺少生效下泄曲线，无法逐小时预留");
                }
                // 完整最终状态：整批全部申请的逐小时新增之和一次性比较
                Map<Long, BigDecimal> additions = new TreeMap<>();
                for (AllocationRow row : locked) {
                    if (row.usageStartNanos() != null) {
                        merge(additions, spreadAdditions(row));
                    }
                }
                List<Map<String, Object>> violations = hourlyViolations(window.id(), capsOf(curve.id()), additions);
                if (!violations.isEmpty()) {
                    throw ApiException.unprocessable("HOURLY_CAP_EXCEEDED",
                            "批量批准按完整最终额度累计预留后存在超过下泄上限的小时", violations);
                }
            }
            for (AllocationRow row : found) {
                AllocationRow current = byId.get(row.id());
                if (current.usageStartNanos() != null) {
                    repository.approveAllocation(current.id(), curve.id(), now);
                    repository.insertReservations(current.id(), window.id(), curve.id(),
                            toReservationInputs(spreadAdditions(current)), now);
                } else {
                    repository.updateAllocationStatus(current.id(), STATUS_APPROVED, now);
                }
            }
            List<AllocationResponse> responses = new ArrayList<>();
            for (String key : allocationKeys) {
                responses.add(toAllocationResponse(repository.findAllocationByKey(key)));
            }
            return new AllocationBatchResponse(responses);
        });
    }

    /** 取消申请：仅申请人本人；取消未核销预留时释放对应小时额度，取消不可恢复。 */
    public AllocationResponse cancelAllocation(String commandKey, String allocationKey, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        requireKey("X-Actor-Id", actor);
        String params = OP_ALLOCATION_CANCEL + "|" + allocationKey + "|" + actor;
        return runCommand(OP_ALLOCATION_CANCEL, commandKey, params, AllocationResponse.class, () -> {
            AllocationRow allocation = repository.findAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            lockWindowOf(allocation);
            // 窗口锁内重读并锁定申请，避免与转让/核销/并发取消使用过期状态
            allocation = repository.lockAllocationByKey(allocationKey);
            if (!allocation.requester().equals(actor)) {
                throw ApiException.conflict("NOT_OWNER", "只有申请人本人可以取消该申请");
            }
            if (STATUS_CANCELLED.equals(allocation.status())) {
                throw ApiException.conflict("ALLOCATION_ALREADY_CANCELLED", "申请已取消，不能重复取消");
            }
            long now = nowNanos();
            // 仅 APPROVED 存在逐小时预留；释放额 = 每小时预留 - 已核销（已核销部分不释放）
            if (STATUS_APPROVED.equals(allocation.status()) && allocation.usageStartNanos() != null) {
                repository.releaseRemainingReservations(allocation.id(), now);
            }
            repository.updateAllocationStatus(allocation.id(), STATUS_CANCELLED, now);
            return toAllocationResponse(repository.findAllocationByKey(allocationKey));
        });
    }

    // ------------------------------------------------------------------
    // 转让
    // ------------------------------------------------------------------

    /**
     * 同窗口额度原子转让：源申请人（actor）把自己 APPROVED 申请的额度转给同窗口、不同用水户的
     * REQUESTED 目标申请，转让额等于目标原申请水量的全部额度。
     * 曲线窗口还须为目标申请按完整最终逐小时预留校验；源扣减、目标批准、不可变流水同事务完成。
     */
    public TransferResponse transferAllocation(String commandKey, String transferKey, String sourceAllocationKey,
                                               String targetAllocationKey, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("transferKey", transferKey);
        requireKey("sourceAllocationKey", sourceAllocationKey);
        requireKey("targetAllocationKey", targetAllocationKey);
        requireKey("X-Actor-Id", actor);
        String params = OP_TRANSFER + "|" + transferKey + "|" + sourceAllocationKey + "|" + targetAllocationKey
                + "|" + actor;
        return runCommand(OP_TRANSFER, commandKey, params, TransferResponse.class, () -> {
            if (repository.findTransferByKey(transferKey) != null) {
                throw ApiException.conflict("TRANSFER_KEY_REUSED", "transferKey 已被使用: " + transferKey);
            }
            AllocationRow source = requireAllocation(sourceAllocationKey, "SOURCE_ALLOCATION_NOT_FOUND", "转出");
            AllocationRow target = requireAllocation(targetAllocationKey, "TARGET_ALLOCATION_NOT_FOUND", "转入");
            // 先锁窗口行，与普通批准、取消、核销、限供、曲线调整按事务提交顺序串行裁决
            lockWindowOf(source);
            AllocationRow lockedSource = repository.lockAllocationByKey(sourceAllocationKey);
            AllocationRow lockedTarget = repository.lockAllocationByKey(targetAllocationKey);
            validateTransferPair(transferKey, lockedSource, lockedTarget, actor, 0);
            long now = nowNanos();
            DrawdownCurveRow curve = repository.lockActiveCurve(lockedSource.windowId());
            if (lockedTarget.usageStartNanos() == null && curve != null) {
                throw ApiException.conflict("ALLOCATION_USAGE_REQUIRED",
                        "窗口已配置下泄曲线，缺少下泄区间的申请不能通过转让批准");
            }
            if (lockedTarget.usageStartNanos() != null) {
                if (curve == null) {
                    throw ApiException.conflict("CURVE_NOT_CONFIGURED", "窗口当前缺少生效下泄曲线，无法逐小时预留");
                }
                Map<Long, BigDecimal> additions = spreadAdditions(lockedTarget);
                List<Map<String, Object>> violations =
                        hourlyViolations(lockedSource.windowId(), capsOf(curve.id()), additions);
                if (!violations.isEmpty()) {
                    throw ApiException.unprocessable("HOURLY_CAP_EXCEEDED",
                            "转让按目标申请覆盖小时累计预留后存在超过下泄上限的小时", violations);
                }
            }
            applyTransfer(transferKey, lockedSource, lockedTarget, curve, now);
            return toTransferResponse(repository.findTransferByKey(transferKey));
        });
    }

    /**
     * 批量转让：多笔转让基于完整最终额度与逐小时预留一次校验提交，任一失败整批回滚、不留半成品。
     * 全部转让必须属于同一窗口；同一源可连续转出，按列表顺序累计扣减持有额度。
     */
    public TransferBatchResponse transferAllocations(String commandKey, List<TransferBatchItem> transfers,
                                                     String actor) {
        requireKey("commandKey", commandKey);
        requireKey("X-Actor-Id", actor);
        if (transfers == null || transfers.isEmpty()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "transfers 不能为空");
        }
        List<String> paramParts = new ArrayList<>();
        for (TransferBatchItem item : transfers) {
            if (item == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "transfers 存在空元素");
            }
            requireKey("transferKey", item.transferKey());
            requireKey("sourceAllocationKey", item.sourceAllocationKey());
            requireKey("targetAllocationKey", item.targetAllocationKey());
            paramParts.add(item.transferKey() + ">" + item.sourceAllocationKey() + ">" + item.targetAllocationKey());
        }
        String params = OP_TRANSFER_BATCH + "|" + actor + "|" + String.join(";", paramParts);
        return runCommand(OP_TRANSFER_BATCH, commandKey, params, TransferBatchResponse.class, () -> {
            // 批内 transferKey 不得重复，也不得与历史流水冲突
            NavigableSet<String> batchKeys = new TreeSet<>();
            for (int i = 0; i < transfers.size(); i++) {
                TransferBatchItem item = transfers.get(i);
                if (!batchKeys.add(item.transferKey())) {
                    throw ApiException.badRequest("TRANSFER_KEY_DUPLICATED_IN_BATCH",
                            "批量请求中 transferKey 重复: " + item.transferKey(),
                            batchIndex(i, item.transferKey()));
                }
                if (repository.findTransferByKey(item.transferKey()) != null) {
                    throw ApiException.conflict("TRANSFER_KEY_REUSED",
                            "transferKey 已被使用: " + item.transferKey(), batchIndex(i, item.transferKey()));
                }
            }
            Long windowId = null;
            Map<String, AllocationRow> prefetched = new HashMap<>();
            for (int i = 0; i < transfers.size(); i++) {
                TransferBatchItem item = transfers.get(i);
                AllocationRow source = requireAllocation(item.sourceAllocationKey(),
                        "SOURCE_ALLOCATION_NOT_FOUND", "转出", i);
                AllocationRow target = requireAllocation(item.targetAllocationKey(),
                        "TARGET_ALLOCATION_NOT_FOUND", "转入", i);
                prefetched.put(item.sourceAllocationKey(), source);
                prefetched.put(item.targetAllocationKey(), target);
                if (windowId == null) {
                    windowId = source.windowId();
                } else if (windowId != source.windowId() || windowId != target.windowId()) {
                    throw ApiException.conflict("DIFFERENT_WINDOW",
                            "批量转让的申请必须属于同一供水窗口", batchIndex(i, item.transferKey()));
                }
            }
            repository.lockWindowById(windowId);
            // 按申请主键升序一次性锁定本批涉及的全部申请行
            NavigableSet<Long> allocationIds = new TreeSet<>();
            transfers.forEach(item -> {
                allocationIds.add(prefetched.get(item.sourceAllocationKey()).id());
                allocationIds.add(prefetched.get(item.targetAllocationKey()).id());
            });
            Map<Long, AllocationRow> locked = new LinkedHashMap<>();
            allocationIds.forEach(id -> locked.put(id, repository.lockAllocationById(id)));

            // 虚拟应用整批：累计源扣减、目标状态，基于完整最终状态裁决
            Map<Long, BigDecimal> remainingHeld = new HashMap<>();
            NavigableSet<Long> approvedVirtually = new TreeSet<>();
            Map<Long, BigDecimal> hourlyAdditions = new TreeMap<>();
            boolean anyHourly = false;
            for (int i = 0; i < transfers.size(); i++) {
                TransferBatchItem item = transfers.get(i);
                AllocationRow source = locked.get(prefetched.get(item.sourceAllocationKey()).id());
                AllocationRow target = locked.get(prefetched.get(item.targetAllocationKey()).id());
                validateTransferPair(item.transferKey(), source, target, actor, i);
                BigDecimal held = remainingHeld.computeIfAbsent(source.id(),
                        k -> source.heldAmount());
                if (held.compareTo(target.amount()) < 0) {
                    throw ApiException.unprocessable("QUOTA_EXCEEDED",
                            "源申请批量转让后持有额度不足：实际 " + fmt(held) + "，要求 " + fmt(target.amount())
                                    + "，差额 " + fmt(target.amount().subtract(held)),
                            batchIndex(i, item.transferKey()));
                }
                remainingHeld.put(source.id(), held.subtract(target.amount()));
                if (!approvedVirtually.add(target.id())) {
                    throw ApiException.conflict("TARGET_NOT_REQUESTED",
                            "目标申请在本批中已被另一笔转让批准", batchIndex(i, item.transferKey()));
                }
                if (target.usageStartNanos() != null) {
                    anyHourly = true;
                    merge(hourlyAdditions, spreadAdditions(target));
                }
            }
            DrawdownCurveRow curve = repository.lockActiveCurve(windowId);
            if (curve != null) {
                for (int i = 0; i < transfers.size(); i++) {
                    TransferBatchItem item = transfers.get(i);
                    AllocationRow target = locked.get(prefetched.get(item.targetAllocationKey()).id());
                    if (target.usageStartNanos() == null) {
                        throw ApiException.conflict("ALLOCATION_USAGE_REQUIRED",
                                "窗口已配置下泄曲线，缺少下泄区间的申请不能通过转让批准",
                                batchIndex(i, item.transferKey()));
                    }
                }
            }
            if (anyHourly) {
                if (curve == null) {
                    throw ApiException.conflict("CURVE_NOT_CONFIGURED", "窗口当前缺少生效下泄曲线，无法逐小时预留");
                }
                List<Map<String, Object>> violations =
                        hourlyViolations(windowId, capsOf(curve.id()), hourlyAdditions);
                if (!violations.isEmpty()) {
                    throw ApiException.unprocessable("HOURLY_CAP_EXCEEDED",
                            "批量转让按完整最终逐小时预留累计后存在超过下泄上限的小时", violations);
                }
            }
            long now = nowNanos();
            List<TransferResponse> responses = new ArrayList<>();
            for (TransferBatchItem item : transfers) {
                AllocationRow source = locked.get(prefetched.get(item.sourceAllocationKey()).id());
                AllocationRow target = locked.get(prefetched.get(item.targetAllocationKey()).id());
                applyTransfer(item.transferKey(), source, target, curve, now);
                responses.add(toTransferResponse(repository.findTransferByKey(item.transferKey())));
            }
            return new TransferBatchResponse(responses);
        });
    }

    // ------------------------------------------------------------------
    // 限供
    // ------------------------------------------------------------------

    /** 创建限供：仅当当前已批准总量不超过拟定限供水量时允许。 */
    public CurtailmentResponse createCurtailment(String commandKey, long windowId, String volume) {
        requireKey("commandKey", commandKey);
        BigDecimal qty = parseAmount("volume", volume);
        String params = OP_CURTAILMENT_CREATE + "|" + windowId + "|" + qty.toPlainString();
        return runCommand(OP_CURTAILMENT_CREATE, commandKey, params, CurtailmentResponse.class, () -> {
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
            return toCurtailmentResponse(repository.listCurtailments(windowId).stream()
                    .filter(c -> c.id() == id).findFirst().orElseThrow());
        });
    }

    /** 取消当前生效限供，恢复计划水量。 */
    public CurtailmentResponse cancelCurtailment(String commandKey, long windowId) {
        requireKey("commandKey", commandKey);
        String params = OP_CURTAILMENT_CANCEL + "|" + windowId;
        return runCommand(OP_CURTAILMENT_CANCEL, commandKey, params, CurtailmentResponse.class, () -> {
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

    // ------------------------------------------------------------------
    // 下泄曲线与核销
    // ------------------------------------------------------------------

    /**
     * 全量替换窗口下泄曲线：点序列必须恰好覆盖窗口全部 UTC 小时，非负、最多 2 位小数、
     * 不得重复/缺失/跨窗口。已存在曲线时，已开始小时（now &gt;= hourStart）的上限不得修改，否则 409。
     * 每次替换生成新版本，旧版本置 SUPERSEDED 且永不删除/改写。
     */
    public DrawdownCurveResponse putDrawdownCurve(String commandKey, long windowId,
                                                  List<DrawdownPointRequest> points) {
        requireKey("commandKey", commandKey);
        if (points == null || points.isEmpty()) {
            throw ApiException.badRequest("CURVE_POINTS_EMPTY", "曲线点序列不能为空");
        }
        // 规范化：逐点校验、按小时排序去重；参数串使用规范化形式以支持同参重放
        NavigableSet<Long> seenHours = new TreeSet<>();
        List<DrawdownPointInput> inputs = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            DrawdownPointRequest point = points.get(i);
            if (point == null || point.hourStartUtc() == null || point.capVolume() == null) {
                throw ApiException.badRequest("CURVE_POINT_INVALID",
                        "第 " + i + " 个曲线点缺少 hourStartUtc 或 capVolume", Map.of("index", i));
            }
            long hour = parseHourStart("hourStartUtc", point.hourStartUtc());
            BigDecimal cap = parseCapVolume(point.capVolume());
            if (!seenHours.add(hour)) {
                throw ApiException.badRequest("CURVE_DUPLICATE_HOUR",
                        "曲线存在重复小时: " + point.hourStartUtc().trim(),
                        Map.of("hourStartUtc", point.hourStartUtc().trim(), "index", i));
            }
            inputs.add(new DrawdownPointInput(hour, cap));
        }
        // 按小时规范化排序，保证同一点集乱序提交也视为同参
        inputs.sort(Comparator.comparingLong(DrawdownPointInput::hourStartNanos));
        String params = OP_DRAWDOWN_PUT + "|" + windowId + "|" + encodePoints(inputs);
        return runCommand(OP_DRAWDOWN_PUT, commandKey, params, DrawdownCurveResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            if (window.startNanos() % NANOS_PER_HOUR != 0 || window.endNanos() % NANOS_PER_HOUR != 0) {
                throw ApiException.badRequest("WINDOW_NOT_HOUR_ALIGNED",
                        "窗口起止时刻未对齐 UTC 整点，无法配置逐小时下泄曲线");
            }
            NavigableSet<Long> expected = hoursOf(window);
            NavigableSet<Long> actual = new TreeSet<>();
            inputs.forEach(p -> actual.add(p.hourStartNanos()));
            if (!actual.equals(expected)) {
                NavigableSet<Long> missing = new TreeSet<>(expected);
                missing.removeAll(actual);
                NavigableSet<Long> extra = new TreeSet<>(actual);
                extra.removeAll(expected);
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("requiredHourCount", expected.size());
                detail.put("actualHourCount", actual.size());
                detail.put("missingHours", missing.stream().map(WaterService::toIso).toList());
                detail.put("unexpectedHours", extra.stream().map(WaterService::toIso).toList());
                throw ApiException.badRequest("CURVE_HOUR_COVERAGE",
                        "曲线必须恰好覆盖窗口全部小时，不得缺失或跨窗口", detail);
            }
            long now = nowNanos();
            DrawdownCurveRow previous = repository.lockActiveCurve(windowId);
            if (previous != null) {
                Map<Long, BigDecimal> oldCaps = capsOf(previous.id());
                // 当前逐小时已提交占用 = 固化预留 - 取消释放（含已核销，已实际下泄不可压缩）
                Map<Long, BigDecimal> committedByHour = new HashMap<>();
                repository.aggregateHourUsage(windowId).forEach(u -> committedByHour.put(
                        u.hourStartNanos(), u.reservedTotal().subtract(u.releasedTotal())));
                List<String> startedChanged = new ArrayList<>();
                List<Map<String, Object>> belowCommitted = new ArrayList<>();
                for (DrawdownPointInput input : inputs) {
                    BigDecimal oldCap = oldCaps.get(input.hourStartNanos());
                    BigDecimal committed = committedByHour.getOrDefault(input.hourStartNanos(), BigDecimal.ZERO);
                    if (oldCap != null && oldCap.compareTo(input.capVolume()) != 0
                            && now >= input.hourStartNanos()) {
                        startedChanged.add(toIso(input.hourStartNanos()));
                    }
                    if (input.capVolume().compareTo(committed) < 0) {
                        Map<String, Object> detail = new LinkedHashMap<>();
                        detail.put("hourStartUtc", toIso(input.hourStartNanos()));
                        detail.put("committedVolume", fmt(committed));
                        detail.put("requestedCapVolume", fmt(input.capVolume()));
                        detail.put("deficitVolume", fmt(committed.subtract(input.capVolume())));
                        belowCommitted.add(detail);
                    }
                }
                if (!startedChanged.isEmpty()) {
                    throw ApiException.conflict("CURVE_HOUR_ALREADY_STARTED",
                            "已开始小时的下泄上限不能修改，实际受影响小时 " + startedChanged.size()
                                    + " 个；要求仅修改尚未开始的小时",
                            Map.of("nowUtc", toIso(now), "changedStartedHours", startedChanged));
                }
                if (!belowCommitted.isEmpty()) {
                    throw ApiException.conflict("CURVE_BELOW_COMMITTED",
                            "未来小时的新上限不能低于该小时当前已提交占用（含已核销），受影响小时 "
                                    + belowCommitted.size() + " 个",
                            Map.of("hours", belowCommitted));
                }
                repository.supersedeActiveCurves(windowId);
            }
            int version = repository.nextCurveVersion(windowId);
            long curveId = repository.insertCurve(windowId, version, now);
            repository.insertPoints(curveId, windowId, inputs);
            return toCurveResponse(repository.findCurveById(curveId), repository.listPoints(curveId));
        });
    }

    /** 查询窗口当前生效曲线版本及其全部小时点（只读）。 */
    public DrawdownCurveResponse getActiveCurve(long windowId) {
        WindowRow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        DrawdownCurveRow curve = repository.findActiveCurve(windowId);
        if (curve == null) {
            throw ApiException.notFound("DRAWDOWN_CURVE_NOT_FOUND", "窗口尚未配置下泄曲线: " + windowId);
        }
        return toCurveResponse(curve, repository.listPoints(curve.id()));
    }

    /**
     * 实际核销：按发生小时扣减同一申请同一小时的剩余预留，不能挪用其他小时余量。
     * 剩余 = 固化预留 - 取消释放 - 已核销；超过剩余返回 422 并给出实际/要求/差额。
     */
    public WriteoffResponse writeoff(String commandKey, String writeoffKey, String allocationKey,
                                     String hourStartUtc, String volume, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("writeoffKey", writeoffKey);
        requireKey("allocationKey", allocationKey);
        requireKey("X-Actor-Id", actor);
        long hour = parseHourStart("hourStartUtc", hourStartUtc);
        BigDecimal qty = parseWriteoffVolume("volume", volume);
        String params = OP_WRITEOFF + "|" + writeoffKey + "|" + allocationKey + "|" + hour + "|"
                + qty.toPlainString() + "|" + actor;
        return runCommand(OP_WRITEOFF, commandKey, params, WriteoffResponse.class, () -> {
            if (repository.findWriteoffByKey(writeoffKey) != null) {
                throw ApiException.conflict("WRITEOFF_KEY_REUSED", "writeoffKey 已被使用: " + writeoffKey);
            }
            AllocationRow allocation = repository.findAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            lockWindowOf(allocation);
            allocation = repository.lockAllocationByKey(allocationKey);
            if (!STATUS_APPROVED.equals(allocation.status())) {
                throw ApiException.conflict("ALLOCATION_NOT_APPROVED",
                        STATUS_CANCELLED.equals(allocation.status())
                                ? "已取消的申请不能核销" : "申请尚未批准，不能核销");
            }
            if (allocation.usageStartNanos() == null
                    || hour < allocation.usageStartNanos() || hour >= allocation.usageEndNanos()) {
                throw ApiException.unprocessable("HOURLY_RESERVATION_NOT_FOUND",
                        "该申请在指定小时没有固化预留，不能核销: " + hourStartUtc.trim(),
                        Map.of("allocationKey", allocationKey, "hourStartUtc", toIso(hour)));
            }
            HourReservationRow reservation = repository.lockReservation(allocation.id(), hour);
            if (reservation == null) {
                throw ApiException.unprocessable("HOURLY_RESERVATION_NOT_FOUND",
                        "该申请在指定小时没有固化预留，不能核销: " + hourStartUtc.trim(),
                        Map.of("allocationKey", allocationKey, "hourStartUtc", toIso(hour)));
            }
            BigDecimal remaining = reservation.reservedVolume()
                    .subtract(reservation.releasedVolume())
                    .subtract(reservation.writtenOffVolume());
            if (qty.compareTo(remaining) > 0) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("allocationKey", allocationKey);
                detail.put("hourStartUtc", toIso(hour));
                detail.put("reservedVolume", fmt(reservation.reservedVolume()));
                detail.put("releasedVolume", fmt(reservation.releasedVolume()));
                detail.put("writtenOffVolume", fmt(reservation.writtenOffVolume()));
                detail.put("remainingVolume", fmt(remaining));
                detail.put("requestedVolume", fmt(qty));
                detail.put("deficitVolume", fmt(qty.subtract(remaining)));
                throw ApiException.unprocessable("WRITEOFF_EXCEEDS_RESERVATION",
                        "核销超过该小时剩余预留：实际剩余 " + fmt(remaining) + "，要求核销 " + fmt(qty)
                                + "，差额 " + fmt(qty.subtract(remaining)),
                        detail);
            }
            long now = nowNanos();
            try {
                repository.insertWriteoff(writeoffKey, allocation.id(), allocationKey, allocation.windowId(),
                        hour, qty, actor, now);
            } catch (DuplicateKeyException e) {
                // 并发复用同一 writeoffKey（换 commandKey）：事务回滚，计数无变化
                throw ApiException.conflict("WRITEOFF_KEY_REUSED", "writeoffKey 已被使用: " + writeoffKey);
            }
            repository.addWrittenOff(reservation.id(), qty, now);
            return toWriteoffResponse(repository.findWriteoffByKey(writeoffKey));
        });
    }

    // ------------------------------------------------------------------
    // 只读查询
    // ------------------------------------------------------------------

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

    /** 查询窗口逐小时下泄诊断（只读）：当前曲线上限、净预留、释放、核销与剩余。 */
    public DrawdownUsageResponse getDrawdownUsage(long windowId) {
        WindowRow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        DrawdownCurveRow curve = repository.findActiveCurve(windowId);
        if (curve == null) {
            throw ApiException.notFound("DRAWDOWN_CURVE_NOT_FOUND", "窗口尚未配置下泄曲线: " + windowId);
        }
        Map<Long, BigDecimal> caps = capsOf(curve.id());
        Map<Long, HourUsageRow> usage = new HashMap<>();
        repository.aggregateHourUsage(windowId).forEach(row -> usage.put(row.hourStartNanos(), row));
        List<HourUsageResponse> hours = new ArrayList<>();
        for (Long hour : new TreeSet<>(caps.keySet())) {
            HourUsageRow row = usage.get(hour);
            BigDecimal reserved = row == null ? BigDecimal.ZERO : row.reservedTotal();
            BigDecimal released = row == null ? BigDecimal.ZERO : row.releasedTotal();
            BigDecimal writtenOff = row == null ? BigDecimal.ZERO : row.writtenOffTotal();
            // 实际占用 = 固化预留 - 取消释放；已核销水量已下泄，仍计入占用
            BigDecimal committed = reserved.subtract(released);
            hours.add(new HourUsageResponse(toIso(hour), fmt(caps.get(hour)), fmt(reserved), fmt(released),
                    fmt(writtenOff), fmt(committed), fmt(caps.get(hour).subtract(committed))));
        }
        return new DrawdownUsageResponse(windowId, curve.version(), hours);
    }

    /**
     * 查询窗口历史明细：窗口 + 全部申请 + 全部限供 + 全部曲线版本（含历史）+
     * 批准时固化的逐小时预留快照及计数 + 全部核销流水。
     */
    public HistoryResponse getHistory(long windowId) {
        WindowRow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        CurtailmentRow active = repository.findActiveCurtailment(windowId);
        Map<Long, Integer> curveVersions = new HashMap<>();
        List<DrawdownCurveResponse> curves = new ArrayList<>();
        for (DrawdownCurveRow curve : repository.listCurves(windowId)) {
            curveVersions.put(curve.id(), curve.version());
            curves.add(toCurveResponse(curve, repository.listPoints(curve.id())));
        }
        Map<Long, String> allocationKeys = new HashMap<>();
        List<AllocationRow> allocationRows = repository.listAllocations(windowId);
        allocationRows.forEach(row -> allocationKeys.put(row.id(), row.allocationKey()));
        List<AllocationResponse> allocations = allocationRows.stream()
                .map(row -> toAllocationResponse(row, curveVersions)).toList();
        List<CurtailmentResponse> curtailments = repository.listCurtailments(windowId).stream()
                .map(this::toCurtailmentResponse).toList();
        Map<Long, List<HourReservationRow>> reservationGroups = new TreeMap<>();
        for (HourReservationRow row : repository.listReservationsByWindow(windowId)) {
            reservationGroups.computeIfAbsent(row.allocationId(), k -> new ArrayList<>()).add(row);
        }
        List<AllocationReservationGroup> groups = new ArrayList<>();
        for (Map.Entry<Long, List<HourReservationRow>> entry : reservationGroups.entrySet()) {
            List<HourReservationResponse> items = entry.getValue().stream()
                    .sorted(Comparator.comparingLong(HourReservationRow::hourStartNanos))
                    .map(row -> new HourReservationResponse(toIso(row.hourStartNanos()),
                            fmt(row.reservedVolume()), fmt(row.releasedVolume()), fmt(row.writtenOffVolume()),
                            curveVersions.getOrDefault(row.curveId(), -1)))
                    .toList();
            AllocationRow owner = allocationRows.stream()
                    .filter(a -> a.id() == entry.getKey()).findFirst().orElse(null);
            groups.add(new AllocationReservationGroup(allocationKeys.get(entry.getKey()),
                    owner == null ? null : curveVersions.get(owner.approvalCurveId()), items));
        }
        List<WriteoffResponse> writeoffs = repository.listWriteoffs(windowId).stream()
                .map(this::toWriteoffResponse).toList();
        return new HistoryResponse(toWindowResponse(window, active), allocations, curtailments,
                curves, groups, writeoffs);
    }

    // ------------------------------------------------------------------
    // 幂等命令框架
    // ------------------------------------------------------------------

    /**
     * 在单事务内执行幂等命令：先占位插入命令行，再执行业务并写回响应。
     * 并发同键时占位插入会因唯一约束失败，随后读取已提交的首次结果重放或报 409。
     * 业务失败抛异常导致整事务回滚，命令行不会落库（失败不占键）。
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
    // 批准/转让内部校验与落库
    // ------------------------------------------------------------------

    private AllocationRow requireAllocation(String key, String code, String label) {
        AllocationRow row = repository.findAllocationByKey(key);
        if (row == null) {
            throw ApiException.notFound(code, label + "申请不存在: " + key);
        }
        return row;
    }

    private AllocationRow requireAllocation(String key, String code, String label, int index) {
        AllocationRow row = repository.findAllocationByKey(key);
        if (row == null) {
            throw ApiException.notFound(code, label + "申请不存在: " + key, batchIndex(index, key));
        }
        return row;
    }

    private void rejectUnapprovable(AllocationRow allocation) {
        rejectUnapprovable(allocation, null);
    }

    private void rejectUnapprovable(AllocationRow allocation, Integer index) {
        switch (allocation.status()) {
            case STATUS_CANCELLED -> throw ApiException.conflict("ALLOCATION_CANCELLED",
                    "已取消的申请不能批准", index == null ? null : batchIndex(index, allocation.allocationKey()));
            case STATUS_APPROVED -> throw ApiException.conflict("ALLOCATION_ALREADY_APPROVED",
                    "申请已批准，不能重复批准",
                    index == null ? null : batchIndex(index, allocation.allocationKey()));
            default -> {
            }
        }
    }

    /** 总量校验：既有 APPROVED 持有额度之和 + 本次新增不得超过当前可用总量。 */
    private void checkTotalQuota(WindowRow window, Map<Long, BigDecimal> additionAmounts, String messagePrefix) {
        BigDecimal available = availableTotal(window);
        BigDecimal approved = repository.sumApprovedAmount(window.id());
        BigDecimal additions = additionAmounts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (approved.add(additions).compareTo(available) > 0) {
            BigDecimal deficit = approved.add(additions).subtract(available);
            throw ApiException.quotaExceeded(messagePrefix + "：实际已批准 " + fmt(approved)
                    + "，本次要求 " + fmt(additions) + "，要求可用总量 " + fmt(available)
                    + "，差额 " + fmt(deficit));
        }
    }

    private void validateTransferPair(String transferKey, AllocationRow lockedSource,
                                      AllocationRow lockedTarget, String actor, int index) {
        validateTransferPair(transferKey, lockedSource, lockedTarget, actor, Integer.valueOf(index));
    }

    private void validateTransferPair(String transferKey, AllocationRow lockedSource,
                                      AllocationRow lockedTarget, String actor, Integer index) {
        Object detail = index == null ? null : batchIndex(index, transferKey);
        if (!lockedSource.requester().equals(actor)) {
            throw ApiException.conflict("NOT_OWNER", "只有转出申请人本人可以发起转让", detail);
        }
        if (!STATUS_APPROVED.equals(lockedSource.status())) {
            throw ApiException.conflict("SOURCE_NOT_APPROVED", "转出申请必须为 APPROVED 状态", detail);
        }
        if (!STATUS_REQUESTED.equals(lockedTarget.status())) {
            throw ApiException.conflict("TARGET_NOT_REQUESTED", "转入申请必须为 REQUESTED 状态", detail);
        }
        if (lockedSource.windowId() != lockedTarget.windowId()) {
            throw ApiException.conflict("DIFFERENT_WINDOW", "转让双方必须属于同一供水窗口", detail);
        }
        if (lockedSource.userId().equals(lockedTarget.userId())) {
            throw ApiException.conflict("SAME_USER", "转让双方必须属于不同用水户", detail);
        }
        BigDecimal amount = lockedTarget.amount();
        if (lockedSource.heldAmount().compareTo(amount) < 0) {
            throw ApiException.unprocessable("QUOTA_EXCEEDED",
                    "源申请当前持有额度 " + fmt(lockedSource.heldAmount()) + " 不足，无法转让 " + fmt(amount)
                            + "，差额 " + fmt(amount.subtract(lockedSource.heldAmount())),
                    detail);
        }
    }

    /** 落库单笔转让：源扣减、目标批准（固化曲线版本与逐小时预留）、不可变流水同事务写入。 */
    private void applyTransfer(String transferKey, AllocationRow source, AllocationRow target,
                               DrawdownCurveRow curve, long now) {
        repository.decrementHeldAmount(source.id(), target.amount(), now);
        if (target.usageStartNanos() != null) {
            if (curve == null) {
                throw ApiException.conflict("CURVE_NOT_CONFIGURED", "窗口当前缺少生效下泄曲线，无法逐小时预留");
            }
            repository.approveAllocation(target.id(), curve.id(), now);
            repository.insertReservations(target.id(), target.windowId(), curve.id(),
                    toReservationInputs(spreadAdditions(target)), now);
        } else {
            repository.updateAllocationStatus(target.id(), STATUS_APPROVED, now);
        }
        try {
            repository.insertTransfer(transferKey, source.windowId(), source.allocationKey(),
                    target.allocationKey(), target.amount(), source.requester(), now);
        } catch (DuplicateKeyException e) {
            // 并发复用同一 transferKey（换 commandKey）：事务回滚，额度无变化
            throw ApiException.conflict("TRANSFER_KEY_REUSED", "transferKey 已被使用: " + transferKey);
        }
    }

    /**
     * 逐小时超限检查：返回全部超限小时明细。当前占用（已核销 + 已批准未核销 = 固化预留 - 取消释放）
     * 与本次新增之和超过该小时上限即超限；差额 = 当前占用 + 新增 - 上限。
     * 核销的水量已实际下泄，仍占用该小时容量，不从占用中扣减。
     */
    private List<Map<String, Object>> hourlyViolations(long windowId, Map<Long, BigDecimal> caps,
                                                       Map<Long, BigDecimal> additions) {
        Map<Long, HourUsageRow> usage = new HashMap<>();
        repository.aggregateHourUsage(windowId).forEach(row -> usage.put(row.hourStartNanos(), row));
        List<Map<String, Object>> violations = new ArrayList<>();
        for (Long hour : new TreeSet<>(additions.keySet())) {
            BigDecimal cap = caps.get(hour);
            if (cap == null) {
                // 申请区间已在提交时校验落在窗口内，此分支仅作防御
                throw ApiException.conflict("CURVE_HOUR_NOT_FOUND", "曲线缺少申请覆盖小时: " + toIso(hour));
            }
            HourUsageRow row = usage.get(hour);
            BigDecimal reserved = row == null ? BigDecimal.ZERO : row.reservedTotal();
            BigDecimal released = row == null ? BigDecimal.ZERO : row.releasedTotal();
            BigDecimal writtenOff = row == null ? BigDecimal.ZERO : row.writtenOffTotal();
            BigDecimal committed = reserved.subtract(released);
            BigDecimal requested = additions.get(hour);
            BigDecimal total = committed.add(requested);
            if (total.compareTo(cap) > 0) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("hourStartUtc", toIso(hour));
                detail.put("capVolume", fmt(cap));
                detail.put("committedVolume", fmt(committed));
                detail.put("writtenOffVolume", fmt(writtenOff));
                detail.put("requestedVolume", fmt(requested));
                detail.put("totalVolume", fmt(total));
                detail.put("deficitVolume", fmt(total.subtract(cap)));
                violations.add(detail);
            }
        }
        return violations;
    }

    // ------------------------------------------------------------------
    // 小时与金额工具
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

    /** 窗口覆盖的全部 UTC 整点小时（左闭右开）；要求窗口起止对齐整点。 */
    private static NavigableSet<Long> hoursOf(WindowRow window) {
        NavigableSet<Long> hours = new TreeSet<>();
        for (long hour = window.startNanos(); hour < window.endNanos(); hour += NANOS_PER_HOUR) {
            hours.add(hour);
        }
        return hours;
    }

    /** 校验申请下泄区间：整点、落在窗口内且非空。 */
    private void validateHourRange(WindowRow window, long usageStartNanos, long usageEndNanos) {
        if (usageStartNanos >= usageEndNanos) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "usageStartUtc 必须早于 usageEndUtc");
        }
        if (usageStartNanos < window.startNanos() || usageEndNanos > window.endNanos()) {
            throw ApiException.badRequest("USAGE_OUT_OF_WINDOW", "申请下泄区间必须落在窗口小时范围内");
        }
    }

    /** 申请覆盖小时 -> 均分水水量映射（2 位小数，尾差按最早小时优先分配）。 */
    private static Map<Long, BigDecimal> spreadAdditions(AllocationRow allocation) {
        int hours = (int) ((allocation.usageEndNanos() - allocation.usageStartNanos()) / NANOS_PER_HOUR);
        List<BigDecimal> shares = spreadEvenly(allocation.amount(), hours);
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        long hour = allocation.usageStartNanos();
        for (BigDecimal share : shares) {
            result.put(hour, share);
            hour += NANOS_PER_HOUR;
        }
        return result;
    }

    /**
     * 将金额（最多 2 位小数）均分到 n 个小时：每小时为非负 2 位小数，总和严格等于原金额；
     * 除不尽的尾差（每单位 0.01）优先分配给最早的小时。
     */
    static List<BigDecimal> spreadEvenly(BigDecimal amount, int hours) {
        if (hours <= 0) {
            throw new IllegalArgumentException("hours must be positive");
        }
        long cents = amount.setScale(2, java.math.RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
        long base = cents / hours;
        long remainder = cents % hours;
        List<BigDecimal> result = new ArrayList<>(hours);
        for (int i = 0; i < hours; i++) {
            long value = base + (i < remainder ? 1 : 0);
            result.add(BigDecimal.valueOf(value, 2));
        }
        return result;
    }

    private static List<ReservationInput> toReservationInputs(Map<Long, BigDecimal> additions) {
        List<ReservationInput> inputs = new ArrayList<>();
        additions.forEach((hour, volume) -> inputs.add(new ReservationInput(hour, volume)));
        return inputs;
    }

    private static void merge(Map<Long, BigDecimal> target, Map<Long, BigDecimal> source) {
        source.forEach((hour, value) -> target.merge(hour, value, BigDecimal::add));
    }

    private Map<Long, BigDecimal> capsOf(long curveId) {
        Map<Long, BigDecimal> caps = new LinkedHashMap<>();
        repository.listPoints(curveId).forEach(p -> caps.put(p.hourStartNanos(), p.capVolume()));
        return caps;
    }

    private static String encodePoints(List<DrawdownPointInput> inputs) {
        List<String> parts = new ArrayList<>();
        for (DrawdownPointInput input : inputs) {
            parts.add(input.hourStartNanos() + ":" + input.capVolume().toPlainString());
        }
        return String.join(",", parts);
    }

    private static Map<String, Object> batchIndex(int index, String key) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("index", index);
        detail.put("key", key);
        return detail;
    }

    // ------------------------------------------------------------------
    // 响应映射
    // ------------------------------------------------------------------

    private WindowResponse toWindowResponse(WindowRow row, CurtailmentRow active) {
        BigDecimal available = active != null ? active.volume() : row.plannedVolume();
        return new WindowResponse(row.id(), row.windowKey(), row.channelId(), toIso(row.startNanos()),
                toIso(row.endNanos()), fmt(row.plannedVolume()),
                active != null ? fmt(active.volume()) : null, fmt(available), toIso(row.createdNanos()));
    }

    private AllocationResponse toAllocationResponse(AllocationRow row) {
        Integer version = null;
        if (row.approvalCurveId() != null) {
            DrawdownCurveRow curve = repository.findCurveById(row.approvalCurveId());
            version = curve == null ? null : curve.version();
        }
        return toAllocationResponse(row, version);
    }

    private AllocationResponse toAllocationResponse(AllocationRow row, Map<Long, Integer> curveVersions) {
        Integer version = row.approvalCurveId() == null ? null
                : curveVersions.getOrDefault(row.approvalCurveId(), null);
        return toAllocationResponse(row, version);
    }

    private AllocationResponse toAllocationResponse(AllocationRow row, Integer curveVersion) {
        return new AllocationResponse(row.allocationKey(), row.windowId(), row.userId(), fmt(row.amount()),
                fmt(row.heldAmount()), row.requester(), row.status(),
                toIso(row.createdNanos()), toIso(row.updatedNanos()),
                row.usageStartNanos() == null ? null : toIso(row.usageStartNanos()),
                row.usageEndNanos() == null ? null : toIso(row.usageEndNanos()),
                curveVersion);
    }

    private TransferResponse toTransferResponse(TransferRow row) {
        return new TransferResponse(row.transferKey(), row.windowId(), row.sourceAllocationKey(),
                row.targetAllocationKey(), fmt(row.amount()), row.actor(), toIso(row.createdNanos()));
    }

    private CurtailmentResponse toCurtailmentResponse(CurtailmentRow row) {
        return new CurtailmentResponse(row.id(), row.windowId(), fmt(row.volume()), row.status(),
                toIso(row.createdNanos()), row.cancelledNanos() == null ? null : toIso(row.cancelledNanos()));
    }

    private DrawdownCurveResponse toCurveResponse(DrawdownCurveRow row, List<DrawdownPointRow> points) {
        List<DrawdownPointResponse> pointResponses = points.stream()
                .map(p -> new DrawdownPointResponse(toIso(p.hourStartNanos()), fmt(p.capVolume())))
                .toList();
        return new DrawdownCurveResponse(row.id(), row.windowId(), row.version(), row.status(),
                toIso(row.createdNanos()), pointResponses);
    }

    private WriteoffResponse toWriteoffResponse(WriteoffRow row) {
        return new WriteoffResponse(row.writeoffKey(), row.allocationKey(), row.windowId(),
                toIso(row.hourStartNanos()), fmt(row.volume()), row.actor(), toIso(row.createdNanos()));
    }

    // ------------------------------------------------------------------
    // 时间与数值解析
    // ------------------------------------------------------------------

    private long nowNanos() {
        return toNanos(clock.get());
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

    /** 解析 UTC 小时起点并校验整点对齐。 */
    static long parseHourStart(String field, String value) {
        long nanos = parseInstant(field, value);
        if (nanos % NANOS_PER_HOUR != 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 必须对齐 UTC 整点小时: " + value);
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

    /** 解析下泄上限：十进制字符串，非负，最多 2 位小数（允许 0）。 */
    static BigDecimal parseCapVolume(String value) {
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

    /** 解析核销量：十进制字符串，大于 0，最多 2 位小数。 */
    static BigDecimal parseWriteoffVolume(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不能为空");
        }
        String trimmed = value.trim();
        if (!CAP_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 必须为大于 0 的十进制字符串，最多 2 位小数: " + value);
        }
        BigDecimal amount = new BigDecimal(trimmed);
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 必须大于 0");
        }
        return amount;
    }

    private static int stripScale(BigDecimal value) {
        return Math.max(0, value.stripTrailingZeros().scale());
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
