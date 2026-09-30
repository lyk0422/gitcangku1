package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.OutageRow;
import com.example.starter.water.WaterRepository.PostponementRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WaterUsageRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.OutageListResponse;
import com.example.starter.water.dto.Dtos.OutageResponse;
import com.example.starter.water.dto.Dtos.PostponementResponse;
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
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 灌区配水业务服务。
 *
 * <p>幂等：每个写命令携带 commandKey，事务内先占位插入命令行再执行业务并写回响应；
 * 同键同参重放返回首次结果，同键改参返回 409。中断登记的业务幂等键为 requestId
 * （同 requestId 换顺延分钟数返回 409）。</p>
 *
 * <p>并发：批准申请与创建/取消限供在同一事务内先对窗口行 SELECT ... FOR UPDATE，
 * 按事务提交顺序生效，保证已批准总量永不超过最终可用总量。排班发布与中断登记先对灌渠行
 * SELECT ... FOR UPDATE 串行化，再锁定交叉排班行，保证不会留下交叉占用。</p>
 *
 * <p>时钟：统一通过可替换的 {@link Clock} 取当前时刻，测试可将时间拨到中断窗口前后验证
 * “未开始/已开始”分支；生产环境默认系统 UTC 时钟。</p>
 */
@Service
public class WaterService {

    static final String STATUS_REQUESTED = "REQUESTED";
    static final String STATUS_APPROVED = "APPROVED";
    static final String STATUS_CANCELLED = "CANCELLED";
    static final String OUTAGE_REGISTERED = "REGISTERED";

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long NANOS_PER_MINUTE = 60L * NANOS_PER_SECOND;
    private static final int MAX_POSTPONE_MINUTES = 24 * 60 * 365;
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("\\d{1,16}(\\.\\d{1,3})?");
    private static final Pattern KEY_PATTERN = Pattern.compile("[\\w.\\-:]{1,128}");
    private static final Pattern REASON_PATTERN = Pattern.compile("[\\p{L}\\p{N}\\p{P}\\p{Zs}]{1,512}");

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

    /** 提交配水申请，申请人为 actor；携带排班时段时该申请同时成为一条排班占用。 */
    public AllocationResponse submitAllocation(String commandKey, String allocationKey, Long windowId,
                                               String userId, String amount, String actor,
                                               String scheduledStartUtc, String scheduledEndUtc) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        requireKey("userId", userId);
        requireKey("X-Actor-Id", actor);
        if (windowId == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "windowId 不能为空");
        }
        BigDecimal qty = parseAmount("amount", amount);
        Long scheduledStartNanos = null;
        Long scheduledEndNanos = null;
        if (scheduledStartUtc != null || scheduledEndUtc != null) {
            if (scheduledStartUtc == null || scheduledEndUtc == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT",
                        "scheduledStartUtc 与 scheduledEndUtc 必须同时给出或同时为空");
            }
            scheduledStartNanos = parseInstant("scheduledStartUtc", scheduledStartUtc);
            scheduledEndNanos = parseInstant("scheduledEndUtc", scheduledEndUtc);
            if (scheduledStartNanos >= scheduledEndNanos) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "排班开始必须早于排班结束");
            }
        }
        String params = "ALLOCATION_SUBMIT|" + allocationKey + "|" + windowId + "|" + userId + "|"
                + qty.toPlainString() + "|" + actor + "|" + scheduledStartNanos + "|" + scheduledEndNanos;
        Long scheduleStart = scheduledStartNanos;
        Long scheduleEnd = scheduledEndNanos;
        return runCommand("ALLOCATION_SUBMIT", commandKey, params, AllocationResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            if (scheduleStart != null) {
                // 先锁灌渠行，与中断登记及同灌渠其他窗口的排班发布串行化
                repository.insertChannelIfAbsent(window.channelId(), nowNanos());
                repository.lockChannelByKey(window.channelId());
                if (scheduleStart < window.startNanos() || scheduleEnd > window.endNanos()) {
                    throw ApiException.unprocessable("SCHEDULE_OUT_OF_WINDOW",
                            "排班时段必须落在配水窗口 [" + toIso(window.startNanos()) + ", "
                                    + toIso(window.endNanos()) + ") 内");
                }
                if (repository.existsOverlappingOutage(window.channelId(), scheduleStart, scheduleEnd)) {
                    throw ApiException.conflict("SCHEDULE_OVERLAPS_OUTAGE",
                            "排班时段与已登记的灌渠中断窗口交叉");
                }
                if (repository.existsOverlappingActiveSchedule(window.channelId(), scheduleStart, scheduleEnd,
                        allocationKey)) {
                    throw ApiException.conflict("SCHEDULE_OVERLAP", "排班时段与同灌渠其他占用重叠，相邻端点合法");
                }
            }
            long id = repository.insertAllocation(allocationKey, windowId, userId, qty, actor,
                    scheduleStart, scheduleEnd, nowNanos());
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
    // 灌渠中断与排班顺延
    // ------------------------------------------------------------------

    /**
     * 登记灌渠中断窗口：整批顺延所有与中断交叉（UTC 左闭右开）且未开始、未取消的排班，
     * 顺延分钟数对全部受影响排班相同。
     *
     * <p>整批原子：任一排班已开始（开始时刻不晚于当前时刻）、顺延后超出所属配水窗口、
     * 顺延后仍落在中断窗口内或与其他排班占用重叠，均返回 422 并回滚，排班、中断状态与额度不变。
     * 同 requestId 同参（含顺延分钟数）重放返回首次结果，换顺延值返回 409。</p>
     */
    public OutageResponse registerOutage(String commandKey, String requestId, String channelId,
                                         String startUtc, String endUtc, Integer postponeMinutes,
                                         String reason) {
        requireKey("commandKey", commandKey);
        requireKey("requestId", requestId);
        requireKey("channelId", channelId);
        long startNanos = parseInstant("startUtc", startUtc);
        long endNanos = parseInstant("endUtc", endUtc);
        if (startNanos >= endNanos) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "中断开始必须早于中断结束");
        }
        int minutes = parsePostponeMinutes(postponeMinutes);
        String normalizedReason = normalizeReason(reason);
        String params = "OUTAGE_REGISTER|" + requestId + "|" + channelId + "|" + startNanos + "|" + endNanos
                + "|" + minutes + "|" + normalizedReason;
        return runCommand("OUTAGE_REGISTER", commandKey, params, OutageResponse.class, () -> {
            long now = nowNanos();
            repository.insertChannelIfAbsent(channelId, now);
            // 灌渠行锁：与排班发布、其他中断登记按事务提交顺序串行裁决
            repository.lockChannelByKey(channelId);
            OutageRow existing = repository.findOutageByRequestId(requestId);
            if (existing != null) {
                if (!existing.channelKey().equals(channelId) || existing.startNanos() != startNanos
                        || existing.endNanos() != endNanos || existing.postponeMinutes() != minutes
                        || !existing.reason().equals(normalizedReason)) {
                    throw ApiException.conflict("OUTAGE_REQUEST_CONFLICT",
                            "相同 requestId 已登记但参数不同（含顺延分钟数），拒绝重放: " + requestId);
                }
                return toOutageResponse(existing,
                        repository.listPostponementsByOutage(requestId));
            }

            long shift = Math.multiplyExact((long) minutes, NANOS_PER_MINUTE);
            // 先查出交叉排班，再按申请主键顺序逐行加锁，与取消/转让串行
            List<AllocationRow> crossing = repository.listCrossingActiveAllocations(
                    channelId, startNanos, endNanos);
            List<AllocationRow> locked = new ArrayList<>();
            for (AllocationRow row : crossing) {
                AllocationRow fresh = repository.lockAllocationByKey(row.allocationKey());
                if (fresh != null && !STATUS_CANCELLED.equals(fresh.status())) {
                    locked.add(fresh);
                }
            }

            // 整批校验阶段：计算每个排班的顺延后窗口
            List<long[]> movedIntervals = new ArrayList<>();
            for (AllocationRow allocation : locked) {
                if (allocation.scheduledStartNanos() == null) {
                    continue;
                }
                if (allocation.scheduledStartNanos() <= now) {
                    throw ApiException.unprocessable("SCHEDULE_ALREADY_STARTED",
                            "排班 " + allocation.allocationKey() + " 已开始，不能随中断顺延");
                }
                WindowRow window = repository.findWindowById(allocation.windowId());
                long newStart = Math.addExact(allocation.scheduledStartNanos(), shift);
                long newEnd = Math.addExact(allocation.scheduledEndNanos(), shift);
                if (newStart < window.startNanos() || newEnd > window.endNanos()) {
                    throw ApiException.unprocessable("POSTPONE_OUT_OF_WINDOW",
                            "排班 " + allocation.allocationKey() + " 顺延 " + minutes
                                    + " 分钟后超出配水窗口，整批拒绝");
                }
                movedIntervals.add(new long[]{allocation.id(), newStart, newEnd});
            }

            // 不能与其他占用重叠：移动中的排班用新窗口，其余同灌渠排班用当前窗口，两两左闭右开判交叉
            Map<Long, long[]> movedById = new LinkedHashMap<>();
            for (long[] interval : movedIntervals) {
                movedById.put(interval[0], interval);
            }
            List<long[]> effectiveIntervals = new ArrayList<>();
            for (AllocationRow occupant : repository.listActiveSchedulesOnChannel(channelId)) {
                long[] moved = movedById.get(occupant.id());
                if (moved != null) {
                    effectiveIntervals.add(new long[]{moved[1], moved[2]});
                } else {
                    effectiveIntervals.add(new long[]{occupant.scheduledStartNanos(),
                            occupant.scheduledEndNanos()});
                }
            }
            for (int i = 0; i < effectiveIntervals.size(); i++) {
                long[] a = effectiveIntervals.get(i);
                for (int j = i + 1; j < effectiveIntervals.size(); j++) {
                    long[] b = effectiveIntervals.get(j);
                    if (overlaps(a[0], a[1], b[0], b[1])) {
                        throw ApiException.unprocessable("POSTPONE_SCHEDULE_OVERLAP",
                                "顺延后排班与同灌渠其他占用重叠（相邻端点不算），整批拒绝");
                    }
                }
            }
            // 顺延后仍不得落在任何中断窗口内（含本次正在登记的窗口，相邻合法）
            List<long[]> outageIntervals = new ArrayList<>();
            for (OutageRow outage : repository.listOutagesByChannel(channelId)) {
                outageIntervals.add(new long[]{outage.startNanos(), outage.endNanos()});
            }
            outageIntervals.add(new long[]{startNanos, endNanos});
            for (long[] moved : movedIntervals) {
                for (long[] outage : outageIntervals) {
                    if (overlaps(moved[1], moved[2], outage[0], outage[1])) {
                        throw ApiException.unprocessable("POSTPONE_OVERLAPS_OUTAGE",
                                "顺延后排班仍与灌渠中断窗口交叉，整批拒绝");
                    }
                }
            }

            // 全部通过后一次性落库：中断、顺延对应关系、排班当前窗口
            try {
                repository.insertOutage(requestId, channelId, normalizedReason, startNanos, endNanos,
                        minutes, now);
            } catch (DuplicateKeyException e) {
                // 并发以不同 commandKey 登记同一 requestId：参数一致则重放首次结果，否则 409
                OutageRow winner = repository.findOutageByRequestId(requestId);
                if (winner == null || !winner.channelKey().equals(channelId)
                        || winner.startNanos() != startNanos || winner.endNanos() != endNanos
                        || winner.postponeMinutes() != minutes || !winner.reason().equals(normalizedReason)) {
                    throw ApiException.conflict("OUTAGE_REQUEST_CONFLICT",
                            "相同 requestId 已登记但参数不同（含顺延分钟数），拒绝重放: " + requestId);
                }
                return toOutageResponse(winner, repository.listPostponementsByOutage(requestId));
            }
            for (AllocationRow allocation : locked) {
                long newStart = Math.addExact(allocation.scheduledStartNanos(), shift);
                long newEnd = Math.addExact(allocation.scheduledEndNanos(), shift);
                repository.insertPostponement(requestId, channelId, allocation.allocationKey(),
                        allocation.scheduledStartNanos(), allocation.scheduledEndNanos(),
                        newStart, newEnd, minutes, normalizedReason, now);
                repository.updateAllocationSchedule(allocation.id(), newStart, newEnd, now);
            }
            return toOutageResponse(repository.findOutageByRequestId(requestId),
                    repository.listPostponementsByOutage(requestId));
        });
    }

    /** 按 requestId 查询中断：中断窗口、原因与全部顺延对应关系（原排班→新窗口）。 */
    public OutageResponse getOutage(String requestId) {
        requireKey("requestId", requestId);
        OutageRow outage = repository.findOutageByRequestId(requestId);
        if (outage == null) {
            throw ApiException.notFound("OUTAGE_NOT_FOUND", "灌渠中断不存在: " + requestId);
        }
        return toOutageResponse(outage, repository.listPostponementsByOutage(requestId));
    }

    /** 查询灌渠全部已登记中断及其顺延对应关系，按登记顺序返回；无中断返回空列表。 */
    public OutageListResponse listOutages(String channelId) {
        requireKey("channelId", channelId);
        List<OutageResponse> outages = repository.listOutagesByChannel(channelId).stream()
                .map(outage -> toOutageResponse(outage,
                        repository.listPostponementsByOutage(outage.requestId())))
                .toList();
        return new OutageListResponse(channelId, outages);
    }

    // ------------------------------------------------------------------
    // 历史用水核销
    // ------------------------------------------------------------------

    /**
     * 历史用水核销：引用并快照核销当时的排班时段，随后中断顺延不改写已完成记录。
     * 仅 APPROVED 申请可核销，累计核销水量不得超过当前持有额度，否则 422。
     */
    public SettlementResponse settleUsage(String commandKey, String usageKey, String allocationKey,
                                          String volume, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("usageKey", usageKey);
        requireKey("allocationKey", allocationKey);
        requireKey("X-Actor-Id", actor);
        BigDecimal qty = parseAmount("volume", volume);
        String params = "USAGE_SETTLE|" + usageKey + "|" + allocationKey + "|" + qty.toPlainString() + "|" + actor;
        return runCommand("USAGE_SETTLE", commandKey, params, SettlementResponse.class, () -> {
            if (repository.findWaterUsageByKey(usageKey) != null) {
                throw ApiException.conflict("USAGE_KEY_REUSED", "usageKey 已被使用: " + usageKey);
            }
            AllocationRow allocation = repository.findAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            lockWindowOf(allocation);
            AllocationRow locked = repository.lockAllocationByKey(allocationKey);
            if (!STATUS_APPROVED.equals(locked.status())) {
                throw ApiException.conflict("ALLOCATION_NOT_APPROVED",
                        "只有 APPROVED 状态的申请可以核销用水，当前状态: " + locked.status());
            }
            BigDecimal settled = repository.sumSettledVolume(allocationKey);
            if (settled.add(qty).compareTo(locked.heldAmount()) > 0) {
                throw ApiException.unprocessable("USAGE_EXCEEDS_HELD",
                        "累计核销 " + fmt(settled.add(qty)) + " 将超过当前持有额度 " + fmt(locked.heldAmount()));
            }
            long now = nowNanos();
            try {
                repository.insertWaterUsage(usageKey, allocationKey, locked.windowId(), qty,
                        locked.scheduledStartNanos(), locked.scheduledEndNanos(), actor, now);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("USAGE_KEY_REUSED", "usageKey 已被使用: " + usageKey);
            }
            return toSettlementResponse(repository.findWaterUsageByKey(usageKey));
        });
    }

    /** 查询申请的历史核销记录（不可变快照），按核销顺序返回。 */
    public SettlementListResponse listSettlements(String allocationKey) {
        requireKey("allocationKey", allocationKey);
        AllocationRow allocation = repository.findAllocationByKey(allocationKey);
        if (allocation == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
        }
        List<SettlementResponse> settlements = repository.listWaterUsagesByAllocation(allocationKey).stream()
                .map(this::toSettlementResponse).toList();
        return new SettlementListResponse(allocationKey, settlements);
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
                row.scheduledStartNanos() == null ? null : toIso(row.scheduledStartNanos()),
                row.scheduledEndNanos() == null ? null : toIso(row.scheduledEndNanos()),
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

    /** 当前时刻（UTC 纳秒），生产取系统时钟，测试可注入固定/偏移时钟。 */
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

    /** 校验顺延分钟数：必填正整数，上限约一年，防止移位乘法溢出。 */
    static int parsePostponeMinutes(Integer postponeMinutes) {
        if (postponeMinutes == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "postponeMinutes 不能为空");
        }
        if (postponeMinutes <= 0 || postponeMinutes > MAX_POSTPONE_MINUTES) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "postponeMinutes 必须为 1 到 " + MAX_POSTPONE_MINUTES + " 之间的整数分钟");
        }
        return postponeMinutes;
    }

    /** 规范化中断原因：必填，trim 后 1-512 字符；允许中英文、数字、标点与空白。 */
    String normalizeReason(String reason) {
        if (reason == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "reason 不能为空");
        }
        String trimmed = reason.trim();
        if (trimmed.isEmpty() || !REASON_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "reason 必须为 1 到 512 字符的可读文本: " + reason);
        }
        return trimmed;
    }

    /** UTC 左闭右开区间交叉判定；相邻端点（aEnd == bStart）不算重叠。 */
    static boolean overlaps(long aStart, long aEnd, long bStart, long bEnd) {
        return aStart < bEnd && bStart < aEnd;
    }

    private OutageResponse toOutageResponse(OutageRow row, List<PostponementRow> postponements) {
        List<PostponementResponse> items = postponements.stream()
                .map(p -> new PostponementResponse(p.allocationKey(),
                        toIso(p.originalStartNanos()), toIso(p.originalEndNanos()),
                        toIso(p.newStartNanos()), toIso(p.newEndNanos()),
                        p.postponeMinutes(), p.reason()))
                .toList();
        return new OutageResponse(row.requestId(), row.channelKey(), row.reason(),
                toIso(row.startNanos()), toIso(row.endNanos()), row.postponeMinutes(), row.status(),
                toIso(row.createdNanos()), items);
    }

    private SettlementResponse toSettlementResponse(WaterUsageRow row) {
        return new SettlementResponse(row.usageKey(), row.allocationKey(), row.windowId(), fmt(row.volume()),
                row.scheduleStartNanos() == null ? null : toIso(row.scheduleStartNanos()),
                row.scheduleEndNanos() == null ? null : toIso(row.scheduleEndNanos()),
                row.actor(), toIso(row.createdNanos()));
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
