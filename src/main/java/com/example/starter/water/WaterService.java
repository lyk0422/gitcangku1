package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.ScheduleRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.UsageRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.AllocationSchedulesResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.ChannelScheduleResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.ScheduleResponse;
import com.example.starter.water.dto.Dtos.TransferListResponse;
import com.example.starter.water.dto.Dtos.TransferResponse;
import com.example.starter.water.dto.Dtos.UsageResponse;
import com.example.starter.water.dto.Dtos.WindowResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
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

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("\\d{1,16}(\\.\\d{1,3})?");
    private static final Pattern KEY_PATTERN = Pattern.compile("[\\w.\\-:]{1,128}");

    private final WaterRepository repository;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public WaterService(WaterRepository repository, PlatformTransactionManager transactionManager,
                        ObjectMapper objectMapper) {
        this(repository, transactionManager, objectMapper, Clock.systemUTC());
    }

    WaterService(WaterRepository repository, PlatformTransactionManager transactionManager,
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
            BigDecimal sourceRemaining = lockedSource.heldAmount().subtract(lockedSource.writtenOffAmount());
            if (sourceRemaining.compareTo(amount) < 0) {
                throw ApiException.quotaExceeded("源申请当前剩余未核销水量 " + fmt(sourceRemaining)
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
    // 轮灌排班与用水核销
    // ------------------------------------------------------------------

    private static final long NANOS_PER_MINUTE = 60L * NANOS_PER_SECOND;
    private static final long MIN_SLOT_NANOS = 30L * NANOS_PER_MINUTE;
    private static final long MAX_SLOT_NANOS = 720L * NANOS_PER_MINUTE;
    private static final int MAX_ACTIVE_SCHEDULES_PER_ALLOCATION = 3;

    /**
     * 创建轮灌引水时段：渠道取自所属窗口；时段须为 30~720 分钟且完全落在窗口内；
     * 申请须 APPROVED、未取消且剩余未核销水量大于 0；同窗口最多持有 3 个生效时段；
     * 同渠道时间重叠（左闭右开，端点相接合法）返回 409 并给出全部冲突时段。
     */
    public ScheduleResponse createSchedule(String commandKey, String scheduleKey, String allocationKey,
                                           String startUtc, String endUtc, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("scheduleKey", scheduleKey);
        requireKey("allocationKey", allocationKey);
        requireKey("X-Actor-Id", actor);
        long startNanos = parseInstant("startUtc", startUtc);
        long endNanos = parseInstant("endUtc", endUtc);
        long duration = endNanos - startNanos;
        if (duration < MIN_SLOT_NANOS || duration > MAX_SLOT_NANOS) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "引水时段时长必须在 30 至 720 分钟之间（含端点）");
        }
        String params = "SCHEDULE_CREATE|" + scheduleKey + "|" + allocationKey + "|" + startNanos + "|"
                + endNanos + "|" + actor;
        return runCommand("SCHEDULE_CREATE", commandKey, params, ScheduleResponse.class, () -> {
            if (repository.findScheduleByKey(scheduleKey) != null) {
                throw ApiException.conflict("SCHEDULE_KEY_REUSED", "scheduleKey 已被使用: " + scheduleKey);
            }
            AllocationRow allocation = repository.findAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            if (!allocation.requester().equals(actor)) {
                throw ApiException.conflict("NOT_OWNER", "只有申请人本人可以为该申请排班");
            }
            // 窗口行锁：与并发排班、转让、取消、核销按事务提交顺序串行裁决
            WindowRow window = lockWindowOf(allocation);
            // 锁内重读申请，得到最新状态、持有额度与累计核销
            allocation = repository.lockAllocationByKey(allocationKey);
            if (!STATUS_APPROVED.equals(allocation.status())) {
                throw ApiException.unprocessable("ALLOCATION_NOT_SCHEDULABLE",
                        "只有 APPROVED 且未取消的申请可以排班，当前状态: " + allocation.status(), null);
            }
            BigDecimal remaining = remainingOf(allocation);
            if (remaining.signum() <= 0) {
                throw ApiException.unprocessable("NO_REMAINING_WATER",
                        "申请剩余未核销水量为 0，不能再排班", null);
            }
            if (startNanos < window.startNanos() || endNanos > window.endNanos()) {
                throw ApiException.badRequest("SLOT_OUTSIDE_WINDOW",
                        "引水时段必须完全落在所属配水窗口 [" + toIso(window.startNanos()) + ", "
                                + toIso(window.endNanos()) + ") 内");
            }
            List<ScheduleRow> conflicts =
                    repository.findOverlappingActiveSchedules(window.channelId(), startNanos, endNanos);
            if (!conflicts.isEmpty()) {
                throw ApiException.conflict("SCHEDULE_OVERLAP",
                        "同一渠道在该时段已存在生效引水时段",
                        Map.of("conflicts", conflicts.stream().map(this::toScheduleResponse).toList()));
            }
            if (repository.countActiveSchedules(allocationKey, window.id())
                    >= MAX_ACTIVE_SCHEDULES_PER_ALLOCATION) {
                throw ApiException.unprocessable("TOO_MANY_SCHEDULES",
                        "单个申请在同一窗口内最多持有 " + MAX_ACTIVE_SCHEDULES_PER_ALLOCATION
                                + " 个生效时段", null);
            }
            long now = nowNanos();
            final long id;
            try {
                id = repository.insertSchedule(scheduleKey, window.channelId(), window.id(), allocationKey,
                        startNanos, endNanos, remaining, now);
            } catch (DuplicateKeyException e) {
                // 并发复用同一 scheduleKey（换 commandKey）：事务回滚，不产生排班
                throw ApiException.conflict("SCHEDULE_KEY_REUSED", "scheduleKey 已被使用: " + scheduleKey);
            }
            return toScheduleResponse(repository.findScheduleById(id));
        });
    }

    /** 取消排班：立即释放同渠道重叠占用；起始时刻已到的时段不得取消（409），历史记录保留。 */
    public ScheduleResponse cancelSchedule(String commandKey, String scheduleKey, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("scheduleKey", scheduleKey);
        requireKey("X-Actor-Id", actor);
        String params = "SCHEDULE_CANCEL|" + scheduleKey + "|" + actor;
        return runCommand("SCHEDULE_CANCEL", commandKey, params, ScheduleResponse.class, () -> {
            ScheduleRow schedule = repository.findScheduleByKey(scheduleKey);
            if (schedule == null) {
                throw ApiException.notFound("SCHEDULE_NOT_FOUND", "排班记录不存在: " + scheduleKey);
            }
            AllocationRow allocation = repository.findAllocationByKey(schedule.allocationKey());
            // 与排班/转让/核销争用同一窗口锁，按事务提交顺序裁决
            WindowRow window = repository.lockWindowById(schedule.windowId());
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + schedule.windowId());
            }
            schedule = repository.findScheduleById(schedule.id());
            if (allocation != null && !allocation.requester().equals(actor)) {
                throw ApiException.conflict("NOT_OWNER", "只有申请人本人可以取消该排班");
            }
            if ("CANCELLED".equals(schedule.status())) {
                throw ApiException.conflict("SCHEDULE_ALREADY_CANCELLED", "排班已取消，不能重复取消");
            }
            if (nowNanos() >= schedule.startNanos()) {
                throw ApiException.conflict("SCHEDULE_ALREADY_STARTED",
                        "引水时段起始时刻已到，不能取消",
                        Map.of("schedule", toScheduleResponse(schedule)));
            }
            repository.cancelSchedule(schedule.id(), nowNanos());
            return toScheduleResponse(repository.findScheduleById(schedule.id()));
        });
    }

    /**
     * 用水核销：用水时刻必须落在该申请某个生效时段内，否则 422 并给出最近可用时段；
     * 本次核销后累计核销不得超过申请持有额度（剩余未核销水量），否则 422；
     * 排班时固化的剩余水量快照不受核销影响。
     */
    public UsageResponse writeOffUsage(String commandKey, String usageKey, String allocationKey,
                                       String volume, String usedAtUtc, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("usageKey", usageKey);
        requireKey("allocationKey", allocationKey);
        requireKey("X-Actor-Id", actor);
        BigDecimal qty = parseAmount("volume", volume);
        long usedAtNanos = parseInstant("usedAtUtc", usedAtUtc);
        String params = "USAGE_WRITE_OFF|" + usageKey + "|" + allocationKey + "|" + qty.toPlainString()
                + "|" + usedAtNanos + "|" + actor;
        return runCommand("USAGE_WRITE_OFF", commandKey, params, UsageResponse.class, () -> {
            if (repository.findUsageByKey(usageKey) != null) {
                throw ApiException.conflict("USAGE_KEY_REUSED", "usageKey 已被使用: " + usageKey);
            }
            AllocationRow allocation = repository.findAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            if (!allocation.requester().equals(actor)) {
                throw ApiException.conflict("NOT_OWNER", "只有申请人本人可以核销该申请的用水");
            }
            // 窗口行锁：与排班、转让、取消按事务提交顺序串行
            WindowRow window = lockWindowOf(allocation);
            allocation = repository.lockAllocationByKey(allocationKey);
            if (!STATUS_APPROVED.equals(allocation.status())) {
                throw ApiException.unprocessable("ALLOCATION_NOT_ACTIVE",
                        "只有 APPROVED 申请可以核销用水，当前状态: " + allocation.status(), null);
            }
            List<ScheduleRow> active = repository.listSchedulesByAllocation(allocationKey).stream()
                    .filter(s -> "ACTIVE".equals(s.status())
                            && usedAtNanos >= s.startNanos() && usedAtNanos < s.endNanos())
                    .toList();
            if (active.isEmpty()) {
                ScheduleRow nearest = nearestActiveSchedule(
                        repository.listSchedulesByAllocation(allocationKey), usedAtNanos);
                Object details = nearest == null ? null
                        : Map.of("nearestSchedule", toScheduleResponse(nearest));
                throw ApiException.unprocessable("OUTSIDE_ACTIVE_SLOT",
                        "用水时刻 " + usedAtUtc + " 不在该申请任何生效引水时段内", details);
            }
            ScheduleRow slot = active.get(0);
            BigDecimal remaining = remainingOf(allocation);
            if (qty.compareTo(remaining) > 0) {
                throw ApiException.quotaExceeded("本次核销 " + fmt(qty) + " 超过申请剩余未核销水量 "
                        + fmt(remaining));
            }
            long now = nowNanos();
            try {
                repository.insertUsage(usageKey, allocationKey, slot.id(), qty, usedAtNanos, now);
            } catch (DuplicateKeyException e) {
                // 并发复用同一 usageKey（换 commandKey）：事务回滚，不产生核销
                throw ApiException.conflict("USAGE_KEY_REUSED", "usageKey 已被使用: " + usageKey);
            }
            repository.addWrittenOffAmount(allocation.id(), qty, now);
            return toUsageResponse(repository.findUsageByKey(usageKey));
        });
    }

    /** 查询渠道排班表（含已取消历史记录）。 */
    public ChannelScheduleResponse getChannelSchedule(String channelId) {
        requireKey("channelId", channelId);
        List<ScheduleResponse> schedules = repository.listSchedulesByChannel(channelId).stream()
                .map(this::toScheduleResponse).toList();
        return new ChannelScheduleResponse(channelId, schedules);
    }

    /** 查询申请时段明细（含已取消历史记录）；申请不存在返回 404。 */
    public AllocationSchedulesResponse getAllocationSchedules(String allocationKey) {
        requireKey("allocationKey", allocationKey);
        AllocationRow allocation = repository.findAllocationByKey(allocationKey);
        if (allocation == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
        }
        List<ScheduleResponse> schedules = repository.listSchedulesByAllocation(allocationKey).stream()
                .map(this::toScheduleResponse).toList();
        return new AllocationSchedulesResponse(allocationKey, schedules);
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

    /** 申请剩余未核销水量 = 当前持有额度 - 累计已核销水量。 */
    private BigDecimal remainingOf(AllocationRow allocation) {
        return allocation.heldAmount().subtract(allocation.writtenOffAmount());
    }

    /**
     * 距离给定时刻最近的生效时段：时刻早于时段按距开始时刻计，晚于/等于结束时刻按距结束时刻计，
     * 距离相同取开始时刻更早者。无生效时段返回 null。
     */
    private ScheduleRow nearestActiveSchedule(List<ScheduleRow> schedules, long atNanos) {
        return schedules.stream()
                .filter(s -> "ACTIVE".equals(s.status()))
                .min(Comparator.comparingLong((ScheduleRow s) -> distanceToSlot(s, atNanos))
                        .thenComparingLong(ScheduleRow::startNanos)
                        .thenComparingLong(ScheduleRow::id))
                .orElse(null);
    }

    private long distanceToSlot(ScheduleRow slot, long atNanos) {
        if (atNanos < slot.startNanos()) {
            return slot.startNanos() - atNanos;
        }
        return atNanos - slot.endNanos();
    }

    private ScheduleResponse toScheduleResponse(ScheduleRow row) {
        return new ScheduleResponse(row.id(), row.scheduleKey(), row.channelId(), row.windowId(),
                row.allocationKey(), toIso(row.startNanos()), toIso(row.endNanos()),
                fmt(row.remainingSnapshot()), row.status(), toIso(row.createdNanos()),
                row.cancelledNanos() == null ? null : toIso(row.cancelledNanos()));
    }

    private UsageResponse toUsageResponse(UsageRow row) {
        return new UsageResponse(row.usageKey(), row.allocationKey(), row.scheduleId(), fmt(row.volume()),
                toIso(row.usedAtNanos()), toIso(row.createdNanos()));
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
                fmt(row.heldAmount()), fmt(row.writtenOffAmount()), row.requester(), row.status(),
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

    long nowNanos() {
        return toNanos(Instant.now(clock));
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
