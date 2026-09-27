package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.AnomalyRow;
import com.example.starter.water.WaterRepository.ChannelConfigRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.ReadingRow;
import com.example.starter.water.WaterRepository.ResolutionRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.WaterRepository.WriteoffRow;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.AnomalyDetailResponse;
import com.example.starter.water.dto.Dtos.AnomalyResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.ChannelConfigResponse;
import com.example.starter.water.dto.Dtos.ChannelHistoryResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.ReadingInput;
import com.example.starter.water.dto.Dtos.ReadingResponse;
import com.example.starter.water.dto.Dtos.ReadingsResponse;
import com.example.starter.water.dto.Dtos.ResolutionResponse;
import com.example.starter.water.dto.Dtos.TransferListResponse;
import com.example.starter.water.dto.Dtos.TransferResponse;
import com.example.starter.water.dto.Dtos.WindowResponse;
import com.example.starter.water.dto.Dtos.WriteoffListResponse;
import com.example.starter.water.dto.Dtos.WriteoffResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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

    static final String ANOMALY_REGRESSION = "REGRESSION";
    static final String ANOMALY_FLOW_EXCEEDED = "FLOW_EXCEEDED";
    static final String ANOMALY_INTERVAL_EXCEEDED = "INTERVAL_EXCEEDED";
    static final String ANOMALY_OPEN = "OPEN";
    static final String ANOMALY_RESOLVED = "RESOLVED";

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long NANOS_PER_MINUTE = 60L * NANOS_PER_SECOND;
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("\\d{1,16}(\\.\\d{1,3})?");
    private static final Pattern KEY_PATTERN = Pattern.compile("[\\w.\\-:]{1,128}");
    private static final Pattern FLOW_RATE_PATTERN = Pattern.compile("\\d{1,13}(\\.\\d{1,6})?");
    private static final Pattern INTERVAL_PATTERN = Pattern.compile("\\d{1,6}(\\.\\d{1,3})?");
    private static final int MAX_DIAGNOSIS_LENGTH = 1000;

    private final WaterRepository repository;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private Clock clock;

    public WaterService(WaterRepository repository, PlatformTransactionManager transactionManager,
                        ObjectMapper objectMapper) {
        this.repository = repository;
        this.tx = new TransactionTemplate(transactionManager);
        // 指纹要求规范化、不依赖外部注入顺序；使用副本避免改动 Spring 容器共享 mapper
        this.objectMapper = objectMapper.copy().disable(SerializationFeature.INDENT_OUTPUT);
        this.clock = Clock.systemUTC();
    }

    /** 替换时钟（测试用可控时钟），仅供测试装配调用。 */
    public void setClock(Clock clock) {
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
        String fp = fingerprint("WINDOW_CREATE", "windowKey", windowKey, "channelId", channelId,
                "startNanos", startNanos, "endNanos", endNanos, "plannedVolume", planned.toPlainString());
        return runCommand("WINDOW_CREATE", commandKey, fp, WindowResponse.class, () -> {
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
        String fp = fingerprint("ALLOCATION_SUBMIT", "allocationKey", allocationKey, "windowId", windowId,
                "userId", userId, "amount", qty.toPlainString(), "requester", actor);
        return runCommand("ALLOCATION_SUBMIT", commandKey, fp, AllocationResponse.class, () -> {
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
        String fp = fingerprint("ALLOCATION_APPROVE", "allocationKey", allocationKey);
        return runCommand("ALLOCATION_APPROVE", commandKey, fp, AllocationResponse.class, () -> {
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
        String fp = fingerprint("ALLOCATION_CANCEL", "allocationKey", allocationKey, "requester", actor);
        return runCommand("ALLOCATION_CANCEL", commandKey, fp, AllocationResponse.class, () -> {
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
        String fp = fingerprint("TRANSFER", "transferKey", transferKey,
                "sourceAllocationKey", sourceAllocationKey, "targetAllocationKey", targetAllocationKey,
                "actor", actor);
        return runCommand("TRANSFER", commandKey, fp, TransferResponse.class, () -> {
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
        String fp = fingerprint("CURTAILMENT_CREATE", "windowId", windowId, "volume", qty.toPlainString());
        return runCommand("CURTAILMENT_CREATE", commandKey, fp, CurtailmentResponse.class, () -> {
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
        String fp = fingerprint("CURTAILMENT_CANCEL", "windowId", windowId);
        return runCommand("CURTAILMENT_CANCEL", commandKey, fp, CurtailmentResponse.class, () -> {
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
    // 渠道计量规则、读数、异常与实际核销
    // ------------------------------------------------------------------

    /**
     * 配置渠道计量异常规则：每次提交产生一个不可变新版本，版本号在渠道锁内按提交顺序递增。
     * 上限或间隔参数非法返回 422。
     */
    public ChannelConfigResponse configureChannel(String commandKey, String channelId, String maxFlowRate,
                                                  String maxIntervalMinutes, String effectiveFromUtc,
                                                  String actor) {
        requireKey("commandKey", commandKey);
        requireKey("channelId", channelId);
        requireKey("X-Actor-Id", actor);
        BigDecimal flowRate = parseRuleRate(maxFlowRate);
        BigDecimal maxInterval = parseRuleInterval(maxIntervalMinutes);
        long effectiveFrom = parseInstant("effectiveFromUtc", effectiveFromUtc);
        String fp = fingerprint("CHANNEL_CONFIG", "channelId", channelId, "actor", actor,
                "maxFlowRate", flowRate.toPlainString(), "maxIntervalMinutes", maxInterval.toPlainString(),
                "effectiveFromNanos", effectiveFrom);
        return runCommand("CHANNEL_CONFIG", commandKey, fp, ChannelConfigResponse.class, () -> {
            // 渠道行锁串行化同一渠道的规则配置、读数提交、异常解除与核销，按提交顺序裁决
            repository.insertChannelIfAbsent(channelId, nowNanos(), actor);
            repository.lockChannel(channelId);
            ChannelConfigRow latest = repository.lockLatestChannelConfig(channelId);
            int version = latest == null ? 1 : latest.version() + 1;
            long id;
            try {
                id = repository.insertChannelConfig(channelId, version, flowRate, maxInterval,
                        effectiveFrom, actor, nowNanos());
            } catch (DuplicateKeyException e) {
                // 并发创建同一版本：按提交顺序只允许一个成功
                throw ApiException.conflict("CHANNEL_CONFIG_CONCURRENT", "渠道规则版本并发冲突，请重试");
            }
            return toChannelConfigResponse(
                    repository.listChannelConfigs(channelId).stream()
                            .filter(c -> c.id() == id).findFirst().orElseThrow());
        });
    }

    /**
     * 提交一批渠道累计读数：整批先做完整序列校验（时刻严格递增、参数合法），
     * 任一错误整批不写入（422，事务回滚）；同渠道并发批次以最新规则行 FOR UPDATE 串行裁决。
     * 相邻读数倒退、瞬时流量超上限或间隔超设定分钟数时，每类各生成一条 OPEN 异常并冻结渠道新核销。
     */
    public ReadingsResponse submitReadings(String commandKey, String channelId, List<ReadingInput> readings,
                                           String actor) {
        requireKey("commandKey", commandKey);
        requireKey("channelId", channelId);
        requireKey("X-Actor-Id", actor);
        if (readings == null || readings.isEmpty()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "readings 不能为空批次");
        }
        // 先解析并规范化整批输入，非法格式直接 400，不进入业务事务
        List<ParsedReading> parsed = new ArrayList<>(readings.size());
        for (int i = 0; i < readings.size(); i++) {
            ReadingInput input = readings.get(i);
            if (input == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "readings[" + i + "] 不能为空");
            }
            long at = parseInstant("readings[" + i + "].readingAtUtc", input.readingAtUtc());
            BigDecimal value = parseNonNegativeAmount("readings[" + i + "].cumulativeReading",
                    input.cumulativeReading());
            parsed.add(new ParsedReading(at, value));
        }
        List<Map<String, Object>> normalized = new ArrayList<>(parsed.size());
        for (ParsedReading r : parsed) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("readingAtNanos", r.atNanos());
            item.put("cumulativeReading", r.value().toPlainString());
            normalized.add(item);
        }
        String fp = fingerprint("READINGS_SUBMIT", "channelId", channelId, "reporter", actor,
                "readings", normalized);
        return runCommand("READINGS_SUBMIT", commandKey, fp, ReadingsResponse.class, () -> {
            // 渠道行锁串行化同一渠道的读数批次、规则配置、异常解除与核销，按提交顺序裁决
            if (!repository.lockChannel(channelId)) {
                throw ApiException.unprocessable("CHANNEL_NOT_CONFIGURED",
                        "渠道尚未配置计量异常规则，不能提交读数: " + channelId);
            }
            ChannelConfigRow latestConfig = repository.lockLatestChannelConfig(channelId);
            if (latestConfig == null) {
                throw ApiException.unprocessable("CHANNEL_NOT_CONFIGURED",
                        "渠道尚未配置计量异常规则，不能提交读数: " + channelId);
            }
            // 锁内重放最新读数，得到本批次的序列前驱
            ReadingRow previous = repository.findLatestReading(channelId);
            // 第一阶段：对完整序列（渠道最新读数 + 本批全部读数）做校验，任一错误整批不写入
            List<EvaluatedReading> evaluated = new ArrayList<>(parsed.size());
            for (int i = 0; i < parsed.size(); i++) {
                ParsedReading current = parsed.get(i);
                if (previous != null && current.atNanos() <= previous.readingAtNanos()) {
                    BigDecimal behindSeconds = BigDecimal.valueOf(previous.readingAtNanos() - current.atNanos())
                            .divide(BigDecimal.valueOf(NANOS_PER_SECOND), 9, java.math.RoundingMode.HALF_UP);
                    throw ApiException.unprocessable("READING_NOT_ASCENDING",
                            "读数时刻必须严格递增：readings[" + i + "] 时刻 " + toIso(current.atNanos())
                                    + " 不晚于渠道最新读数时刻 " + toIso(previous.readingAtNanos())
                                    + "，要求晚于该时刻，差额 " + fmt(behindSeconds) + " 秒");
                }
                ChannelConfigRow rule = repository.findEffectiveChannelConfigAt(channelId, current.atNanos());
                if (rule == null) {
                    throw ApiException.unprocessable("RULE_NOT_EFFECTIVE",
                            "读数时刻 " + toIso(current.atNanos()) + " 尚无生效规则版本");
                }
                evaluated.add(new EvaluatedReading(current, rule));
                previous = new ReadingRow(0, channelId, current.atNanos(), current.value(), rule.version(),
                        actor, 0L);
            }

            // 第二阶段：序列全部合法后统一入库；异常只追加证据，不回滚本批已合法读数
            long now = nowNanos();
            previous = repository.findLatestReading(channelId);
            List<ReadingRow> stored = new ArrayList<>(evaluated.size());
            List<AnomalyRow> generated = new ArrayList<>();
            for (EvaluatedReading evaluatedReading : evaluated) {
                ParsedReading current = evaluatedReading.reading();
                ChannelConfigRow rule = evaluatedReading.rule();
                long readingId = repository.insertReading(channelId, current.atNanos(), current.value(),
                        rule.version(), actor, now);
                ReadingRow currentRow = repository.findReadingById(readingId);

                if (previous != null) {
                    long deltaNanos = current.atNanos() - previous.readingAtNanos();
                    BigDecimal deltaVolume = current.value().subtract(previous.cumulativeReading());
                    Long prevReadingId = previous.id();
                    // 1) 相邻读数倒退
                    if (deltaVolume.signum() < 0) {
                        BigDecimal difference = previous.cumulativeReading().subtract(current.value());
                        long anomalyId = repository.insertAnomaly(channelId, readingId, prevReadingId,
                                ANOMALY_REGRESSION, rule.version(),
                                previous.readingAtNanos(), current.atNanos(),
                                previous.readingAtNanos(), previous.cumulativeReading(),
                                current.atNanos(), current.value(),
                                current.value(), previous.cumulativeReading(), difference,
                                "CUMULATIVE_M3", actor, now);
                        generated.add(repository.findAnomalyById(anomalyId));
                    } else {
                        // 2) 瞬时流量超上限（倒退时流量为负，不构成该异常）
                        BigDecimal hours = BigDecimal.valueOf(deltaNanos)
                                .divide(BigDecimal.valueOf(3_600L * NANOS_PER_SECOND), 12,
                                        java.math.RoundingMode.HALF_UP);
                        BigDecimal flow = deltaVolume.divide(hours, 6, java.math.RoundingMode.HALF_UP);
                        if (flow.compareTo(rule.maxFlowRate()) > 0) {
                            BigDecimal difference = flow.subtract(rule.maxFlowRate());
                            long anomalyId = repository.insertAnomaly(channelId, readingId, prevReadingId,
                                    ANOMALY_FLOW_EXCEEDED, rule.version(),
                                    previous.readingAtNanos(), current.atNanos(),
                                    previous.readingAtNanos(), previous.cumulativeReading(),
                                    current.atNanos(), current.value(),
                                    flow, rule.maxFlowRate(), difference,
                                    "FLOW_M3_PER_H", actor, now);
                            generated.add(repository.findAnomalyById(anomalyId));
                        }
                    }
                    // 3) 读数间隔超设定分钟数（可与倒退/流量异常并存）
                    BigDecimal intervalMinutes = BigDecimal.valueOf(deltaNanos)
                            .divide(BigDecimal.valueOf(NANOS_PER_MINUTE), 6, java.math.RoundingMode.HALF_UP);
                    if (intervalMinutes.compareTo(rule.maxIntervalMinutes()) > 0) {
                        BigDecimal difference = intervalMinutes.subtract(rule.maxIntervalMinutes());
                        long anomalyId = repository.insertAnomaly(channelId, readingId, prevReadingId,
                                ANOMALY_INTERVAL_EXCEEDED, rule.version(),
                                previous.readingAtNanos(), current.atNanos(),
                                previous.readingAtNanos(), previous.cumulativeReading(),
                                current.atNanos(), current.value(),
                                intervalMinutes, rule.maxIntervalMinutes(), difference,
                                "INTERVAL_MINUTES", actor, now);
                        generated.add(repository.findAnomalyById(anomalyId));
                    }
                }
                stored.add(currentRow);
                previous = currentRow;
            }
            return new ReadingsResponse(channelId,
                    stored.stream().map(this::toReadingResponse).toList(),
                    generated.stream().map(a -> toAnomalyResponse(a, null)).toList());
        });
    }

    /**
     * 解除异常：解除人必须与异常读数提交人不同，须提供诊断说明与替代读数；
     * 解除只追加证据并把异常翻转一次为 RESOLVED，不改写原异常、原读数或既有核销。
     */
    public AnomalyDetailResponse resolveAnomaly(String commandKey, long anomalyId, String diagnosisNote,
                                                String replacementAtUtc, String replacementValue,
                                                String actor) {
        requireKey("commandKey", commandKey);
        requireKey("X-Actor-Id", actor);
        if (diagnosisNote == null || diagnosisNote.isBlank()) {
            throw ApiException.unprocessable("DIAGNOSIS_REQUIRED", "诊断说明不能为空");
        }
        String note = diagnosisNote.trim();
        if (note.length() > MAX_DIAGNOSIS_LENGTH) {
            throw ApiException.unprocessable("DIAGNOSIS_TOO_LONG",
                    "诊断说明最长 " + MAX_DIAGNOSIS_LENGTH + " 字符，当前 " + note.length());
        }
        long replacementAt = parseInstant("replacementAtUtc", replacementAtUtc);
        BigDecimal replacement = parseNonNegativeAmount("replacementValue", replacementValue);
        String fp = fingerprint("ANOMALY_RESOLVE", "anomalyId", anomalyId, "resolver", actor,
                "diagnosisNote", note, "replacementAtNanos", replacementAt,
                "replacementValue", replacement.toPlainString());
        return runCommand("ANOMALY_RESOLVE", commandKey, fp, AnomalyDetailResponse.class, () -> {
            AnomalyRow anomaly = repository.findAnomalyById(anomalyId);
            if (anomaly == null) {
                throw ApiException.notFound("ANOMALY_NOT_FOUND", "传感异常不存在: " + anomalyId);
            }
            // 与读数提交、核销在渠道行锁上串行，保证冻结状态按提交顺序裁决
            repository.lockChannel(anomaly.channelId());
            anomaly = repository.lockAnomalyById(anomalyId);
            if (anomaly == null) {
                throw ApiException.notFound("ANOMALY_NOT_FOUND", "传感异常不存在: " + anomalyId);
            }
            if (ANOMALY_RESOLVED.equals(anomaly.status())) {
                throw ApiException.conflict("ANOMALY_ALREADY_RESOLVED",
                        "异常已解除，解除证据不可改写: " + anomalyId);
            }
            if (anomaly.reporter().equals(actor)) {
                throw ApiException.conflict("SAME_OPERATOR",
                        "异常解除必须由不同于读数提交人 " + anomaly.reporter() + " 的操作人确认");
            }
            if (anomaly.prevValue() != null && replacement.compareTo(anomaly.prevValue()) < 0) {
                throw ApiException.unprocessable("REPLACEMENT_BELOW_PREVIOUS",
                        "替代读数 " + fmt(replacement) + " 不得低于前一条读数 "
                                + fmt(anomaly.prevValue()) + "，差额 "
                                + fmt(anomaly.prevValue().subtract(replacement)));
            }
            long now = nowNanos();
            long resolutionId;
            try {
                resolutionId = repository.insertResolution(anomalyId, actor, note, replacementAt,
                        replacement, now);
            } catch (DuplicateKeyException e) {
                // 并发解除：唯一约束保证一条异常至多一条解除
                throw ApiException.conflict("ANOMALY_ALREADY_RESOLVED",
                        "异常已被并发解除: " + anomalyId);
            }
            if (!repository.resolveAnomaly(anomalyId, resolutionId)) {
                throw ApiException.conflict("ANOMALY_ALREADY_RESOLVED",
                        "异常已被并发解除: " + anomalyId);
            }
            AnomalyRow refreshed = repository.findAnomalyById(anomalyId);
            ResolutionRow resolution = repository.findResolutionByAnomalyId(anomalyId);
            return new AnomalyDetailResponse(toAnomalyResponse(refreshed, resolution),
                    toResolutionResponse(resolution));
        });
    }

    /**
     * 实际核销：对窗口内已批准申请的持有额度等额扣减并写不可变流水。
     * 渠道存在 OPEN 异常（冻结中）时同刻后提交的核销被拦截（422）；冻结不影响额度转让登记。
     */
    public WriteoffResponse writeoff(String commandKey, String writeoffKey, String allocationKey,
                                     String amount, String atUtc, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("writeoffKey", writeoffKey);
        requireKey("allocationKey", allocationKey);
        requireKey("X-Actor-Id", actor);
        BigDecimal qty = parseAmount("amount", amount);
        long atNanos = parseInstant("atUtc", atUtc);
        String fp = fingerprint("WRITEOFF", "writeoffKey", writeoffKey, "allocationKey", allocationKey,
                "amount", qty.toPlainString(), "actor", actor, "atNanos", atNanos);
        return runCommand("WRITEOFF", commandKey, fp, WriteoffResponse.class, () -> {
            if (repository.findWriteoffByKey(writeoffKey) != null) {
                throw ApiException.conflict("WRITEOFF_KEY_REUSED", "writeoffKey 已被使用: " + writeoffKey);
            }
            AllocationRow allocation = repository.findAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            WindowRow window = lockWindowOf(allocation);
            // 渠道行锁与异常生成/解除串行：异常先提交则本核销被拦截
            repository.lockChannel(window.channelId());
            allocation = repository.lockAllocationByKey(allocationKey);
            if (atNanos < window.startNanos() || atNanos >= window.endNanos()) {
                throw ApiException.unprocessable("WRITEOFF_OUTSIDE_WINDOW",
                        "核销时刻 " + toIso(atNanos) + " 不在窗口 [" + toIso(window.startNanos()) + ", "
                                + toIso(window.endNanos()) + ") 内");
            }
            List<AnomalyRow> open = repository.listOpenAnomalies(window.channelId());
            if (!open.isEmpty()) {
                AnomalyRow first = open.get(0);
                throw ApiException.unprocessable("CHANNEL_FROZEN",
                        "渠道 " + window.channelId() + " 存在 " + open.size() + " 条未解除异常，核销已冻结；"
                                + "最早异常 #" + first.id() + "（" + first.type() + "，"
                                + toIso(first.createdNanos()) + "）须先由不同操作人解除");
            }
            if (!STATUS_APPROVED.equals(allocation.status())) {
                throw ApiException.conflict("ALLOCATION_NOT_APPROVED",
                        "只有 APPROVED 状态的申请可以核销，当前状态: " + allocation.status());
            }
            if (allocation.heldAmount().compareTo(qty) < 0) {
                throw ApiException.quotaExceeded("申请当前持有额度 " + fmt(allocation.heldAmount())
                        + " 不足，无法核销 " + fmt(qty) + "，差额 "
                        + fmt(qty.subtract(allocation.heldAmount())));
            }
            long now = nowNanos();
            BigDecimal heldAfter = allocation.heldAmount().subtract(qty);
            try {
                repository.insertWriteoff(writeoffKey, window.id(), window.channelId(), allocationKey,
                        qty, heldAfter, actor, atNanos, now);
            } catch (DuplicateKeyException e) {
                // 并发复用同一 writeoffKey（换 commandKey）：事务回滚，额度无变化
                throw ApiException.conflict("WRITEOFF_KEY_REUSED", "writeoffKey 已被使用: " + writeoffKey);
            }
            repository.decrementHeldAmount(allocation.id(), qty, now);
            WriteoffRow row = repository.findWriteoffByKey(writeoffKey);
            AllocationRow after = repository.findAllocationByKey(allocationKey);
            return toWriteoffResponse(row, after.heldAmount());
        });
    }

    /** 查询渠道读数、异常与规则版本历史（只读，排序稳定）。 */
    public ChannelHistoryResponse getChannelHistory(String channelId) {
        if (!repository.existsChannelConfig(channelId)) {
            throw ApiException.notFound("CHANNEL_NOT_FOUND", "渠道不存在或未配置计量规则: " + channelId);
        }
        List<ReadingResponse> readings = repository.listReadings(channelId).stream()
                .map(this::toReadingResponse).toList();
        List<ChannelConfigResponse> versions = repository.listChannelConfigs(channelId).stream()
                .map(this::toChannelConfigResponse).toList();
        List<AnomalyResponse> anomalies = repository.listAnomalies(channelId).stream()
                .map(a -> toAnomalyResponse(a,
                        ANOMALY_RESOLVED.equals(a.status())
                                ? repository.findResolutionByAnomalyId(a.id()) : null))
                .toList();
        return new ChannelHistoryResponse(channelId, readings, anomalies, versions);
    }

    /** 查询单条异常诊断明细（只读）：异常证据与解除证据（未解除时 resolution 为 null）。 */
    public AnomalyDetailResponse getAnomalyDetail(long anomalyId) {
        AnomalyRow anomaly = repository.findAnomalyById(anomalyId);
        if (anomaly == null) {
            throw ApiException.notFound("ANOMALY_NOT_FOUND", "传感异常不存在: " + anomalyId);
        }
        ResolutionRow resolution = ANOMALY_RESOLVED.equals(anomaly.status())
                ? repository.findResolutionByAnomalyId(anomalyId) : null;
        return new AnomalyDetailResponse(toAnomalyResponse(anomaly, resolution),
                resolution == null ? null : toResolutionResponse(resolution));
    }

    /** 查询窗口全部实际核销流水（只读，按提交顺序）。 */
    public WriteoffListResponse getWriteoffs(long windowId) {
        WindowRow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        List<WriteoffResponse> writeoffs = repository.listWriteoffs(windowId).stream()
                .map(w -> toWriteoffResponse(w, w.heldAfter())).toList();
        return new WriteoffListResponse(windowId, writeoffs);
    }

    // ------------------------------------------------------------------
    // 幂等命令框架
    // ------------------------------------------------------------------

    /**
     * 在单事务内执行幂等命令：先占位插入命令行，再执行业务并写回响应。
     * 并发同键时占位插入会因唯一约束失败，随后读取已提交的首次结果重放或报 409。
     *
     * @param fingerprint 规范化请求指纹：覆盖操作类型、操作者、规范化集合与全部结果相关字段，
     *                    以结构化 JSON 承载（集合为 JSON 数组，不做裸分隔符拼接）
     */
    private <T> T runCommand(String operation, String commandKey, String fingerprint, Class<T> type,
                             Supplier<T> business) {
        try {
            return tx.execute(status -> {
                CommandRow existing = repository.findCommand(commandKey);
                if (existing != null) {
                    return replay(existing, operation, fingerprint, type);
                }
                repository.insertCommand(commandKey, operation, fingerprint, nowNanos());
                T result = business.get();
                repository.updateCommandResponse(commandKey, toJson(result));
                return result;
            });
        } catch (DuplicateKeyException e) {
            CommandRow committed = repository.findCommand(commandKey);
            if (committed == null || committed.response() == null) {
                throw ApiException.conflict("COMMAND_CONFLICT", "相同 commandKey 的命令正在处理，请重试");
            }
            return replay(committed, operation, fingerprint, type);
        }
    }

    private <T> T replay(CommandRow existing, String operation, String fingerprint, Class<T> type) {
        if (!existing.operation().equals(operation) || !existing.params().equals(fingerprint)) {
            throw ApiException.conflict("COMMAND_KEY_REUSED", "相同 commandKey 但参数不同，拒绝重放");
        }
        return fromJson(existing.response(), type);
    }

    /**
     * 构建规范化命令指纹：固定字段顺序的 JSON 对象，内嵌操作类型；
     * 集合参数（如批量读数）以 JSON 数组表达，绝不直接以未转义分隔符拼接。
     */
    private String fingerprint(String operation, Object... orderedKeyValues) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("operation", operation);
        for (int i = 0; i + 1 < orderedKeyValues.length; i += 2) {
            root.put((String) orderedKeyValues[i], orderedKeyValues[i + 1]);
        }
        return toJson(root);
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

    private ChannelConfigResponse toChannelConfigResponse(ChannelConfigRow row) {
        return new ChannelConfigResponse(row.id(), row.channelId(), row.version(), fmt6(row.maxFlowRate()),
                fmt(row.maxIntervalMinutes()), toIso(row.effectiveFromNanos()), row.actor(),
                toIso(row.createdNanos()));
    }

    private ReadingResponse toReadingResponse(ReadingRow row) {
        return new ReadingResponse(row.id(), row.channelId(), toIso(row.readingAtNanos()),
                fmt(row.cumulativeReading()), row.ruleVersion(), row.reporter(), toIso(row.createdNanos()));
    }

    private ResolutionResponse toResolutionResponse(ResolutionRow row) {
        if (row == null) {
            return null;
        }
        return new ResolutionResponse(row.id(), row.anomalyId(), row.resolver(), row.diagnosisNote(),
                toIso(row.replacementAtNanos()), fmt(row.replacementValue()), toIso(row.createdNanos()));
    }

    private AnomalyResponse toAnomalyResponse(AnomalyRow row, ResolutionRow resolution) {
        // 两分支均保持 Long 包装类型，避免 resolutionId 为 null 时三元表达式触发拆箱 NPE
        Long resolutionId = resolution == null ? row.resolutionId() : Long.valueOf(resolution.id());
        return new AnomalyResponse(row.id(), row.channelId(), row.type(), row.ruleVersion(),
                row.windowStartNanos() == null ? null : toIso(row.windowStartNanos()),
                toIso(row.windowEndNanos()),
                row.prevReadingAtNanos() == null ? null : toIso(row.prevReadingAtNanos()),
                row.prevValue() == null ? null : fmt(row.prevValue()),
                toIso(row.readingAtNanos()), fmt(row.readingValue()),
                fmt6(row.actualValue()), fmt6(row.requiredValue()), fmt6(row.differenceValue()),
                row.metric(), row.reporter(), row.status(), resolutionId,
                toIso(row.createdNanos()), toResolutionResponse(resolution));
    }

    private WriteoffResponse toWriteoffResponse(WriteoffRow row, BigDecimal heldAmountAfter) {
        return new WriteoffResponse(row.writeoffKey(), row.windowId(), row.channelId(),
                row.allocationKey(), fmt(row.amount()), row.actor(), toIso(row.atNanos()),
                fmt(heldAmountAfter), toIso(row.createdNanos()));
    }

    /** 批量读数解析后的规范化输入。 */
    private record ParsedReading(long atNanos, BigDecimal value) {
    }

    /** 批量读数第一阶段校验结果：规范化输入与其时刻适用的规则版本。 */
    private record EvaluatedReading(ParsedReading reading, ChannelConfigRow rule) {
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
        BigDecimal amount = parseDecimal(field, value, AMOUNT_PATTERN);
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 必须大于 0");
        }
        return amount;
    }

    /** 解析非负水量（累计读数、替代读数允许为 0），最多 3 位小数。 */
    static BigDecimal parseNonNegativeAmount(String field, String value) {
        BigDecimal amount = parseDecimal(field, value, AMOUNT_PATTERN);
        if (amount.signum() < 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不能为负数");
        }
        return amount;
    }

    /**
     * 解析渠道瞬时流量上限（立方米/小时）：大于 0，最多 6 位小数。
     * 按题意上限参数非法返回 422 UNPROCESSABLE_ENTITY。
     */
    static BigDecimal parseRuleRate(String value) {
        BigDecimal rate = parseRuleDecimal("maxFlowRate", value, FLOW_RATE_PATTERN);
        if (rate.signum() <= 0) {
            throw ApiException.unprocessable("INVALID_RULE_PARAM", "maxFlowRate 必须大于 0（立方米/小时）");
        }
        return rate;
    }

    /**
     * 解析相邻读数最大允许间隔（分钟）：大于 0，最多 3 位小数。
     * 按题意间隔参数非法返回 422 UNPROCESSABLE_ENTITY。
     */
    static BigDecimal parseRuleInterval(String value) {
        BigDecimal minutes = parseRuleDecimal("maxIntervalMinutes", value, INTERVAL_PATTERN);
        if (minutes.signum() <= 0) {
            throw ApiException.unprocessable("INVALID_RULE_PARAM", "maxIntervalMinutes 必须大于 0（分钟）");
        }
        return minutes;
    }

    private static BigDecimal parseDecimal(String field, String value, Pattern pattern) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不能为空");
        }
        String trimmed = value.trim();
        if (!pattern.matcher(trimmed).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 必须为十进制字符串（最多 "
                            + (pattern == FLOW_RATE_PATTERN ? 6 : 3) + " 位小数）: " + value);
        }
        return new BigDecimal(trimmed);
    }

    private static BigDecimal parseRuleDecimal(String field, String value, Pattern pattern) {
        if (value == null || value.isBlank()) {
            throw ApiException.unprocessable("INVALID_RULE_PARAM", field + " 不能为空");
        }
        String trimmed = value.trim();
        if (!pattern.matcher(trimmed).matches()) {
            throw ApiException.unprocessable("INVALID_RULE_PARAM",
                    field + " 必须为大于 0 的十进制字符串，最多 "
                            + (pattern == FLOW_RATE_PATTERN ? 6 : 3) + " 位小数: " + value);
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

    /** 流量/间隔等最多 6 位小数字段的格式化，保留计算精度并去掉多余尾零。 */
    private static String fmt6(BigDecimal value) {
        return fmt(value);
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
