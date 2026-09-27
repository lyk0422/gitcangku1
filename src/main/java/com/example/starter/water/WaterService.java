package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.AnomalyRow;
import com.example.starter.water.WaterRepository.ChannelRuleRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.ReadingRow;
import com.example.starter.water.WaterRepository.ReleaseRow;
import com.example.starter.water.WaterRepository.SettlementRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.AnomalyDetailResponse;
import com.example.starter.water.dto.Dtos.AnomalyListResponse;
import com.example.starter.water.dto.Dtos.AnomalyResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.ChannelRuleListResponse;
import com.example.starter.water.dto.Dtos.ChannelStatusResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.ReadingItem;
import com.example.starter.water.dto.Dtos.ReadingListResponse;
import com.example.starter.water.dto.Dtos.ReadingResponse;
import com.example.starter.water.dto.Dtos.ReleaseResponse;
import com.example.starter.water.dto.Dtos.RuleVersionResponse;
import com.example.starter.water.dto.Dtos.SettlementListResponse;
import com.example.starter.water.dto.Dtos.SettlementResponse;
import com.example.starter.water.dto.Dtos.SubmitReadingsResponse;
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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
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

    static final String TYPE_REGRESSION = "REGRESSION";
    static final String TYPE_FLOW_EXCEEDED = "FLOW_EXCEEDED";
    static final String TYPE_INTERVAL_EXCEEDED = "INTERVAL_EXCEEDED";

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long NANOS_PER_MINUTE = 60_000_000_000L;
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
    // 计量传感异常与实际核销
    // ------------------------------------------------------------------

    /**
     * 配置渠道异常判定规则：每次成功配置生成新的不可变版本，只影响其后的读数判定。
     * 上限或间隔参数非法返回 422。
     */
    public RuleVersionResponse configureChannelRule(String commandKey, String channelId, String maxFlowRate,
                                                    Long maxIntervalMinutes, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("channelId", channelId);
        requireKey("X-Actor-Id", actor);
        BigDecimal maxFlow = parsePositiveAmount422("maxFlowRate", maxFlowRate);
        int interval = parseIntervalMinutes(maxIntervalMinutes);
        String params = "CHANNEL_RULE_SET|" + esc(channelId) + "|" + esc(maxFlow.toPlainString()) + "|"
                + interval + "|" + esc(actor);
        return runCommand("CHANNEL_RULE_SET", commandKey, params, RuleVersionResponse.class, () -> {
            repository.ensureChannel(channelId, nowNanos());
            repository.lockChannel(channelId);
            ChannelRuleRow current = repository.findCurrentRule(channelId);
            int version = current == null ? 1 : current.version() + 1;
            repository.insertChannelRule(channelId, version, maxFlow, interval, actor, nowNanos());
            return toRuleResponse(repository.findCurrentRule(channelId));
        });
    }

    /** 查询渠道规则版本历史。 */
    public ChannelRuleListResponse getChannelRules(String channelId) {
        requireChannel(channelId);
        List<ChannelRuleRow> rules = repository.listChannelRules(channelId);
        Integer current = rules.isEmpty() ? null : rules.get(rules.size() - 1).version();
        return new ChannelRuleListResponse(channelId, current,
                rules.stream().map(this::toRuleResponse).toList());
    }

    /**
     * 批量提交累计读数：先校验完整序列（时刻严格递增、读数格式合法），任一错误不写入；
     * 然后在同一事务内写入全部读数并按当前规则版本逐对判定异常，异常即冻结渠道新核销。
     * 读数时刻不递增返回 422。
     */
    public SubmitReadingsResponse submitReadings(String commandKey, String channelId,
                                                 List<ReadingItem> items, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("channelId", channelId);
        requireKey("X-Actor-Id", actor);
        if (items == null || items.isEmpty()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "readings 不能为空");
        }
        if (items.size() > 1000) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "一批读数最多 1000 条，实际: " + items.size());
        }
        List<ParsedReading> parsed = new ArrayList<>(items.size());
        for (ReadingItem item : items) {
            if (item == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "readings 不能包含空项");
            }
            parsed.add(new ParsedReading(parseInstant("observedUtc", item.observedUtc()),
                    parseReadingValue(item.cumulativeValue())));
        }
        for (int i = 1; i < parsed.size(); i++) {
            if (parsed.get(i).observedNanos() <= parsed.get(i - 1).observedNanos()) {
                throw ApiException.unprocessable("READING_TIME_NOT_INCREASING",
                        "读数时刻必须严格递增，上一时刻 " + toIso(parsed.get(i - 1).observedNanos())
                                + "，当前时刻 " + toIso(parsed.get(i).observedNanos()));
            }
        }
        StringBuilder params = new StringBuilder("READINGS_SUBMIT|").append(esc(channelId)).append('|')
                .append(esc(actor));
        for (ParsedReading p : parsed) {
            params.append('|').append(esc(Long.toString(p.observedNanos()))).append(',')
                    .append(esc(p.value().toPlainString()));
        }
        return runCommand("READINGS_SUBMIT", commandKey, params.toString(), SubmitReadingsResponse.class,
                () -> {
                    repository.ensureChannel(channelId, nowNanos());
                    repository.lockChannel(channelId);
                    ChannelRuleRow rule = repository.findCurrentRule(channelId);
                    if (rule == null) {
                        throw ApiException.notFound("CHANNEL_RULE_NOT_FOUND",
                                "渠道未配置异常判定规则: " + channelId);
                    }
                    MeterPoint prev = latestMeterPoint(channelId);
                    if (prev != null && parsed.get(0).observedNanos() <= prev.observedNanos()) {
                        throw ApiException.unprocessable("READING_TIME_NOT_INCREASING",
                                "读数时刻必须晚于已有计量点时刻 " + toIso(prev.observedNanos())
                                        + "，本次最早时刻 " + toIso(parsed.get(0).observedNanos()));
                    }
                    List<ReadingResponse> readings = new ArrayList<>(parsed.size());
                    List<AnomalyResponse> anomalies = new ArrayList<>();
                    for (ParsedReading p : parsed) {
                        long readingId = repository.insertReading(channelId, p.observedNanos(), p.value(),
                                actor, nowNanos());
                        if (prev != null) {
                            AnomalyRow anomaly = evaluateAnomaly(rule, prev, p, readingId, channelId,
                                    actor);
                            if (anomaly != null) {
                                anomalies.add(toAnomalyResponse(anomaly, false));
                            }
                        }
                        readings.add(new ReadingResponse(readingId, toIso(p.observedNanos()),
                                fmt(p.value()), actor, toIso(nowNanos())));
                        prev = new MeterPoint(p.observedNanos(), p.value());
                    }
                    boolean frozen = repository.countOpenFreezes(channelId) > 0;
                    return new SubmitReadingsResponse(channelId, readings.size(), frozen,
                            List.copyOf(readings), List.copyOf(anomalies));
                });
    }

    /** 查询渠道读数历史，按读数时刻与主键稳定排序。 */
    public ReadingListResponse getReadings(String channelId) {
        requireChannel(channelId);
        return new ReadingListResponse(channelId,
                repository.listReadings(channelId).stream().map(this::toReadingResponse).toList());
    }

    /**
     * 解除异常：须由与触发读数提交人不同的操作人确认诊断说明与替代读数。
     * 解除只新增不可变解除记录，不改写原异常与既有核销；替代读数成为后续判定的计量基线。
     */
    public ReleaseResponse releaseAnomaly(String commandKey, long anomalyId, String diagnosis,
                                          String substituteValue, String substituteObservedUtc, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("X-Actor-Id", actor);
        if (diagnosis == null || diagnosis.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "diagnosis 不能为空");
        }
        if (diagnosis.length() > 1024) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "diagnosis 最长 1024 字符，实际: " + diagnosis.length());
        }
        BigDecimal substitute = parseReadingValue(substituteValue);
        long substituteNanos = parseInstant("substituteObservedUtc", substituteObservedUtc);
        String params = "ANOMALY_RELEASE|" + anomalyId + "|" + esc(diagnosis) + "|"
                + esc(substitute.toPlainString()) + "|" + substituteNanos + "|" + esc(actor);
        return runCommand("ANOMALY_RELEASE", commandKey, params, ReleaseResponse.class, () -> {
            AnomalyRow anomaly = repository.findAnomalyById(anomalyId);
            if (anomaly == null) {
                throw ApiException.notFound("ANOMALY_NOT_FOUND", "异常不存在: " + anomalyId);
            }
            repository.lockChannel(anomaly.channelId());
            if (repository.findReleaseByAnomalyId(anomalyId) != null) {
                throw ApiException.conflict("ANOMALY_ALREADY_RELEASED", "异常已解除，不能重复解除: " + anomalyId);
            }
            if (anomaly.readingActor().equals(actor)) {
                throw ApiException.conflict("DIFFERENT_OPERATOR_REQUIRED",
                        "解除确认人必须与触发读数提交人不同: " + actor);
            }
            if (substituteNanos < anomaly.readingObservedNanos()) {
                throw ApiException.unprocessable("SUBSTITUTE_TIME_TOO_EARLY",
                        "替代读数时刻 " + toIso(substituteNanos) + " 早于触发读数时刻 "
                                + toIso(anomaly.readingObservedNanos()));
            }
            try {
                repository.insertRelease(anomalyId, anomaly.channelId(), diagnosis, substitute,
                        substituteNanos, actor, nowNanos());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("ANOMALY_ALREADY_RELEASED", "异常已解除，不能重复解除: " + anomalyId);
            }
            boolean frozen = repository.countOpenFreezes(anomaly.channelId()) > 0;
            return toReleaseResponse(repository.findReleaseByAnomalyId(anomalyId), frozen);
        });
    }

    /** 查询渠道异常与诊断明细，按异常主键稳定排序；未解除的 release 为 null。 */
    public AnomalyListResponse getAnomalies(String channelId) {
        requireChannel(channelId);
        Map<Long, ReleaseRow> releases = new HashMap<>();
        for (ReleaseRow release : repository.listReleases(channelId)) {
            releases.put(release.anomalyId(), release);
        }
        List<AnomalyDetailResponse> details = repository.listAnomalies(channelId).stream()
                .map(a -> {
                    ReleaseRow release = releases.get(a.id());
                    return new AnomalyDetailResponse(toAnomalyResponse(a, release != null),
                            release == null ? null
                                    : toReleaseResponse(release, repository.countOpenFreezes(channelId) > 0));
                })
                .toList();
        return new AnomalyListResponse(channelId, details);
    }

    /** 查询渠道状态：是否冻结、未解除异常数与当前规则版本。 */
    public ChannelStatusResponse getChannelStatus(String channelId) {
        requireChannel(channelId);
        int open = repository.countOpenFreezes(channelId);
        ChannelRuleRow current = repository.findCurrentRule(channelId);
        return new ChannelStatusResponse(channelId, open > 0, open,
                current == null ? null : current.version());
    }

    /**
     * 实际核销：扣减 APPROVED 申请的持有额度并写不可变核销流水。
     * 渠道存在未解除异常冻结时禁止新核销（409）；核销水量超过持有额度返回 422（含实际值与差额）。
     * 与读数、解除、规则配置按渠道锁行以提交顺序串行裁决。
     */
    public SettlementResponse settle(String commandKey, String settlementKey, String allocationKey,
                                     String amount, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("settlementKey", settlementKey);
        requireKey("allocationKey", allocationKey);
        requireKey("X-Actor-Id", actor);
        BigDecimal qty = parseAmount("amount", amount);
        String params = "SETTLEMENT_CREATE|" + esc(settlementKey) + "|" + esc(allocationKey) + "|"
                + esc(qty.toPlainString()) + "|" + esc(actor);
        return runCommand("SETTLEMENT_CREATE", commandKey, params, SettlementResponse.class, () -> {
            if (repository.findSettlementByKey(settlementKey) != null) {
                throw ApiException.conflict("SETTLEMENT_KEY_REUSED", "settlementKey 已被使用: " + settlementKey);
            }
            AllocationRow allocation = repository.findAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            WindowRow window = repository.findWindowById(allocation.windowId());
            String channelId = window.channelId();
            repository.ensureChannel(channelId, nowNanos());
            repository.lockChannel(channelId);
            int open = repository.countOpenFreezes(channelId);
            if (open > 0) {
                throw ApiException.conflict("CHANNEL_FROZEN",
                        "渠道 " + channelId + " 存在 " + open + " 条未解除异常冻结，禁止新核销");
            }
            AllocationRow locked = repository.lockAllocationByKey(allocationKey);
            if (!STATUS_APPROVED.equals(locked.status())) {
                throw ApiException.conflict("ALLOCATION_NOT_APPROVED",
                        "只有 APPROVED 状态的申请可以核销，当前状态: " + locked.status());
            }
            if (locked.heldAmount().compareTo(qty) < 0) {
                throw ApiException.unprocessable("SETTLEMENT_EXCEEDS_HELD",
                        "核销水量（要求值）" + fmt(qty) + " 超过当前持有额度（实际值）" + fmt(locked.heldAmount())
                                + "，差额 " + fmt(qty.subtract(locked.heldAmount())));
            }
            long now = nowNanos();
            repository.decrementHeldAmount(locked.id(), qty, now);
            try {
                repository.insertSettlement(settlementKey, allocationKey, window.id(), channelId, qty,
                        actor, now);
            } catch (DuplicateKeyException e) {
                // 并发复用同一 settlementKey（换 commandKey）：事务回滚，额度无变化
                throw ApiException.conflict("SETTLEMENT_KEY_REUSED",
                        "settlementKey 已被使用: " + settlementKey);
            }
            return toSettlementResponse(repository.findSettlementByKey(settlementKey),
                    locked.heldAmount().subtract(qty));
        });
    }

    /** 查询申请核销历史，按主键稳定排序。 */
    public SettlementListResponse getSettlements(String allocationKey) {
        AllocationRow allocation = repository.findAllocationByKey(allocationKey);
        if (allocation == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
        }
        List<SettlementResponse> settlements = repository.listSettlements(allocationKey).stream()
                .map(row -> toSettlementResponse(row, null))
                .toList();
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

    /** 计量点（读数或解除替代值），作为相邻判定的前一基线。 */
    private record MeterPoint(long observedNanos, BigDecimal value) {
    }

    /** 解析后的读数项。 */
    private record ParsedReading(long observedNanos, BigDecimal value) {
    }

    /** 渠道当前最新计量点：最新读数与不早于它的最新替代值中的较晚者，无则 null。 */
    private MeterPoint latestMeterPoint(String channelId) {
        ReadingRow reading = repository.findLatestReading(channelId);
        ReleaseRow release = repository.findLatestRelease(channelId);
        if (release != null && (reading == null
                || release.substituteObservedNanos() >= reading.observedNanos())) {
            return new MeterPoint(release.substituteObservedNanos(), release.substituteValue());
        }
        if (reading != null) {
            return new MeterPoint(reading.observedNanos(), reading.cumulativeValue());
        }
        return null;
    }

    /**
     * 逐对判定异常，每个读数至多生成一条异常，按 倒退 → 间隔 → 流量 顺序取首个命中规则。
     * 命中即插入不可变异常与冻结记录并返回异常行，未命中返回 null。
     */
    private AnomalyRow evaluateAnomaly(ChannelRuleRow rule, MeterPoint prev, ParsedReading current,
                                       long readingId, String channelId, String actor) {
        long intervalNanos = current.observedNanos() - prev.observedNanos();
        String type = null;
        BigDecimal actual = null;
        BigDecimal required = null;
        if (current.value().compareTo(prev.value()) < 0) {
            type = TYPE_REGRESSION;
            actual = current.value();
            required = prev.value();
        } else if (intervalNanos > rule.maxIntervalMinutes() * NANOS_PER_MINUTE) {
            type = TYPE_INTERVAL_EXCEEDED;
            actual = BigDecimal.valueOf(intervalNanos)
                    .divide(BigDecimal.valueOf(NANOS_PER_MINUTE), 6, RoundingMode.HALF_UP);
            required = BigDecimal.valueOf(rule.maxIntervalMinutes());
        } else {
            // 精确判定：流量 > 上限 等价于 增量 * 每秒纳秒数 > 上限 * 间隔纳秒数
            BigDecimal delta = current.value().subtract(prev.value());
            BigDecimal lhs = delta.multiply(BigDecimal.valueOf(NANOS_PER_SECOND));
            BigDecimal rhs = rule.maxFlowRate().multiply(BigDecimal.valueOf(intervalNanos));
            if (lhs.compareTo(rhs) > 0) {
                type = TYPE_FLOW_EXCEEDED;
                actual = lhs.divide(BigDecimal.valueOf(intervalNanos), 6, RoundingMode.HALF_UP);
                required = rule.maxFlowRate();
            }
        }
        if (type == null) {
            return null;
        }
        long now = nowNanos();
        long anomalyId = repository.insertAnomaly(channelId, rule.id(), readingId, type, actual, required,
                actual.subtract(required), prev.observedNanos(), prev.value(), current.observedNanos(),
                current.value(), actor, now);
        repository.insertFreeze(anomalyId, channelId, now);
        return repository.findAnomalyById(anomalyId);
    }

    private void requireChannel(String channelId) {
        if (!repository.channelExists(channelId)) {
            throw ApiException.notFound("CHANNEL_NOT_FOUND", "渠道不存在: " + channelId);
        }
    }

    private RuleVersionResponse toRuleResponse(ChannelRuleRow row) {
        return new RuleVersionResponse(row.id(), row.version(), fmt(row.maxFlowRate()),
                row.maxIntervalMinutes(), row.actor(), toIso(row.createdNanos()));
    }

    private ReadingResponse toReadingResponse(ReadingRow row) {
        return new ReadingResponse(row.id(), toIso(row.observedNanos()), fmt(row.cumulativeValue()),
                row.actor(), toIso(row.createdNanos()));
    }

    private AnomalyResponse toAnomalyResponse(AnomalyRow row, boolean released) {
        return new AnomalyResponse(row.id(), row.channelId(), row.ruleVersion(), row.readingId(), row.type(),
                fmt(row.actualValue()), fmt(row.requiredValue()), fmt(row.deltaValue()),
                toIso(row.prevObservedNanos()), fmt(row.prevValue()), toIso(row.readingObservedNanos()),
                fmt(row.readingValue()), row.readingActor(), released, toIso(row.createdNanos()));
    }

    private ReleaseResponse toReleaseResponse(ReleaseRow row, boolean channelFrozen) {
        return new ReleaseResponse(row.id(), row.anomalyId(), row.channelId(), row.diagnosis(),
                fmt(row.substituteValue()), toIso(row.substituteObservedNanos()), row.actor(),
                channelFrozen, toIso(row.createdNanos()));
    }

    private SettlementResponse toSettlementResponse(SettlementRow row, BigDecimal remainingHeld) {
        return new SettlementResponse(row.settlementKey(), row.allocationKey(), row.windowId(),
                row.channelId(), fmt(row.amount()),
                remainingHeld == null ? null : fmt(remainingHeld), row.actor(), toIso(row.createdNanos()));
    }

    /** 解析累计读数/替代读数：大于等于 0 的十进制字符串，最多 3 位小数，非法返回 422。 */
    static BigDecimal parseReadingValue(String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.unprocessable("INVALID_READING_VALUE", "读数值不能为空");
        }
        String trimmed = value.trim();
        if (!AMOUNT_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.unprocessable("INVALID_READING_VALUE",
                    "读数值必须为大于等于 0 的十进制字符串，最多 3 位小数，实际: " + value);
        }
        return new BigDecimal(trimmed);
    }

    /** 解析规则上限：大于 0 的十进制字符串，最多 3 位小数，非法返回 422。 */
    static BigDecimal parsePositiveAmount422(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.unprocessable("INVALID_RULE_PARAMETER", field + " 不能为空");
        }
        String trimmed = value.trim();
        if (!AMOUNT_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.unprocessable("INVALID_RULE_PARAMETER",
                    field + " 必须为大于 0 的十进制字符串，最多 3 位小数，实际: " + value);
        }
        BigDecimal amount = new BigDecimal(trimmed);
        if (amount.signum() <= 0) {
            throw ApiException.unprocessable("INVALID_RULE_PARAMETER",
                    field + " 必须大于 0，实际: " + value);
        }
        return amount;
    }

    /** 解析读数间隔上限（分钟）：1..5256000 的整数，非法返回 422。 */
    static int parseIntervalMinutes(Long value) {
        if (value == null || value < 1 || value > 5_256_000) {
            throw ApiException.unprocessable("INVALID_RULE_PARAMETER",
                    "maxIntervalMinutes 必须为 1..5256000 的整数，实际: " + value);
        }
        return value.intValue();
    }

    /** 幂等指纹 token 转义：\、|、, 前加反斜杠，保证集合拼接无歧义。 */
    private static String esc(String token) {
        StringBuilder sb = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '\\' || c == '|' || c == ',') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
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
