package com.example.starter.water;

import com.example.starter.water.MeterRepository.AnomalyRow;
import com.example.starter.water.MeterRepository.ChannelRow;
import com.example.starter.water.MeterRepository.ReadingRow;
import com.example.starter.water.MeterRepository.ResolutionRow;
import com.example.starter.water.MeterRepository.RuleVersionRow;
import com.example.starter.water.dto.Dtos.AnomalyListResponse;
import com.example.starter.water.dto.Dtos.AnomalyResponse;
import com.example.starter.water.dto.Dtos.ChannelResponse;
import com.example.starter.water.dto.Dtos.ReadingItem;
import com.example.starter.water.dto.Dtos.ReadingListResponse;
import com.example.starter.water.dto.Dtos.ReadingResponse;
import com.example.starter.water.dto.Dtos.ResolutionResponse;
import com.example.starter.water.dto.Dtos.RuleVersionResponse;
import com.example.starter.water.dto.Dtos.SubmitReadingsResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * 配水传感计量服务：渠道规则版本、累计读数提交、异常判定/冻结与异常解除。
 *
 * <p>幂等：写操作走 {@link CommandExecutor}，参数指纹为规范化 JSON（操作类型置于最外层，
 * 集合以 JSON 数组转义序列化，绝不直接以未转义分隔符拼接），覆盖操作者与全部结果相关字段；
 * 同键同参重放首次成功响应快照，读取与重放均不改状态，失败回滚不占键。</p>
 *
 * <p>裁决：读数提交、异常解除与规则配置在事务内先对 meter_channel 行 SELECT ... FOR UPDATE，
 * 与窗口核销（先锁窗口再锁渠道）按事务提交顺序串行；异常先提交则后到的同渠道核销被拦截。</p>
 *
 * <p>冻结为派生状态：渠道存在未解除异常即冻结，全部解除后派生解冻。读数、规则版本、异常、
 * 解除与替代值只插入不改写；解除不改写原异常、原读数或既有核销。替代读数是解除证据，
 * 校验其相对异常前序读数不产生倒退/超速/超间隔；后续新读数仍从不可变的最新实际读数续接。</p>
 */
@Service
public class MeterService {

    static final String OP_CONFIGURE = "CHANNEL_CONFIGURE";
    static final String OP_READING_SUBMIT = "READING_SUBMIT";
    static final String OP_RESOLVE = "ANOMALY_RESOLVE";

    private final MeterRepository meterRepository;
    private final CommandExecutor commandExecutor;
    private Clock clock = Clock.systemUTC();

    public MeterService(MeterRepository meterRepository, CommandExecutor commandExecutor) {
        this.meterRepository = meterRepository;
        this.commandExecutor = commandExecutor;
    }

    /** 替换时钟（测试使用可控时钟），生产默认 systemUTC。 */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // 命令入口
    // ------------------------------------------------------------------

    /** 配置（首次注册并出版本 1，之后只追加新版本）渠道异常规则。 */
    public ChannelResponse configureChannel(String commandKey, String channelId, String maxFlowRate,
                                            Integer maxIntervalMinutes, String actor) {
        WaterService.requireKey("commandKey", commandKey);
        WaterService.requireKey("channelId", channelId);
        WaterService.requireKey("X-Actor-Id", actor);
        // 版本号在锁内决定，指纹只承载规范化参数与操作者
        MeterRules.Rule requested = MeterRules.validateRule(0, maxFlowRate, maxIntervalMinutes);
        ConfigureFingerprint fp = new ConfigureFingerprint(actor, channelId,
                WaterService.fmt(requested.maxFlowRate()), requested.maxIntervalMinutes());
        return commandExecutor.run(OP_CONFIGURE, commandKey, fp, ChannelResponse.class, () -> {
            long now = nowNanos();
            meterRepository.registerChannelIfAbsent(channelId, actor, now);
            ChannelRow channel = meterRepository.lockChannel(channelId);
            RuleVersionRow latest = meterRepository.findLatestRuleVersion(channelId);
            int version = latest == null ? 1 : latest.version() + 1;
            meterRepository.insertRuleVersion(channelId, version, requested.maxFlowRate(),
                    requested.maxIntervalMinutes(), actor, now);
            return loadChannelResponse(channel);
        });
    }

    /**
     * 批量提交累计读数：先完整校验整批序列（非空、时刻合法且批次内严格递增、累计值非负合法），
     * 任一错误整批拒绝（422），不写入任何读数/异常/命令。锁渠道后还须整体晚于历史最新读数。
     */
    public SubmitReadingsResponse submitReadings(String commandKey, String channelId, List<ReadingItem> readings,
                                                 String actor) {
        WaterService.requireKey("commandKey", commandKey);
        WaterService.requireKey("channelId", channelId);
        WaterService.requireKey("X-Actor-Id", actor);
        // 先校验完整序列，任一错误在开启命令前直接拒绝
        List<MeterRules.Point> points = MeterRules.validateBatch(readings);
        List<ReadingFingerprintItem> items = new ArrayList<>(points.size());
        for (MeterRules.Point point : points) {
            items.add(new ReadingFingerprintItem(point.utcNanos(), WaterService.fmt(point.cumulative())));
        }
        SubmitFingerprint fp = new SubmitFingerprint(actor, channelId, items);
        return commandExecutor.run(OP_READING_SUBMIT, commandKey, fp, SubmitReadingsResponse.class, () -> {
            long now = nowNanos();
            ChannelRow channel = meterRepository.lockChannel(channelId);
            if (channel == null) {
                throw ApiException.notFound("CHANNEL_NOT_FOUND", "渠道计量点不存在，请先配置规则: " + channelId);
            }
            RuleVersionRow ruleRow = meterRepository.findLatestRuleVersion(channelId);
            MeterRules.Rule rule = new MeterRules.Rule(ruleRow.version(), ruleRow.maxFlowRate(),
                    ruleRow.maxIntervalMinutes());
            // 渠道锁内读取历史链头；整批必须严格晚于链头，否则整批 422（唯一约束同时兜底同刻）
            ReadingRow head = meterRepository.findLatestReading(channelId);
            if (head != null && points.get(0).utcNanos() <= head.readingUtcNanos()) {
                throw ApiException.unprocessable("READING_NOT_INCREMENTAL",
                        "首批读数时刻 " + WaterService.toIso(points.get(0).utcNanos())
                                + " 必须严格晚于渠道最新读数时刻 " + WaterService.toIso(head.readingUtcNanos()));
            }

            List<ReadingRow> insertedReadings = new ArrayList<>(points.size());
            List<AnomalyRow> insertedAnomalies = new ArrayList<>();
            for (int i = 0; i < points.size(); i++) {
                MeterRules.Point curr = points.get(i);
                MeterRules.Point prevPoint;
                long prevReadingId;
                if (i == 0) {
                    if (head == null) {
                        // 渠道首个读数没有相邻对，直接入库，不产生异常
                        insertedReadings.add(meterRepository.insertReading(channelId, curr.utcNanos(),
                                curr.cumulative(), i, actor, rule.version(), commandKey, now));
                        continue;
                    }
                    prevPoint = new MeterRules.Point(head.readingUtcNanos(), head.cumulativeVolume());
                    prevReadingId = head.id();
                } else {
                    ReadingRow prevRow = insertedReadings.get(i - 1);
                    prevPoint = new MeterRules.Point(prevRow.readingUtcNanos(), prevRow.cumulativeVolume());
                    prevReadingId = prevRow.id();
                }
                ReadingRow currRow = meterRepository.insertReading(channelId, curr.utcNanos(),
                        curr.cumulative(), i, actor, rule.version(), commandKey, now);
                insertedReadings.add(currRow);
                for (MeterRules.Finding finding : MeterRules.evaluate(prevPoint, curr, rule)) {
                    insertedAnomalies.add(insertAnomaly(channelId, rule, finding, prevReadingId, currRow,
                            prevPoint, curr, actor, commandKey, now));
                }
            }
            boolean frozen = meterRepository.countActiveAnomalies(channelId) > 0;
            List<ReadingResponse> readingResponses = insertedReadings.stream().map(this::toReadingResponse).toList();
            List<AnomalyResponse> anomalyResponses = insertedAnomalies.stream()
                    .map(a -> toAnomalyResponse(a, null)).toList();
            return new SubmitReadingsResponse(channelId, rule.version(), frozen,
                    readingResponses, anomalyResponses);
        });
    }

    /**
     * 解除异常：解除人必须不同于异常提交人，须给出诊断说明与替代读数；
     * 替代读数时刻严格夹在异常相邻两读数之间，且相对前序读数不产生倒退/超速/超间隔。
     * 只插入解除证据，不改写原异常、原读数或既有核销；全部异常解除后渠道派生解冻。
     */
    public AnomalyResponse resolveAnomaly(String commandKey, long anomalyId, String actor, String diagnosisNote,
                                          String replacementReadingUtc, String replacementCumulativeVolume) {
        WaterService.requireKey("commandKey", commandKey);
        WaterService.requireKey("X-Actor-Id", actor);
        if (diagnosisNote == null || diagnosisNote.isBlank()) {
            throw ApiException.unprocessable("INVALID_DIAGNOSIS", "diagnosisNote 诊断说明不能为空");
        }
        String note = diagnosisNote.trim();
        if (note.length() > 1024) {
            throw ApiException.unprocessable("INVALID_DIAGNOSIS", "diagnosisNote 最长 1024 字符");
        }
        // 先解析替代读数文本（非法直接 422），跨时刻/跨规则的完整校验在锁内进行
        ResolveFingerprint fp = new ResolveFingerprint(anomalyId, actor, note, replacementReadingUtc,
                replacementCumulativeVolume);
        return commandExecutor.run(OP_RESOLVE, commandKey, fp, AnomalyResponse.class, () -> {
            AnomalyRow anomaly = meterRepository.findAnomalyById(anomalyId);
            if (anomaly == null) {
                throw ApiException.notFound("ANOMALY_NOT_FOUND", "异常不存在: " + anomalyId);
            }
            // 先锁渠道行，与读数提交/规则配置/核销按提交顺序裁决
            ChannelRow channel = meterRepository.lockChannel(anomaly.channelId());
            if (channel == null) {
                throw new IllegalStateException("异常所属渠道缺失: " + anomaly.channelId());
            }
            anomaly = meterRepository.lockAnomalyById(anomalyId);
            if (meterRepository.findResolutionByAnomaly(anomalyId) != null) {
                throw ApiException.conflict("ANOMALY_ALREADY_RESOLVED", "异常已解除，证据不可改写");
            }
            if (anomaly.submitter().equals(actor)) {
                throw ApiException.conflict("RESOLVER_MUST_DIFFER",
                        "异常解除必须由不同于提交人的操作人确认（提交人: " + anomaly.submitter() + "）");
            }
            RuleVersionRow ruleRow = meterRepository.findRuleVersion(anomaly.channelId(), anomaly.ruleVersion());
            if (ruleRow == null) {
                throw new IllegalStateException("异常判定规则版本缺失: " + anomaly.ruleVersion());
            }
            ReadingRow previous = meterRepository.findReadingById(anomaly.previousReadingId());
            ReadingRow anomalous = meterRepository.findReadingById(anomaly.readingId());
            if (previous == null || anomalous == null) {
                throw new IllegalStateException("异常相邻读数证据缺失");
            }
            MeterRules.Rule rule = new MeterRules.Rule(ruleRow.version(), ruleRow.maxFlowRate(),
                    ruleRow.maxIntervalMinutes());
            MeterRules.Point replacement = MeterRules.validateReplacement(
                    new MeterRules.Point(previous.readingUtcNanos(), previous.cumulativeVolume()),
                    new MeterRules.Point(anomalous.readingUtcNanos(), anomalous.cumulativeVolume()),
                    replacementReadingUtc, replacementCumulativeVolume, rule);
            long now = nowNanos();
            meterRepository.insertResolution(new ResolutionRow(0L, anomalyId, anomaly.channelId(), actor, note,
                    replacement.utcNanos(), replacement.cumulative(), commandKey, now));
            return toAnomalyResponse(anomaly, meterRepository.findResolutionByAnomaly(anomalyId));
        });
    }

    // ------------------------------------------------------------------
    // 查询（读取不改状态，排序稳定）
    // ------------------------------------------------------------------

    /** 查询渠道当前状态（冻结派生、规则版本）。 */
    public ChannelResponse getChannel(String channelId) {
        ChannelRow channel = meterRepository.findChannel(channelId);
        if (channel == null) {
            throw ApiException.notFound("CHANNEL_NOT_FOUND", "渠道计量点不存在: " + channelId);
        }
        return loadChannelResponse(channel);
    }

    /** 查询渠道读数明细（不可变，按测量时刻、主键升序）。 */
    public ReadingListResponse listReadings(String channelId) {
        requireChannel(channelId);
        List<ReadingResponse> readings = meterRepository.listReadings(channelId).stream()
                .map(this::toReadingResponse).toList();
        return new ReadingListResponse(channelId, readings);
    }

    /** 查询渠道异常历史与诊断（不可变，按触发读数时刻、主键升序）。 */
    public AnomalyListResponse listAnomalies(String channelId) {
        requireChannel(channelId);
        List<AnomalyResponse> anomalies = meterRepository.listAnomalies(channelId).stream()
                .map(row -> toAnomalyResponse(row, meterRepository.findResolutionByAnomaly(row.id()))).toList();
        return new AnomalyListResponse(channelId, anomalies);
    }

    /** 查询单条异常证据与解除诊断。 */
    public AnomalyResponse getAnomaly(long anomalyId) {
        AnomalyRow anomaly = meterRepository.findAnomalyById(anomalyId);
        if (anomaly == null) {
            throw ApiException.notFound("ANOMALY_NOT_FOUND", "异常不存在: " + anomalyId);
        }
        return toAnomalyResponse(anomaly, meterRepository.findResolutionByAnomaly(anomalyId));
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private ChannelRow requireChannel(String channelId) {
        ChannelRow channel = meterRepository.findChannel(channelId);
        if (channel == null) {
            throw ApiException.notFound("CHANNEL_NOT_FOUND", "渠道计量点不存在: " + channelId);
        }
        return channel;
    }

    private ChannelResponse loadChannelResponse(ChannelRow channel) {
        RuleVersionRow latest = meterRepository.findLatestRuleVersion(channel.channelId());
        List<RuleVersionRow> versions = meterRepository.listRuleVersions(channel.channelId());
        int active = meterRepository.countActiveAnomalies(channel.channelId());
        return toChannelResponse(channel, latest, active, versions);
    }

    private AnomalyRow insertAnomaly(String channelId, MeterRules.Rule rule, MeterRules.Finding finding,
                                     long previousReadingId, ReadingRow currRow, MeterRules.Point prevPoint,
                                     MeterRules.Point curr, String actor, String commandKey, long now) {
        return meterRepository.insertAnomaly(new AnomalyRow(0L, channelId, finding.type(), rule.version(),
                previousReadingId, currRow.id(), prevPoint.utcNanos(), curr.utcNanos(),
                prevPoint.cumulative(), curr.cumulative(), finding.volumeDelta(), finding.durationMinutes(),
                finding.actualFlowRate(), rule.maxFlowRate(), rule.maxIntervalMinutes(),
                finding.regressionAmount(), finding.flowRateExcess(), finding.intervalExcessMinutes(),
                actor, commandKey, now));
    }

    long nowNanos() {
        return WaterService.toNanos(java.time.Instant.now(clock));
    }

    ChannelResponse toChannelResponse(ChannelRow channel, RuleVersionRow latest, int activeAnomalyCount,
                                      List<RuleVersionRow> versions) {
        List<RuleVersionResponse> versionResponses = versions.stream().map(v -> new RuleVersionResponse(
                v.version(), WaterService.fmt(v.maxFlowRate()), v.maxIntervalMinutes(),
                v.createdBy(), WaterService.toIso(v.createdNanos()))).toList();
        return new ChannelResponse(channel.channelId(), activeAnomalyCount > 0, activeAnomalyCount,
                latest == null ? null : latest.version(),
                latest == null ? null : WaterService.fmt(latest.maxFlowRate()),
                latest == null ? null : latest.maxIntervalMinutes(),
                channel.createdBy(), WaterService.toIso(channel.createdNanos()), versionResponses);
    }

    ReadingResponse toReadingResponse(ReadingRow row) {
        return new ReadingResponse(row.id(), row.channelId(), WaterService.toIso(row.readingUtcNanos()),
                WaterService.fmt(row.cumulativeVolume()), row.batchSeq(), row.submitter(), row.ruleVersion(),
                row.commandKey(), WaterService.toIso(row.createdNanos()));
    }

    ResolutionResponse toResolutionResponse(ResolutionRow row) {
        return new ResolutionResponse(row.anomalyId(), row.resolver(), row.diagnosisNote(),
                WaterService.toIso(row.replacementUtcNanos()), WaterService.fmt(row.replacementCumulative()),
                row.commandKey(), WaterService.toIso(row.createdNanos()));
    }

    AnomalyResponse toAnomalyResponse(AnomalyRow row, ResolutionRow resolution) {
        return new AnomalyResponse(row.id(), row.channelId(), row.anomalyType(), row.ruleVersion(),
                row.previousReadingId(), row.readingId(),
                WaterService.toIso(row.previousUtcNanos()), WaterService.toIso(row.readingUtcNanos()),
                WaterService.fmt(row.previousCumulative()), WaterService.fmt(row.readingCumulative()),
                WaterService.fmt(row.volumeDelta()), WaterService.fmt(row.durationMinutes()),
                WaterService.fmt(row.actualFlowRate()), WaterService.fmt(row.requiredMaxFlowRate()),
                row.requiredMaxIntervalMinutes(),
                row.regressionAmount() == null ? null : WaterService.fmt(row.regressionAmount()),
                row.flowRateExcess() == null ? null : WaterService.fmt(row.flowRateExcess()),
                row.intervalExcessMinutes() == null ? null : WaterService.fmt(row.intervalExcessMinutes()),
                row.submitter(), row.commandKey(), WaterService.toIso(row.createdNanos()),
                resolution == null ? null : toResolutionResponse(resolution));
    }

    // ------------------------------------------------------------------
    // 幂等指纹（规范化 JSON；集合为 JSON 数组，禁止未转义分隔符拼接）
    // ------------------------------------------------------------------

    private record ConfigureFingerprint(String actor, String channelId, String maxFlowRate,
                                        int maxIntervalMinutes) {
    }

    private record ReadingFingerprintItem(long readingUtcNanos, String cumulativeVolume) {
    }

    private record SubmitFingerprint(String actor, String channelId, List<ReadingFingerprintItem> readings) {
    }

    private record ResolveFingerprint(long anomalyId, String actor, String diagnosisNote,
                                      String replacementReadingUtc, String replacementCumulativeVolume) {
    }
}
