package com.example.starter.water;

import com.example.starter.water.SensorRepository.AnomalyRow;
import com.example.starter.water.SensorRepository.ReadingRow;
import com.example.starter.water.SensorRepository.ResolutionRow;
import com.example.starter.water.SensorRepository.SensorRuleRow;
import com.example.starter.water.SensorRepository.WriteOffRow;
import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.dto.Dtos.AnomalyResponse;
import com.example.starter.water.dto.Dtos.ChannelDiagnosticsResponse;
import com.example.starter.water.dto.Dtos.ChannelReadingsResponse;
import com.example.starter.water.dto.Dtos.ReadingBatchResponse;
import com.example.starter.water.dto.Dtos.ReadingItem;
import com.example.starter.water.dto.Dtos.ReadingResponse;
import com.example.starter.water.dto.Dtos.ReplacementReadingResponse;
import com.example.starter.water.dto.Dtos.ResolutionResponse;
import com.example.starter.water.dto.Dtos.SensorRuleResponse;
import com.example.starter.water.dto.Dtos.WriteOffListResponse;
import com.example.starter.water.dto.Dtos.WriteOffResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 配水计量传感异常与核销业务服务。
 *
 * <p>幂等：复用 command_log，同键同参重放返回首次完整响应快照，同键异参 409，失败回滚不占键。
 * 指纹为结构化规范化 JSON（数组/对象经 JSON 转义，不做裸分隔符拼接），包含操作者、操作类型、
 * 规范化对象集合及全部影响结果的字段。</p>
 *
 * <p>并发裁决：读数批次、核销与（首次）规则配置在事务内先锁定 channel 行 FOR UPDATE，按事务提交
 * 顺序串行；异常先提交则同刻后提交的核销被拦截。整批读数先完整校验序列再在同一事务写入读数、
 * 异常与冻结，任一错误整批回滚、查不到半成品。</p>
 */
@Service
public class SensorService {

    static final String REASON_REGRESSION = "REGRESSION";
    static final String REASON_FLOW_EXCEEDED = "FLOW_EXCEEDED";
    static final String REASON_GAP_EXCEEDED = "GAP_EXCEEDED";

    private static final String OP_RULE = "SENSOR_RULE_CONFIGURE";
    private static final String OP_READINGS = "READING_SUBMIT";
    private static final String OP_RESOLVE = "ANOMALY_RESOLVE";
    private static final String OP_WRITE_OFF = "WRITE_OFF";

    private static final long NANOS_PER_MINUTE = 60_000_000_000L;
    private static final Pattern RATE_PATTERN = Pattern.compile("\\d{1,16}(\\.\\d{1,6})?");
    private static final Pattern CUMULATIVE_PATTERN = Pattern.compile("\\d{1,16}(\\.\\d{1,3})?");
    private static final int RATE_SCALE = 6;

    private final SensorRepository sensorRepository;
    private final WaterRepository waterRepository;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public SensorService(SensorRepository sensorRepository, WaterRepository waterRepository,
                         PlatformTransactionManager transactionManager, ObjectMapper objectMapper) {
        this.sensorRepository = sensorRepository;
        this.waterRepository = waterRepository;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 规则版本
    // ------------------------------------------------------------------

    /** 配置渠道传感规则：参数非法返回 422；每次提交生成不可变新版本，版本号渠道内递增。 */
    public SensorRuleResponse configureRule(String commandKey, String channelId, String maxFlowRate,
                                            Integer maxGapMinutes, String actor) {
        WaterService.requireKey("commandKey", commandKey);
        WaterService.requireKey("channelId", channelId);
        WaterService.requireKey("X-Actor-Id", actor);
        BigDecimal rate = parseRuleRate(maxFlowRate);
        int gap = parseRuleGap(maxGapMinutes);
        String fingerprint = fingerprint(List.of(OP_RULE, channelId, rate.toPlainString(), gap, actor));
        return runCommand(OP_RULE, commandKey, fingerprint, SensorRuleResponse.class, () -> {
            SensorRepository.ChannelRow channel = lockOrCreateChannel(channelId);
            SensorRuleRow latest = sensorRepository.findLatestSensorRule(channelId);
            int version = (latest == null ? 0 : latest.ruleVersion()) + 1;
            long now = WaterService.nowNanos();
            long id = sensorRepository.insertSensorRule(channelId, version, rate, gap, actor, now);
            return toRuleResponse(sensorRepository.findSensorRuleById(id));
        });
    }

    // ------------------------------------------------------------------
    // 读数批次与异常/冻结
    // ------------------------------------------------------------------

    /**
     * 提交一批累计读数：先完整解析并校验批内序列严格递增（不递增返回 422），
     * 事务内锁定渠道、与历史末点逐对裁决（倒退/超瞬时流量/超间隔），全部读数与命中的异常、
     * 冻结在同一事务提交；任一错误整批不写入。
     */
    public ReadingBatchResponse submitReadings(String commandKey, String batchKey, String channelId,
                                               List<ReadingItem> readings, String actor) {
        WaterService.requireKey("commandKey", commandKey);
        WaterService.requireKey("batchKey", batchKey);
        WaterService.requireKey("channelId", channelId);
        WaterService.requireKey("X-Actor-Id", actor);
        if (readings == null || readings.isEmpty()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "readings 不能为空，每批至少一条读数");
        }
        // 先解析整批（格式错误 400），再按时刻规范化排序得到规范化对象集合
        List<ParsedReading> parsed = new ArrayList<>();
        List<List<Object>> canonicalItems = new ArrayList<>();
        for (ReadingItem item : readings) {
            if (item == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "读数项不能为空");
            }
            long t = WaterService.parseInstant("readingUtc", item.readingUtc());
            BigDecimal cumulative = parseCumulative(item.cumulative());
            parsed.add(new ParsedReading(t, cumulative));
            canonicalItems.add(List.of(t, cumulative.toPlainString()));
        }
        canonicalItems.sort((a, b) -> {
            int cmp = Long.compare((Long) a.get(0), (Long) b.get(0));
            return cmp != 0 ? cmp : ((String) a.get(1)).compareTo((String) b.get(1));
        });
        // 业务校验与写入按提交序列进行：时刻必须按提交顺序严格递增（相等或倒退均 422），不做自动重排
        for (int i = 1; i < parsed.size(); i++) {
            if (parsed.get(i).readingNanos() <= parsed.get(i - 1).readingNanos()) {
                throw ApiException.unprocessable("READING_TIME_NOT_INCREMENTED",
                        "读数时刻必须严格递增: " + WaterService.toIso(parsed.get(i - 1).readingNanos())
                                + " -> " + WaterService.toIso(parsed.get(i).readingNanos()));
            }
        }
        String fingerprint = fingerprint(List.of(OP_READINGS, batchKey, channelId, actor, canonicalItems));
        return runCommand(OP_READINGS, commandKey, fingerprint, ReadingBatchResponse.class, () -> {
            SensorRepository.ChannelRow channel = sensorRepository.lockChannel(channelId);
            if (channel == null) {
                throw ApiException.notFound("CHANNEL_NOT_FOUND", "渠道不存在或尚未配置传感规则: " + channelId);
            }
            SensorRuleRow rule = sensorRepository.findLatestSensorRule(channelId);
            if (rule == null) {
                throw ApiException.unprocessable("SENSOR_RULE_NOT_CONFIGURED",
                        "渠道尚未配置传感异常规则，不能提交读数: " + channelId);
            }
            if (!sensorRepository.listReadingsByBatch(batchKey).isEmpty()) {
                throw ApiException.conflict("BATCH_KEY_REUSED", "batchKey 已被使用: " + batchKey);
            }
            long now = WaterService.nowNanos();
            // 逐对裁决：读数为追加式时间序列，游标从渠道最新读数开始（新批次首点必须更晚）
            ReadingRow previous = sensorRepository.findLatestReading(channelId);
            List<ReadingRow> inserted = new ArrayList<>();
            for (int seq = 0; seq < parsed.size(); seq++) {
                ParsedReading curr = parsed.get(seq);
                if (previous != null && curr.readingNanos() <= previous.readingNanos()) {
                    // 并发下历史末点时刻不早于本批读数：时刻不递增
                    throw ApiException.unprocessable("READING_TIME_NOT_INCREMENTED",
                            "读数时刻必须严格递增，渠道已存在时刻 "
                                    + WaterService.toIso(previous.readingNanos()) + " 的读数");
                }
                long readingId;
                try {
                    readingId = sensorRepository.insertReading(batchKey, channelId, seq, curr.readingNanos(),
                            curr.cumulative(), rule.ruleVersion(), actor, now);
                } catch (DuplicateKeyException e) {
                    // 并发批次提交了同一时刻读数
                    throw ApiException.unprocessable("READING_TIME_NOT_INCREMENTED",
                            "渠道已存在相同时刻 " + WaterService.toIso(curr.readingNanos()) + " 的读数");
                }
                ReadingRow current = sensorRepository.findReadingById(readingId);
                if (previous != null) {
                    evaluatePair(rule, previous, current, batchKey, actor, now);
                }
                inserted.add(current);
                previous = current;
            }
            List<AnomalyResponse> anomalies = sensorRepository.listAnomaliesByChannel(channelId).stream()
                    .filter(a -> batchKey.equals(a.batchKey()))
                    .map(this::toAnomalyResponse).toList();
            List<ReadingResponse> readingResponses = inserted.stream().map(this::toReadingResponse).toList();
            // frozen 反映提交后渠道真实冻结状态（本批新异常或历史未解除异常都会持续冻结）
            return new ReadingBatchResponse(batchKey, channelId, readingResponses, anomalies,
                    sensorRepository.hasOpenAnomaly(channelId), WaterService.toIso(now));
        });
    }

    /** 裁决相邻读数对，命中任一规则即生成不可变异常并冻结渠道。 */
    private void evaluatePair(SensorRuleRow rule, ReadingRow previous, ReadingRow current, String batchKey,
                              String actor, long now) {
        BigDecimal delta = current.cumulative().subtract(previous.cumulative());
        BigDecimal gapMinutes = BigDecimal.valueOf(current.readingNanos() - previous.readingNanos())
                .divide(BigDecimal.valueOf(NANOS_PER_MINUTE), RATE_SCALE, RoundingMode.HALF_UP);
        BigDecimal flowRate = gapMinutes.signum() == 0
                ? null
                : delta.divide(gapMinutes, RATE_SCALE, RoundingMode.HALF_UP);
        List<String> reasons = new ArrayList<>();
        if (delta.signum() < 0) {
            reasons.add(REASON_REGRESSION);
        }
        if (flowRate != null && flowRate.compareTo(rule.maxFlowRate()) > 0) {
            reasons.add(REASON_FLOW_EXCEEDED);
        }
        if (gapMinutes.compareTo(BigDecimal.valueOf(rule.maxGapMinutes())) > 0) {
            reasons.add(REASON_GAP_EXCEEDED);
        }
        if (reasons.isEmpty()) {
            return;
        }
        AnomalyRow row = new AnomalyRow(0L, current.channelId(), batchKey, rule.ruleVersion(),
                previous.id(), current.id(), previous.readingNanos(), current.readingNanos(),
                previous.cumulative(), current.cumulative(), toJson(reasons),
                delta.setScale(3, RoundingMode.HALF_UP), gapMinutes,
                flowRate == null ? BigDecimal.ZERO.setScale(RATE_SCALE) : flowRate,
                rule.maxGapMinutes(), rule.maxFlowRate(), actor, now);
        long anomalyId = sensorRepository.insertAnomaly(row);
        // 每条异常对应一次不可变冻结；全部解除前渠道持续拦截新核销
        sensorRepository.insertFreeze(current.channelId(), anomalyId, now);
    }

    // ------------------------------------------------------------------
    // 异常解除
    // ------------------------------------------------------------------

    /** 解除异常：操作人必须不同于异常提交人，确认诊断说明与替代读数；解除不改写原异常或既有核销。 */
    public AnomalyResponse resolveAnomaly(String commandKey, long anomalyId, String diagnosisNote,
                                          String replacementCumulative, String actor) {
        WaterService.requireKey("commandKey", commandKey);
        WaterService.requireKey("X-Actor-Id", actor);
        if (diagnosisNote == null || diagnosisNote.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "diagnosisNote 不能为空");
        }
        String note = diagnosisNote.trim();
        if (note.length() > 1024) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "diagnosisNote 最长 1024 字符");
        }
        BigDecimal replacement = parseCumulative(replacementCumulative);
        String fingerprint = fingerprint(List.of(OP_RESOLVE, anomalyId, actor, note, replacement.toPlainString()));
        return runCommand(OP_RESOLVE, commandKey, fingerprint, AnomalyResponse.class, () -> {
            AnomalyRow found = sensorRepository.findAnomalyById(anomalyId);
            if (found == null) {
                throw ApiException.notFound("ANOMALY_NOT_FOUND", "传感异常不存在: " + anomalyId);
            }
            // 先锁渠道行：与读数提交、核销在同一把渠道行锁上按事务提交顺序串行裁决
            sensorRepository.lockChannelOrThrow(found.channelId());
            AnomalyRow anomaly = sensorRepository.lockAnomalyById(anomalyId);
            if (anomaly == null) {
                throw ApiException.notFound("ANOMALY_NOT_FOUND", "传感异常不存在: " + anomalyId);
            }
            if (sensorRepository.findResolutionByAnomaly(anomalyId) != null) {
                throw ApiException.conflict("ANOMALY_ALREADY_RESOLVED", "异常已解除，不能重复解除: " + anomalyId);
            }
            if (anomaly.detectorActor().equals(actor)) {
                throw ApiException.conflict("RESOLVER_SAME_AS_DETECTOR",
                        "解除操作人必须不同于异常提交操作人: " + actor);
            }
            long now = WaterService.nowNanos();
            // 替代读数时刻固定为异常后一读数时刻，作为独立诊断证据，不改写原读数序列
            sensorRepository.insertResolution(anomalyId, anomaly.channelId(), note, replacement,
                    anomaly.toReadingNanos(), actor, now);
            return toAnomalyResponse(sensorRepository.findAnomalyById(anomalyId));
        });
    }

    // ------------------------------------------------------------------
    // 实际核销
    // ------------------------------------------------------------------

    /**
     * 实际核销：渠道存在未解除异常时返回 422 CHANNEL_FROZEN；核销额超过当前剩余可核销额度返回 422。
     * 窗口锁→渠道锁→申请行锁顺序加锁，与转让/取消及读数异常按事务提交顺序串行裁决。
     */
    public WriteOffResponse writeOff(String commandKey, String writeOffKey, String allocationKey,
                                     String amount, String atUtc, String actor) {
        WaterService.requireKey("commandKey", commandKey);
        WaterService.requireKey("writeOffKey", writeOffKey);
        WaterService.requireKey("allocationKey", allocationKey);
        WaterService.requireKey("X-Actor-Id", actor);
        BigDecimal qty = WaterService.parseAmount("amount", amount);
        long atNanos = WaterService.parseInstant("atUtc", atUtc);
        String fingerprint = fingerprint(List.of(OP_WRITE_OFF, writeOffKey, allocationKey,
                qty.toPlainString(), atNanos, actor));
        return runCommand(OP_WRITE_OFF, commandKey, fingerprint, WriteOffResponse.class, () -> {
            if (sensorRepository.findWriteOffByKey(writeOffKey) != null) {
                throw ApiException.conflict("WRITE_OFF_KEY_REUSED", "writeOffKey 已被使用: " + writeOffKey);
            }
            AllocationRow allocation = waterRepository.findAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            WindowRow window = waterRepository.lockWindowById(allocation.windowId());
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + allocation.windowId());
            }
            // 渠道锁：与读数批次的异常生成/冻结按提交顺序串行，异常先提交则本核销被拦截
            sensorRepository.lockChannelOrThrow(window.channelId());
            AllocationRow locked = waterRepository.lockAllocationByKey(allocationKey);
            if (!WaterService.STATUS_APPROVED.equals(locked.status())) {
                throw ApiException.conflict("ALLOCATION_NOT_APPROVED",
                        "只有 APPROVED 状态的申请可以核销，当前状态: " + locked.status());
            }
            if (sensorRepository.hasOpenAnomaly(window.channelId())) {
                List<AnomalyRow> open = sensorRepository.listAnomaliesByChannel(window.channelId()).stream()
                        .filter(a -> sensorRepository.findResolutionByAnomaly(a.id()) == null).toList();
                long earliest = open.stream().mapToLong(AnomalyRow::id).min().orElse(0);
                throw ApiException.unprocessable("CHANNEL_FROZEN",
                        "渠道 " + window.channelId() + " 存在 " + open.size() + " 个未解除异常，"
                                + "新核销被冻结拦截，最早异常 id=" + earliest);
            }
            BigDecimal written = sensorRepository.sumWriteOffs(allocationKey);
            BigDecimal remaining = locked.heldAmount();
            if (qty.compareTo(remaining) > 0) {
                throw ApiException.unprocessable("WRITE_OFF_EXCEEDS_AVAILABLE",
                        "本次核销 " + WaterService.fmt(qty) + " 立方米超过当前剩余可核销额度 "
                                + WaterService.fmt(remaining) + " 立方米，差额 "
                                + WaterService.fmt(qty.subtract(remaining)) + " 立方米");
            }
            long now = WaterService.nowNanos();
            waterRepository.decrementHeldAmount(locked.id(), qty, now);
            try {
                sensorRepository.insertWriteOff(writeOffKey, allocationKey, window.id(),
                        window.channelId(), qty, atNanos, actor, now);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("WRITE_OFF_KEY_REUSED", "writeOffKey 已被使用: " + writeOffKey);
            }
            return toWriteOffResponse(sensorRepository.findWriteOffByKey(writeOffKey));
        });
    }

    // ------------------------------------------------------------------
    // 查询：明细 / 历史 / 诊断
    // ------------------------------------------------------------------

    /** 查询申请核销明细：核销基数（当前持有 + 已核销）、实际已核销总量与当前剩余。 */
    public WriteOffListResponse getWriteOffs(String allocationKey) {
        AllocationRow allocation = waterRepository.findAllocationByKey(allocationKey);
        if (allocation == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
        }
        WindowRow window = waterRepository.findWindowById(allocation.windowId());
        List<WriteOffResponse> rows = sensorRepository.listWriteOffsByAllocation(allocationKey).stream()
                .map(this::toWriteOffResponse).toList();
        BigDecimal written = sensorRepository.sumWriteOffs(allocationKey);
        BigDecimal available = allocation.heldAmount().add(written);
        return new WriteOffListResponse(allocationKey, allocation.windowId(), window.channelId(),
                WaterService.fmt(available), WaterService.fmt(written), WaterService.fmt(allocation.heldAmount()),
                rows);
    }

    /** 查询渠道读数历史（跨全部批次，按时刻升序、同刻按主键升序）。 */
    public ChannelReadingsResponse getChannelReadings(String channelId) {
        requireChannel(channelId);
        List<ReadingResponse> rows = sensorRepository.listReadingsByChannel(channelId).stream()
                .map(this::toReadingResponse).toList();
        return new ChannelReadingsResponse(channelId, rows);
    }

    /** 查询渠道诊断：当前规则版本、冻结状态、全部异常（含解除证据）与替代读数。 */
    public ChannelDiagnosticsResponse getDiagnostics(String channelId) {
        requireChannel(channelId);
        SensorRuleRow latest = sensorRepository.findLatestSensorRule(channelId);
        boolean frozen = sensorRepository.hasOpenAnomaly(channelId);
        List<AnomalyResponse> anomalies = sensorRepository.listAnomaliesByChannel(channelId).stream()
                .map(this::toAnomalyResponse).toList();
        List<ReplacementReadingResponse> replacements = anomalies.stream()
                .filter(a -> a.resolution() != null)
                .map(a -> {
                    ResolutionResponse r = a.resolution();
                    return new ReplacementReadingResponse(r.anomalyId(), r.replacementCumulative(),
                            r.replacementReadingUtc(), r.resolverActor(), r.createdUtc());
                }).toList();
        return new ChannelDiagnosticsResponse(channelId, latest == null ? null : toRuleResponse(latest),
                frozen, anomalies, replacements);
    }

    // ------------------------------------------------------------------
    // 幂等命令框架（command_log，结构化 JSON 指纹）
    // ------------------------------------------------------------------

    private <T> T runCommand(String operation, String commandKey, String fingerprint, Class<T> type,
                             java.util.function.Supplier<T> business) {
        try {
            return tx.execute(status -> {
                CommandRow existing = waterRepository.findCommand(commandKey);
                if (existing != null) {
                    return replay(existing, operation, fingerprint, type);
                }
                waterRepository.insertCommand(commandKey, operation, fingerprint, WaterService.nowNanos());
                T result = business.get();
                waterRepository.updateCommandResponse(commandKey, toJson(result));
                return result;
            });
        } catch (DuplicateKeyException e) {
            CommandRow committed = waterRepository.findCommand(commandKey);
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

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private record ParsedReading(long readingNanos, BigDecimal cumulative) {
    }

    private void requireChannel(String channelId) {
        if (sensorRepository.findChannel(channelId) == null) {
            throw ApiException.notFound("CHANNEL_NOT_FOUND", "渠道不存在: " + channelId);
        }
    }

    /** 首次配置规则时创建渠道锚点行；并发首建时唯一约束决出一方，另一方重读加锁。 */
    private SensorRepository.ChannelRow lockOrCreateChannel(String channelId) {
        SensorRepository.ChannelRow channel = sensorRepository.lockChannel(channelId);
        if (channel != null) {
            return channel;
        }
        try {
            long id = sensorRepository.insertChannel(channelId, WaterService.nowNanos());
            return new SensorRepository.ChannelRow(id, channelId, WaterService.nowNanos());
        } catch (DuplicateKeyException e) {
            SensorRepository.ChannelRow locked = sensorRepository.lockChannel(channelId);
            if (locked == null) {
                throw ApiException.conflict("CHANNEL_CONFLICT", "渠道正在被并发创建，请重试: " + channelId);
            }
            return locked;
        }
    }

    private SensorRuleResponse toRuleResponse(SensorRuleRow row) {
        return new SensorRuleResponse(row.id(), row.channelId(), row.ruleVersion(),
                WaterService.fmt(row.maxFlowRate()), row.maxGapMinutes(), row.actor(),
                WaterService.toIso(row.createdNanos()));
    }

    private ReadingResponse toReadingResponse(ReadingRow row) {
        return new ReadingResponse(row.id(), row.batchKey(), row.channelId(), row.seqNo(),
                WaterService.toIso(row.readingNanos()), WaterService.fmt(row.cumulative()),
                row.ruleVersion(), row.actor(), WaterService.toIso(row.createdNanos()));
    }

    private AnomalyResponse toAnomalyResponse(AnomalyRow row) {
        List<String> reasons = fromJsonList(row.reasons());
        ResolutionRow resolutionRow = sensorRepository.findResolutionByAnomaly(row.id());
        ResolutionResponse resolution = null;
        if (resolutionRow != null) {
            resolution = new ResolutionResponse(resolutionRow.id(), resolutionRow.anomalyId(),
                    resolutionRow.diagnosisNote(), WaterService.fmt(resolutionRow.replacementCumulative()),
                    WaterService.toIso(resolutionRow.replacementNanos()), resolutionRow.resolverActor(),
                    WaterService.toIso(resolutionRow.createdNanos()));
        }
        BigDecimal flowOver = row.actualFlowRate().subtract(row.maxFlowRate()).max(BigDecimal.ZERO)
                .setScale(RATE_SCALE, RoundingMode.HALF_UP);
        BigDecimal gapOver = row.actualGapMinutes().subtract(BigDecimal.valueOf(row.maxGapMinutes()))
                .max(BigDecimal.ZERO).setScale(RATE_SCALE, RoundingMode.HALF_UP);
        return new AnomalyResponse(row.id(), row.channelId(), row.batchKey(), row.ruleVersion(), reasons,
                WaterService.toIso(row.fromReadingNanos()), WaterService.toIso(row.toReadingNanos()),
                WaterService.fmt(row.fromCumulative()), WaterService.fmt(row.toCumulative()),
                WaterService.fmt(row.actualDelta()), WaterService.fmt(row.actualGapMinutes()),
                WaterService.fmt(row.actualFlowRate()), WaterService.fmt(row.maxFlowRate()),
                row.maxGapMinutes(), WaterService.fmt(flowOver), WaterService.fmt(gapOver),
                row.detectorActor(), WaterService.toIso(row.createdNanos()),
                resolution, resolution != null);
    }

    private WriteOffResponse toWriteOffResponse(WriteOffRow row) {
        return new WriteOffResponse(row.id(), row.writeOffKey(), row.allocationKey(), row.windowId(),
                row.channelId(), WaterService.fmt(row.amount()), WaterService.toIso(row.atNanos()),
                row.actor(), WaterService.toIso(row.createdNanos()));
    }

    /** 解析规则瞬时流量上限：缺失/非法（含非正、精度超 6 位）一律 422。 */
    private BigDecimal parseRuleRate(String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.unprocessable("RULE_PARAM_INVALID", "maxFlowRate 不能为空且必须大于 0");
        }
        String trimmed = value.trim();
        if (!RATE_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.unprocessable("RULE_PARAM_INVALID",
                    "maxFlowRate 必须为大于 0 的十进制字符串（立方米/分钟，最多 6 位小数）: " + value);
        }
        BigDecimal rate = new BigDecimal(trimmed);
        if (rate.signum() <= 0) {
            throw ApiException.unprocessable("RULE_PARAM_INVALID", "maxFlowRate 必须大于 0: " + value);
        }
        return rate;
    }

    /** 解析规则最大间隔分钟数：缺失/非正一律 422。 */
    private int parseRuleGap(Integer maxGapMinutes) {
        if (maxGapMinutes == null || maxGapMinutes <= 0) {
            throw ApiException.unprocessable("RULE_PARAM_INVALID", "maxGapMinutes 必须为大于 0 的整数分钟");
        }
        return maxGapMinutes;
    }

    /** 解析累计读数：非负十进制，最多 3 位小数；格式非法为 400。 */
    private BigDecimal parseCumulative(String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "cumulative 不能为空");
        }
        String trimmed = value.trim();
        if (!CUMULATIVE_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "cumulative 必须为非负十进制字符串（立方米，最多 3 位小数）: " + value);
        }
        return new BigDecimal(trimmed);
    }

    private String fingerprint(Object structure) {
        return toJson(structure);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("反序列化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> fromJsonList(String json) {
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            throw new IllegalStateException("原因列表反序列化失败", e);
        }
    }
}
