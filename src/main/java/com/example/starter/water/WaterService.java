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
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.EvaporationDiagnosticsResponse;
import com.example.starter.water.dto.Dtos.EvaporationRateListResponse;
import com.example.starter.water.dto.Dtos.EvaporationRateResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.SettlementBatchResponse;
import com.example.starter.water.dto.Dtos.SettlementItemRequest;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    // ------------------------------------------------------------------
    // 渠道蒸发损耗结算
    // ------------------------------------------------------------------

    private static final String KIND_SETTLEMENT = "SETTLEMENT";
    private static final String KIND_REVERSAL = "REVERSAL";
    private static final BigDecimal MAX_RATE = new BigDecimal("0.2");
    private static final Pattern RATE_PATTERN = Pattern.compile("\\d{1,2}(\\.\\d{1,4})?");

    /**
     * 登记或修改渠道某日（UTC 自然日）蒸发率。当日首次核销提交后率被锁定，再修改返回 409；
     * 与核销按事务提交顺序裁决（双方都锁定同一蒸发率行）。
     */
    public EvaporationRateResponse upsertEvaporationRate(String commandKey, String channelId, String dateStr,
                                                         String rateStr) {
        requireKey("commandKey", commandKey);
        requireKey("channelId", channelId);
        LocalDate date = parseDate("date", dateStr);
        BigDecimal rate = parseRate(rateStr);
        String params = "EVAP_RATE_UPSERT|" + channelId + "|" + date + "|" + rate.toPlainString();
        return runCommand("EVAP_RATE_UPSERT", commandKey, params, EvaporationRateResponse.class, () -> {
            EvaporationRateRow existing = repository.lockRate(channelId, date);
            long now = nowNanos();
            if (existing == null) {
                try {
                    repository.insertRate(channelId, date, rate, now);
                } catch (DuplicateKeyException e) {
                    throw ApiException.conflict("RATE_CONCURRENT_CREATE",
                            "同一渠道同日期的蒸发率正在并发登记，请重试");
                }
            } else {
                if (repository.existsSettlementForChannelDate(channelId, date)) {
                    throw ApiException.conflict("RATE_LOCKED",
                            "渠道 " + channelId + " 在 " + date + " 已存在核销，当日蒸发率不可再修改");
                }
                repository.updateRate(existing.id(), rate, existing.version() + 1, now);
            }
            return toRateResponse(repository.findRate(channelId, date), false);
        });
    }

    /**
     * 批量核销送达水量：先按完整最终余额与逐笔舍入口径预校验全部核销项，任一失败整批回滚；
     * 全部通过后在一个事务内扣减上游持有额度并写入不可变核销记录（固化蒸发率版本、原始送达量、
     * 损耗与上游扣减），满足 上游扣减=送达+损耗。
     */
    public SettlementBatchResponse settleDeliveries(String commandKey, List<SettlementItemRequest> items) {
        requireKey("commandKey", commandKey);
        if (items == null || items.isEmpty()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "items 不能为空");
        }
        // 事务外纯参数解析：参数非法直接 400，不占幂等键
        List<ParsedItem> parsed = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        StringBuilder params = new StringBuilder("SETTLEMENT_BATCH");
        for (SettlementItemRequest item : items) {
            if (item == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "items 不能包含空项");
            }
            requireKey("settlementKey", item.settlementKey());
            requireKey("allocationKey", item.allocationKey());
            LocalDate date = parseDate("date", item.date());
            BigDecimal delivered = parseAmount("deliveredAmount", item.deliveredAmount());
            if (!seenKeys.add(item.settlementKey())) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "批内 settlementKey 重复: " + item.settlementKey());
            }
            parsed.add(new ParsedItem(item.settlementKey(), item.allocationKey(), date, delivered));
            params.append('|').append(item.settlementKey()).append(',').append(item.allocationKey())
                    .append(',').append(date).append(',').append(delivered.toPlainString());
        }
        return runCommand("SETTLEMENT_BATCH", commandKey, params.toString(), SettlementBatchResponse.class, () -> {
            // 业务键复用检查（换 commandKey 复用 -> 409）
            for (ParsedItem item : parsed) {
                if (repository.findSettlementByKey(item.settlementKey()) != null) {
                    throw ApiException.conflict("SETTLEMENT_KEY_REUSED",
                            "settlementKey 已被使用: " + item.settlementKey());
                }
            }
            // 解析申请与窗口（窗口创建后不可变，无需锁定）
            Map<String, AllocationRow> allocations = new LinkedHashMap<>();
            Map<String, WindowRow> windows = new LinkedHashMap<>();
            for (ParsedItem item : parsed) {
                if (!allocations.containsKey(item.allocationKey())) {
                    AllocationRow allocation = repository.findAllocationByKey(item.allocationKey());
                    if (allocation == null) {
                        throw ApiException.notFound("ALLOCATION_NOT_FOUND",
                                "配水申请不存在: " + item.allocationKey());
                    }
                    allocations.put(item.allocationKey(), allocation);
                    windows.put(item.allocationKey(), repository.findWindowById(allocation.windowId()));
                }
            }
            // 按（渠道, 日期）升序锁定蒸发率行，与“修改当日率”按提交顺序串行裁决
            Map<String, EvaporationRateRow> rates = new LinkedHashMap<>();
            List<String> rateKeys = new ArrayList<>();
            for (ParsedItem item : parsed) {
                String channelId = windows.get(item.allocationKey()).channelId();
                String rateKey = channelId + "@" + item.date();
                if (!rates.containsKey(rateKey)) {
                    rateKeys.add(rateKey);
                    rates.put(rateKey, null);
                }
            }
            rateKeys.sort(String::compareTo);
            for (String rateKey : rateKeys) {
                String channelId = rateKey.substring(0, rateKey.indexOf('@'));
                LocalDate date = LocalDate.parse(rateKey.substring(rateKey.indexOf('@') + 1));
                EvaporationRateRow rate = repository.lockRate(channelId, date);
                if (rate == null) {
                    throw ApiException.unprocessable("RATE_MISSING",
                            "渠道 " + channelId + " 在 " + date + " 未登记蒸发率，无法核销");
                }
                rates.put(rateKey, rate);
            }
            // 按业务键升序锁定申请行，得到最新持有额度，防止同一上游额度并发超卖
            Map<String, AllocationRow> locked = new LinkedHashMap<>();
            List<String> allocationKeys = new ArrayList<>(allocations.keySet());
            allocationKeys.sort(String::compareTo);
            for (String allocationKey : allocationKeys) {
                locked.put(allocationKey, repository.lockAllocationByKey(allocationKey));
            }
            // 完整预校验：状态、日期在窗口内、逐笔舍入守恒、完整最终余额
            Map<String, BigDecimal> running = new LinkedHashMap<>();
            locked.forEach((key, allocation) -> running.put(key, allocation.heldAmount()));
            List<ComputedItem> computed = new ArrayList<>();
            for (ParsedItem item : parsed) {
                AllocationRow allocation = locked.get(item.allocationKey());
                if (!STATUS_APPROVED.equals(allocation.status())) {
                    throw ApiException.conflict("ALLOCATION_NOT_APPROVED",
                            "申请 " + item.allocationKey() + " 状态为 " + allocation.status() + "，不能核销");
                }
                WindowRow window = windows.get(item.allocationKey());
                long dayStart = dayStartNanos(item.date());
                long dayEnd = dayStartNanos(item.date().plusDays(1));
                if (dayStart < window.startNanos() || dayEnd > window.endNanos()) {
                    throw ApiException.unprocessable("DATE_OUT_OF_WINDOW",
                            "核销日期 " + item.date() + "（日区间 [" + toIso(dayStart) + ", " + toIso(dayEnd)
                                    + ")）不在配水窗口 [" + toIso(window.startNanos()) + ", "
                                    + toIso(window.endNanos()) + ") 内");
                }
                EvaporationRateRow rate = rates.get(window.channelId() + "@" + item.date());
                BigDecimal loss = item.delivered().multiply(rate.rate())
                        .setScale(2, RoundingMode.HALF_EVEN);
                BigDecimal deducted = item.delivered().add(loss).setScale(2, RoundingMode.HALF_EVEN);
                if (deducted.subtract(loss).compareTo(item.delivered()) != 0) {
                    throw ApiException.unprocessable("CONSERVATION_VIOLATION",
                            "舍入后守恒不成立：送达 " + fmt(item.delivered()) + " + 损耗 " + fmt(loss)
                                    + " 与两位小数的上游扣减 " + fmt(deducted) + " 不一致");
                }
                BigDecimal remain = running.get(item.allocationKey()).subtract(deducted);
                if (remain.signum() < 0) {
                    throw ApiException.unprocessable("UPSTREAM_QUOTA_INSUFFICIENT",
                            "申请 " + item.allocationKey() + " 上游剩余额度 "
                                    + fmt(running.get(item.allocationKey())) + " 不足，本笔需扣减 " + fmt(deducted)
                                    + "，差额 " + fmt(remain.negate()));
                }
                running.put(item.allocationKey(), remain);
                computed.add(new ComputedItem(item, allocation, window, rate, loss, deducted, remain));
            }
            // 预校验全部通过：同一事务内扣减上游额度并写入不可变核销记录
            long now = nowNanos();
            List<SettlementResponse> responses = new ArrayList<>();
            for (ComputedItem item : computed) {
                repository.decrementHeldAmount(item.allocation().id(), item.deducted(), now);
                repository.insertSettlement(item.parsed().settlementKey(), KIND_SETTLEMENT,
                        item.window().id(), item.parsed().allocationKey(), item.window().channelId(),
                        item.parsed().date(), item.rate().version(), item.rate().rate(),
                        item.parsed().delivered(), item.loss(), item.deducted(), item.remain(), null, now);
                responses.add(toSettlementResponse(
                        repository.findSettlementByKey(item.parsed().settlementKey())));
            }
            return new SettlementBatchResponse(responses);
        });
    }

    /**
     * 反向更正：已结算核销不可改写，只能生成等额反向记录（数量为负）并回补上游持有额度。
     * 同一核销至多更正一次；反向记录本身不能再被更正。
     */
    public SettlementResponse reverseSettlement(String commandKey, String settlementKey, String reversalKey) {
        requireKey("commandKey", commandKey);
        requireKey("settlementKey", settlementKey);
        requireKey("reversalKey", reversalKey);
        String params = "SETTLEMENT_REVERSAL|" + settlementKey + "|" + reversalKey;
        return runCommand("SETTLEMENT_REVERSAL", commandKey, params, SettlementResponse.class, () -> {
            SettlementRow original = repository.findSettlementByKey(settlementKey);
            if (original == null) {
                throw ApiException.notFound("SETTLEMENT_NOT_FOUND", "核销记录不存在: " + settlementKey);
            }
            if (!KIND_SETTLEMENT.equals(original.kind())) {
                throw ApiException.conflict("NOT_REVERSABLE", "反向更正记录不能再次被更正: " + settlementKey);
            }
            if (repository.findSettlementByKey(reversalKey) != null) {
                throw ApiException.conflict("SETTLEMENT_KEY_REUSED", "reversalKey 已被使用: " + reversalKey);
            }
            // 锁定上游申请行，与核销/转让按提交顺序串行裁决
            AllocationRow allocation = repository.lockAllocationByKey(original.allocationKey());
            if (!STATUS_APPROVED.equals(allocation.status())) {
                throw ApiException.conflict("ALLOCATION_NOT_APPROVED",
                        "申请 " + original.allocationKey() + " 状态为 " + allocation.status() + "，不能回补额度");
            }
            if (repository.findReversalOf(settlementKey) != null) {
                throw ApiException.conflict("ALREADY_REVERSED", "该核销已被反向更正: " + settlementKey);
            }
            BigDecimal newHeld = allocation.heldAmount().add(original.deductedAmount());
            if (newHeld.compareTo(allocation.amount()) > 0) {
                throw ApiException.unprocessable("REVERSAL_EXCEEDS_QUOTA",
                        "回补后持有额度 " + fmt(newHeld) + " 将超过原申请水量 " + fmt(allocation.amount()));
            }
            long now = nowNanos();
            repository.incrementHeldAmount(allocation.id(), original.deductedAmount(), now);
            repository.insertSettlement(reversalKey, KIND_REVERSAL, original.windowId(),
                    original.allocationKey(), original.channelId(), original.rateDate(), original.rateVersion(),
                    original.rate(), original.deliveredAmount().negate(), original.lossAmount().negate(),
                    original.deductedAmount().negate(), newHeld, settlementKey, now);
            return toSettlementResponse(repository.findSettlementByKey(reversalKey));
        });
    }

    /** 查询单笔核销记录明细（只读）。 */
    public SettlementResponse getSettlement(String settlementKey) {
        SettlementRow row = repository.findSettlementByKey(settlementKey);
        if (row == null) {
            throw ApiException.notFound("SETTLEMENT_NOT_FOUND", "核销记录不存在: " + settlementKey);
        }
        return toSettlementResponse(row);
    }

    /** 查询申请全部核销历史（含反向更正），按发生顺序返回（只读）。 */
    public SettlementListResponse getAllocationSettlements(String allocationKey) {
        if (repository.findAllocationByKey(allocationKey) == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
        }
        List<SettlementResponse> settlements = repository.listSettlementsByAllocation(allocationKey).stream()
                .map(this::toSettlementResponse).toList();
        return new SettlementListResponse(allocationKey, settlements);
    }

    /** 查询渠道某日蒸发率（只读）。 */
    public EvaporationRateResponse getEvaporationRate(String channelId, String dateStr) {
        requireKey("channelId", channelId);
        LocalDate date = parseDate("date", dateStr);
        EvaporationRateRow rate = repository.findRate(channelId, date);
        if (rate == null) {
            throw ApiException.notFound("RATE_NOT_FOUND",
                    "渠道 " + channelId + " 在 " + date + " 未登记蒸发率");
        }
        return toRateResponse(rate, repository.existsSettlementForChannelDate(channelId, date));
    }

    /** 查询渠道全部蒸发率（只读）。 */
    public EvaporationRateListResponse listEvaporationRates(String channelId) {
        requireKey("channelId", channelId);
        List<EvaporationRateResponse> rates = repository.listRates(channelId).stream()
                .map(row -> toRateResponse(row,
                        repository.existsSettlementForChannelDate(row.channelId(), row.rateDate())))
                .toList();
        return new EvaporationRateListResponse(channelId, rates);
    }

    /** 渠道某日蒸发结算诊断：当前率与版本、锁定状态、正常核销/反向更正/净额合计（只读）。 */
    public EvaporationDiagnosticsResponse getEvaporationDiagnostics(String channelId, String dateStr) {
        requireKey("channelId", channelId);
        LocalDate date = parseDate("date", dateStr);
        EvaporationRateRow rate = repository.findRate(channelId, date);
        SettlementTotals settled = repository.sumSettlements(channelId, date, KIND_SETTLEMENT);
        SettlementTotals reversed = repository.sumSettlements(channelId, date, KIND_REVERSAL);
        return new EvaporationDiagnosticsResponse(channelId, date.toString(),
                rate == null ? null : fmt(rate.rate()), rate == null ? null : rate.version(),
                settled.count() > 0, settled.count(), reversed.count(),
                fmt(settled.delivered()), fmt(settled.loss()), fmt(settled.deducted()),
                fmt(reversed.delivered()), fmt(reversed.loss()), fmt(reversed.deducted()),
                fmt(settled.delivered().add(reversed.delivered())),
                fmt(settled.loss().add(reversed.loss())),
                fmt(settled.deducted().add(reversed.deducted())));
    }

    /** 解析后的核销项（参数校验已通过）。 */
    private record ParsedItem(String settlementKey, String allocationKey, LocalDate date,
                              BigDecimal delivered) {
    }

    /** 预校验通过的核销项计算结果（含固化率、损耗、扣减与扣减后剩余额度）。 */
    private record ComputedItem(ParsedItem parsed, AllocationRow allocation, WindowRow window,
                                EvaporationRateRow rate, BigDecimal loss, BigDecimal deducted,
                                BigDecimal remain) {
    }

    private EvaporationRateResponse toRateResponse(EvaporationRateRow row, boolean locked) {
        return new EvaporationRateResponse(row.channelId(), row.rateDate().toString(), fmt(row.rate()),
                row.version(), locked, toIso(row.createdNanos()), toIso(row.updatedNanos()));
    }

    private SettlementResponse toSettlementResponse(SettlementRow row) {
        return new SettlementResponse(row.settlementKey(), row.kind(), row.windowId(), row.allocationKey(),
                row.channelId(), row.rateDate().toString(), row.rateVersion(), fmt(row.rate()),
                fmt(row.deliveredAmount()), fmt(row.lossAmount()), fmt(row.deductedAmount()),
                row.reversalOfKey(), fmt(row.remainingHeld()), toIso(row.createdNanos()));
    }

    /** 解析 UTC 自然日（yyyy-MM-dd）。 */
    static LocalDate parseDate(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不能为空");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 不是合法的 UTC 日期（yyyy-MM-dd）: " + value);
        }
    }

    /** 解析蒸发率：0~0.2（0~20%）的十进制字符串，最多四位小数。 */
    static BigDecimal parseRate(String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "rate 不能为空");
        }
        String trimmed = value.trim();
        if (!RATE_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "rate 必须为 0~0.2 的十进制字符串，最多四位小数: " + value);
        }
        BigDecimal rate = new BigDecimal(trimmed);
        if (rate.compareTo(MAX_RATE) > 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "rate 必须在 0~0.2 之间（0~20%），实际为: " + value);
        }
        return rate;
    }

    /** UTC 自然日 00:00 的纳秒时间戳。 */
    static long dayStartNanos(LocalDate date) {
        return toNanos(date.atStartOfDay(ZoneOffset.UTC).toInstant());
    }
}
