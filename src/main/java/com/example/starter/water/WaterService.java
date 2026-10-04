package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.CurveNodeRow;
import com.example.starter.water.WaterRepository.CurveRow;
import com.example.starter.water.WaterRepository.PumpStationRow;
import com.example.starter.water.WaterRepository.RequestRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.CurveNode;
import com.example.starter.water.dto.Dtos.PumpStationResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.TransferListResponse;
import com.example.starter.water.dto.Dtos.TransferResponse;
import com.example.starter.water.dto.Dtos.WindowResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
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
    private static final long NANOS_PER_MINUTE = 60_000_000_000L;
    private static final MathContext MC = MathContext.DECIMAL128;
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

    /**
     * 提交配水申请，申请人为 actor。可附泵站流量曲线（2 至 20 个 UTC 整分钟节点，
     * 首末节点等于窗口边界，梯形积分必须等于申请水量）；无曲线申请按原流程，不占用泵站容量。
     */
    public AllocationResponse submitAllocation(String commandKey, String allocationKey, Long windowId,
                                               String userId, String amount, String actor,
                                               String pumpStationKey, List<CurveNode> curve) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        requireKey("userId", userId);
        requireKey("X-Actor-Id", actor);
        if (windowId == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "windowId 不能为空");
        }
        BigDecimal qty = parseAmount("amount", amount);
        if (curve != null && !curve.isEmpty()) {
            requireKey("pumpStationKey", pumpStationKey);
        }
        List<ParsedNode> nodes = parseCurve(curve, qty);
        String params = "ALLOCATION_SUBMIT|" + allocationKey + "|" + windowId + "|" + userId + "|"
                + qty.toPlainString() + "|" + actor + "|"
                + (pumpStationKey == null ? "" : pumpStationKey) + "|" + curveParams(nodes);
        return runCommand("ALLOCATION_SUBMIT", commandKey, params, AllocationResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            PumpStationRow station = null;
            if (nodes != null) {
                // 与曲线批准、取消、参数调整共用泵站行锁，按同一顺序串行判定
                station = repository.lockPumpStationByKey(pumpStationKey);
                if (station == null) {
                    throw ApiException.notFound("STATION_NOT_FOUND", "泵站不存在: " + pumpStationKey);
                }
                if (nodes.get(0).minuteNanos() != window.startNanos()
                        || nodes.get(nodes.size() - 1).minuteNanos() != window.endNanos()) {
                    throw ApiException.badRequest("CURVE_WINDOW_MISMATCH",
                            "曲线首末节点必须等于供水窗口边界");
                }
            }
            long id = repository.insertAllocation(allocationKey, windowId, userId, qty, actor, nowNanos());
            if (nodes != null) {
                long curveId = repository.insertCurve(id, station.id(), nodes.size(), nowNanos());
                for (int i = 0; i < nodes.size(); i++) {
                    ParsedNode node = nodes.get(i);
                    repository.insertCurveNode(curveId, i, node.minuteNanos(), node.flow());
                }
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
            CurveRow curve = repository.findCurveByAllocationId(allocation.id());
            PumpStationRow station = null;
            if (curve != null) {
                // 与曲线取消、参数调整共用泵站行锁，按提交顺序串行判定
                station = repository.lockPumpStationById(curve.stationId());
                if (station == null) {
                    throw ApiException.notFound("STATION_NOT_FOUND", "泵站不存在: " + curve.stationId());
                }
                LimitViolation violation = findLimitViolation(station.maxFlow(), station.maxRamp(),
                        station.id(), repository.listCurveNodes(curve.id()));
                if (violation != null) {
                    // 越限：申请仍待审且配额不扣
                    throw ApiException.unprocessable(violation.code(), violation.message());
                }
            }
            BigDecimal available = availableTotal(window);
            BigDecimal approved = repository.sumApprovedAmount(window.id());
            if (approved.add(allocation.amount()).compareTo(available) > 0) {
                throw ApiException.quotaExceeded("批准后将超过当前可用总量 " + fmt(available));
            }
            repository.updateAllocationStatus(allocation.id(), STATUS_APPROVED, nowNanos());
            if (curve != null) {
                // 批准冻结泵站配置版本，后续合法参数调整不改写本次判定
                repository.setCurveApprovedVersion(curve.id(), station.version());
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
            CurveRow curve = repository.findCurveByAllocationId(allocation.id());
            if (curve != null) {
                // 与曲线批准、参数调整共用泵站行锁；取消只去除该申请贡献
                repository.lockPumpStationById(curve.stationId());
            }
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
            if (repository.findCurveByAllocationId(lockedSource.id()) != null
                    || repository.findCurveByAllocationId(lockedTarget.id()) != null) {
                throw ApiException.conflict("CURVE_TRANSFER_UNSUPPORTED",
                        "附流量曲线的申请不参与额度转让");
            }
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
        String stationKey = null;
        List<CurveNode> curveNodes = null;
        Long approvedVersion = null;
        CurveRow curve = repository.findCurveByAllocationId(row.id());
        if (curve != null) {
            PumpStationRow station = repository.findPumpStationById(curve.stationId());
            stationKey = station == null ? null : station.stationKey();
            approvedVersion = curve.approvedVersion();
            curveNodes = repository.listCurveNodes(curve.id()).stream()
                    .map(n -> new CurveNode(toIso(n.minuteNanos()), fmt(n.flow())))
                    .toList();
        }
        return new AllocationResponse(row.allocationKey(), row.windowId(), row.userId(), fmt(row.amount()),
                fmt(row.heldAmount()), row.requester(), row.status(),
                toIso(row.createdNanos()), toIso(row.updatedNanos()),
                stationKey, curveNodes, approvedVersion);
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

    // ------------------------------------------------------------------
    // 泵站配置与流量曲线
    // ------------------------------------------------------------------

    /** 解析后的曲线节点：UTC 整分钟纳秒时间戳 + 非负流量（立方米每分钟）。 */
    private record ParsedNode(long minuteNanos, BigDecimal flow) {
    }

    /** 叠加越限结果：code 为 FLOW_LIMIT_EXCEEDED / RAMP_LIMIT_EXCEEDED。 */
    private record LimitViolation(String code, String message) {
    }

    /**
     * 写入泵站配置（创建或更新）。创建要求 expectedVersion 为 0，更新要求等于当前版本；
     * 参数调整后已有已批准曲线叠加若越限则 409。判定前不舍入。
     */
    public PumpStationResponse upsertPumpStation(String requestId, String stationKey, String maxFlow,
                                                 String maxRampPerMinute, Long expectedVersion, String actor) {
        requireKey("requestId", requestId);
        requireKey("stationKey", stationKey);
        requireKey("X-Actor-Id", actor);
        BigDecimal flow = parseAmount("maxFlow", maxFlow);
        BigDecimal ramp = parseAmount("maxRampPerMinute", maxRampPerMinute);
        if (expectedVersion == null || expectedVersion < 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "expectedVersion 必须为不小于 0 的整数");
        }
        ObjectNode identity = objectMapper.createObjectNode();
        identity.put("operation", "PUMP_STATION_UPSERT");
        identity.put("actor", actor);
        identity.put("resource", stationKey);
        ObjectNode paramsNode = identity.putObject("params");
        paramsNode.put("maxFlow", flow.toPlainString());
        paramsNode.put("maxRampPerMinute", ramp.toPlainString());
        paramsNode.put("expectedVersion", expectedVersion);
        return runRequest(requestId, identity.toString(), PumpStationResponse.class, () -> {
            PumpStationRow station = repository.lockPumpStationByKey(stationKey);
            if (station == null) {
                if (expectedVersion != 0) {
                    throw ApiException.conflict("VERSION_CONFLICT",
                            "泵站不存在，创建时 expectedVersion 必须为 0");
                }
                long id = repository.insertPumpStation(stationKey, flow, ramp, nowNanos());
                return toStationResponse(repository.findPumpStationById(id));
            }
            if (expectedVersion != station.version()) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "expectedVersion " + expectedVersion + " 与当前版本 " + station.version() + " 不一致");
            }
            LimitViolation violation = findLimitViolation(flow, ramp, station.id(), null);
            if (violation != null) {
                throw ApiException.conflict("STATION_PARAMS_CONFLICT",
                        "参数调整后已有已批准曲线叠加越限: " + violation.message());
            }
            repository.updatePumpStation(station.id(), flow, ramp, station.version() + 1, nowNanos());
            return toStationResponse(repository.findPumpStationById(station.id()));
        });
    }

    /** 查询泵站当前配置。 */
    public PumpStationResponse getPumpStation(String stationKey) {
        PumpStationRow station = repository.findPumpStationByKey(stationKey);
        if (station == null) {
            throw ApiException.notFound("STATION_NOT_FOUND", "泵站不存在: " + stationKey);
        }
        return toStationResponse(station);
    }

    private PumpStationResponse toStationResponse(PumpStationRow row) {
        return new PumpStationResponse(row.stationKey(), fmt(row.maxFlow()), fmt(row.maxRamp()),
                row.version(), toIso(row.createdNanos()), toIso(row.updatedNanos()));
    }

    /**
     * 解析并校验曲线结构：2 至 20 个 UTC 整分钟节点、时刻严格递增、流量非负且最多 3 位小数、
     * 梯形积分等于申请水量（不等返回 422）。无曲线返回 null。
     */
    private List<ParsedNode> parseCurve(List<CurveNode> curve, BigDecimal amount) {
        if (curve == null || curve.isEmpty()) {
            return null;
        }
        if (curve.size() < 2 || curve.size() > 20) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "曲线节点数必须为 2 至 20 个");
        }
        List<ParsedNode> nodes = new ArrayList<>(curve.size());
        long previous = Long.MIN_VALUE;
        for (CurveNode node : curve) {
            if (node == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "曲线节点不能为空");
            }
            long nanos = parseInstant("curve.timeUtc", node.timeUtc());
            if (nanos % NANOS_PER_MINUTE != 0) {
                throw ApiException.badRequest("INVALID_ARGUMENT",
                        "曲线节点时刻必须为 UTC 整分钟: " + node.timeUtc());
            }
            if (nanos <= previous) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "曲线节点时刻必须严格递增");
            }
            previous = nanos;
            nodes.add(new ParsedNode(nanos, parseFlow(node.flow())));
        }
        // 梯形积分（整分钟间隔，精确计算）：Σ (f_i + f_{i+1}) / 2 × Δt
        BigDecimal integral = BigDecimal.ZERO;
        for (int i = 0; i + 1 < nodes.size(); i++) {
            long minutes = (nodes.get(i + 1).minuteNanos() - nodes.get(i).minuteNanos()) / NANOS_PER_MINUTE;
            integral = integral.add(nodes.get(i).flow().add(nodes.get(i + 1).flow())
                    .multiply(BigDecimal.valueOf(minutes))
                    .divide(BigDecimal.valueOf(2)));
        }
        if (integral.compareTo(amount) != 0) {
            throw ApiException.unprocessable("CURVE_INTEGRAL_MISMATCH",
                    "曲线梯形积分 " + fmt(integral) + " 与申请水量 " + fmt(amount) + " 不一致");
        }
        return nodes;
    }

    /** 解析流量：十进制字符串，非负，最多 3 位小数。 */
    private static BigDecimal parseFlow(String value) {
        if (value == null || value.isBlank() || !AMOUNT_PATTERN.matcher(value.trim()).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "曲线流量必须为非负十进制字符串，最多 3 位小数: " + value);
        }
        return new BigDecimal(value.trim());
    }

    /** 曲线参数规范化串（纳秒:流量），用于幂等参数比较。 */
    private static String curveParams(List<ParsedNode> nodes) {
        if (nodes == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ParsedNode node : nodes) {
            sb.append(node.minuteNanos()).append(':').append(node.flow().toPlainString()).append(',');
        }
        return sb.toString();
    }

    /**
     * 在泵站全部已批准曲线（可选叠加候选曲线）上检查流量与斜率上限。
     * 叠加曲线为分段线性，在所有节点时刻并集处取得极值；斜率按相邻并集时刻精确比较
     * |ΔF| ≤ maxRamp × Δt，判定前不舍入。越限返回违规信息，否则 null。
     */
    private LimitViolation findLimitViolation(BigDecimal maxFlow, BigDecimal maxRamp, long stationId,
                                              List<CurveNodeRow> candidate) {
        List<List<CurveNodeRow>> curves = new ArrayList<>();
        for (CurveRow approved : repository.listApprovedCurves(stationId)) {
            curves.add(repository.listCurveNodes(approved.id()));
        }
        if (candidate != null) {
            curves.add(candidate);
        }
        TreeSet<Long> union = new TreeSet<>();
        for (List<CurveNodeRow> nodes : curves) {
            for (CurveNodeRow node : nodes) {
                union.add(node.minuteNanos());
            }
        }
        if (union.isEmpty()) {
            return null;
        }
        List<Long> times = new ArrayList<>(union);
        List<BigDecimal> totals = new ArrayList<>(times.size());
        for (int i = 0; i < times.size(); i++) {
            totals.add(BigDecimal.ZERO);
        }
        for (List<CurveNodeRow> nodes : curves) {
            for (int i = 0; i < times.size(); i++) {
                totals.set(i, totals.get(i).add(evalCurve(nodes, times.get(i))));
            }
        }
        for (int i = 0; i < times.size(); i++) {
            if (totals.get(i).compareTo(maxFlow) > 0) {
                return new LimitViolation("FLOW_LIMIT_EXCEEDED",
                        "时刻 " + toIso(times.get(i)) + " 叠加流量 " + fmt(totals.get(i))
                                + " 超过最大流量 " + fmt(maxFlow));
            }
        }
        for (int i = 0; i + 1 < times.size(); i++) {
            long minutes = (times.get(i + 1) - times.get(i)) / NANOS_PER_MINUTE;
            BigDecimal delta = totals.get(i + 1).subtract(totals.get(i)).abs();
            BigDecimal limit = maxRamp.multiply(BigDecimal.valueOf(minutes));
            if (delta.compareTo(limit) > 0) {
                return new LimitViolation("RAMP_LIMIT_EXCEEDED",
                        "时刻 " + toIso(times.get(i)) + " 至 " + toIso(times.get(i + 1))
                                + " 叠加变化量 " + fmt(delta) + " 超过每分钟最大变化量 " + fmt(maxRamp)
                                + " × " + minutes + " 分钟");
            }
        }
        return null;
    }

    /** 曲线在指定时刻的取值：范围外为 0，节点处精确取值，节点间线性插值。 */
    private static BigDecimal evalCurve(List<CurveNodeRow> nodes, long nanos) {
        if (nanos < nodes.get(0).minuteNanos() || nanos > nodes.get(nodes.size() - 1).minuteNanos()) {
            return BigDecimal.ZERO;
        }
        for (int i = 0; i < nodes.size(); i++) {
            CurveNodeRow node = nodes.get(i);
            if (node.minuteNanos() == nanos) {
                return node.flow();
            }
            if (node.minuteNanos() > nanos) {
                CurveNodeRow prev = nodes.get(i - 1);
                BigDecimal span = BigDecimal.valueOf(node.minuteNanos() - prev.minuteNanos());
                BigDecimal offset = BigDecimal.valueOf(nanos - prev.minuteNanos());
                return prev.flow().add(node.flow().subtract(prev.flow()).multiply(offset)
                        .divide(span, MC));
            }
        }
        return BigDecimal.ZERO;
    }

    // ------------------------------------------------------------------
    // 请求幂等框架（requestId，结构化身份）
    // ------------------------------------------------------------------

    /**
     * 在单事务内执行幂等请求：先占位插入请求行，再执行业务并写回响应，业务与去重原子提交。
     * 身份为结构化 JSON（操作、操作者、资源、全部参数与期望版本），同键同身份重放首次完整响应，
     * 异参 409，失败回滚不占键；并发同键不泄露唯一键异常。
     */
    private <T> T runRequest(String requestKey, String identity, Class<T> type, Supplier<T> business) {
        try {
            return tx.execute(status -> {
                RequestRow existing = repository.findRequest(requestKey);
                if (existing != null) {
                    return replayRequest(existing, identity, type);
                }
                repository.insertRequest(requestKey, identity, nowNanos());
                T result = business.get();
                repository.updateRequestResponse(requestKey, toJson(result));
                return result;
            });
        } catch (DuplicateKeyException e) {
            RequestRow committed = repository.findRequest(requestKey);
            if (committed == null || committed.response() == null) {
                throw ApiException.conflict("REQUEST_CONFLICT", "相同 requestId 的请求正在处理，请重试");
            }
            return replayRequest(committed, identity, type);
        }
    }

    private <T> T replayRequest(RequestRow existing, String identity, Class<T> type) {
        if (!existing.identity().equals(identity)) {
            throw ApiException.conflict("REQUEST_KEY_REUSED", "相同 requestId 但身份或参数不同，拒绝重放");
        }
        return fromJson(existing.response(), type);
    }
}