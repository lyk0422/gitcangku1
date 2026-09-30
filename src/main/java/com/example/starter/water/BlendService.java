package com.example.starter.water;

import com.example.starter.water.BlendRepository.BlendApplicationRow;
import com.example.starter.water.BlendRepository.BlendItemRow;
import com.example.starter.water.BlendRepository.BlendSourceRow;
import com.example.starter.water.dto.Dtos.BlendApplicationResponse;
import com.example.starter.water.dto.Dtos.BlendItemResponse;
import com.example.starter.water.dto.Dtos.BlendSourceInput;
import com.example.starter.water.dto.Dtos.BlendSourceResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多来源水质混配业务服务。
 *
 * <p>受理规则：加权水质指标 = Σ(来源体积 × 来源指标) ÷ 总体积，保留 6 位小数四舍五入；
 * 加权值不超过用途阈值方可受理（等于阈值可受理）。任一来源不存在、指标缺失、可用配额不足或
 * 加权值超过阈值时整单驳回（404/422），事务回滚不扣任何来源额度；总体积必须大于 0。</p>
 *
 * <p>并发：提交时按来源键排序后依次 SELECT ... FOR UPDATE 锁定全部来源行，在锁内汇总
 * 已预占总量并裁决，保证已提交的预占总额永不超过来源可用配额；取消释放同一批预占量。</p>
 *
 * <p>幂等：创建来源/取消申请走 commandKey 命令框架；混配提交以 requestId 兼作命令幂等键，
 * 同键同参（含全部来源与体积）重放首次配额与计算结果，同键改来源或体积返回 409。</p>
 */
@Service
public class BlendService {

    static final String STATUS_RESERVED = "RESERVED";
    static final String STATUS_CANCELLED = "CANCELLED";

    private static final int INDEX_SCALE = 6;

    private final BlendRepository repository;
    private final CommandTemplate commands;

    public BlendService(BlendRepository repository, CommandTemplate commands) {
        this.repository = repository;
        this.commands = commands;
    }

    /** 创建水质来源（固定配额池）；qualityIndex 为 null/空白表示指标缺失。 */
    public BlendSourceResponse createSource(String commandKey, String sourceKey, String name,
                                            String quotaVolume, String qualityIndex) {
        WaterService.requireKey("commandKey", commandKey);
        WaterService.requireKey("sourceKey", sourceKey);
        String trimmedName = requireText("name", name);
        BigDecimal quota = WaterService.parseAmount("quotaVolume", quotaVolume);
        BigDecimal index = qualityIndex == null || qualityIndex.isBlank()
                ? null : parseIndex("qualityIndex", qualityIndex);
        String params = "BLEND_SOURCE_CREATE|" + sourceKey + "|" + trimmedName + "|"
                + quota.toPlainString() + "|" + (index == null ? "" : index.toPlainString());
        return commands.run("BLEND_SOURCE_CREATE", commandKey, params, BlendSourceResponse.class, () -> {
            if (repository.findSourceByKey(sourceKey) != null) {
                throw ApiException.conflict("BLEND_SOURCE_KEY_REUSED", "来源已存在: " + sourceKey);
            }
            repository.insertSource(sourceKey, trimmedName, quota, index, WaterService.nowNanos());
            return toSourceResponse(repository.findSourceByKey(sourceKey));
        });
    }

    /**
     * 提交多来源水质混配申请：一次性锁定全部来源、校验指标/配额/阈值后原子预占。
     * 申请人为 actor；requestId 全局唯一且兼作幂等键。
     */
    public BlendApplicationResponse submitBlend(String requestId, String userId, String purpose,
                                                String qualityThreshold, List<BlendSourceInput> sources,
                                                String actor) {
        WaterService.requireKey("requestId", requestId);
        WaterService.requireKey("userId", userId);
        WaterService.requireKey("X-Actor-Id", actor);
        String trimmedPurpose = requireText("purpose", purpose);
        BigDecimal threshold = parseIndex("qualityThreshold", qualityThreshold);
        List<BlendSourceInput> inputs = validateInputs(sources);

        // 规范化参数串：来源按提交顺序拼接，同 requestId 改任一来源或体积都会被识别为改参（409）
        StringBuilder params = new StringBuilder("BLEND_SUBMIT|").append(requestId).append('|')
                .append(userId).append('|').append(trimmedPurpose).append('|')
                .append(threshold.toPlainString()).append('|').append(actor);
        for (BlendSourceInput input : inputs) {
            params.append('|').append(input.sourceKey()).append(':')
                    .append(WaterService.parseAmount("volume", input.volume()).toPlainString());
        }
        return commands.run("BLEND_SUBMIT", requestId, params.toString(), BlendApplicationResponse.class,
                () -> reserve(requestId, userId, trimmedPurpose, threshold, inputs, actor));
    }

    /** 查询混配申请：返回每种来源、体积、指标、加权贡献值与当前预占状态；取消记录保留原计算依据。 */
    public BlendApplicationResponse getApplication(String requestId) {
        WaterService.requireKey("requestId", requestId);
        BlendApplicationRow application = repository.findApplicationByRequestId(requestId);
        if (application == null) {
            throw ApiException.notFound("BLEND_NOT_FOUND", "混配申请不存在: " + requestId);
        }
        return toApplicationResponse(application, repository.listItemsByApplicationId(application.id()));
    }

    /** 取消混配申请：仅申请人本人可取消，取消立即释放全部来源的原预占量，不可恢复。 */
    public BlendApplicationResponse cancelBlend(String commandKey, String requestId, String actor) {
        WaterService.requireKey("commandKey", commandKey);
        WaterService.requireKey("requestId", requestId);
        WaterService.requireKey("X-Actor-Id", actor);
        String params = "BLEND_CANCEL|" + requestId + "|" + actor;
        return commands.run("BLEND_CANCEL", commandKey, params, BlendApplicationResponse.class, () -> {
            BlendApplicationRow application = repository.lockApplicationByRequestId(requestId);
            if (application == null) {
                throw ApiException.notFound("BLEND_NOT_FOUND", "混配申请不存在: " + requestId);
            }
            if (!application.requester().equals(actor)) {
                throw ApiException.conflict("NOT_OWNER", "只有申请人本人可以取消该申请");
            }
            if (STATUS_CANCELLED.equals(application.status())) {
                throw ApiException.conflict("BLEND_ALREADY_CANCELLED", "申请已取消，不能重复取消");
            }
            repository.cancelApplication(application.id(), WaterService.nowNanos());
            BlendApplicationRow cancelled = repository.findApplicationById(application.id());
            return toApplicationResponse(cancelled, repository.listItemsByApplicationId(application.id()));
        });
    }

    /** 查询来源当前配额、指标与预占/可用体积。 */
    public BlendSourceResponse getSource(String sourceKey) {
        WaterService.requireKey("sourceKey", sourceKey);
        BlendSourceRow source = repository.findSourceByKey(sourceKey);
        if (source == null) {
            throw ApiException.notFound("BLEND_SOURCE_NOT_FOUND", "水质来源不存在: " + sourceKey);
        }
        return toSourceResponse(source);
    }

    // ------------------------------------------------------------------
    // 受理与预占
    // ------------------------------------------------------------------

    private BlendApplicationResponse reserve(String requestId, String userId, String purpose,
                                             BigDecimal threshold, List<BlendSourceInput> inputs,
                                             String actor) {
        // 按来源键排序加锁，避免多申请交叉锁定多来源时死锁；不存在 -> 404
        List<BlendSourceRow> lockedInKeyOrder = new ArrayList<>();
        for (String sourceKey : inputs.stream().map(BlendSourceInput::sourceKey)
                .distinct().sorted().toList()) {
            BlendSourceRow source = repository.lockSourceByKey(sourceKey);
            if (source == null) {
                throw ApiException.notFound("BLEND_SOURCE_NOT_FOUND", "水质来源不存在: " + sourceKey);
            }
            lockedInKeyOrder.add(source);
        }
        Map<String, BlendSourceRow> sourcesByKey = new LinkedHashMap<>();
        for (BlendSourceRow source : lockedInKeyOrder) {
            sourcesByKey.put(source.sourceKey(), source);
        }

        // 提交顺序下的体积与指标快照，并做全部业务校验：指标缺失、阈值、配额任一不过即整单驳回
        List<BigDecimal> volumes = new ArrayList<>();
        List<BigDecimal> indexes = new ArrayList<>();
        for (BlendSourceInput input : inputs) {
            BlendSourceRow source = sourcesByKey.get(input.sourceKey());
            if (source.qualityIndex() == null) {
                throw ApiException.quotaExceeded("来源 " + source.sourceKey() + " 水质指标缺失，不能参与混配");
            }
            volumes.add(WaterService.parseAmount("volume", input.volume()));
            indexes.add(source.qualityIndex());
        }
        BigDecimal totalVolume = volumes.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal weighted = weightedIndex(volumes, indexes, totalVolume);
        if (weighted.compareTo(threshold) > 0) {
            throw ApiException.quotaExceeded("混配加权指标 " + WaterService.fmt(weighted)
                    + " 超过用途阈值 " + WaterService.fmt(threshold));
        }
        for (int i = 0; i < inputs.size(); i++) {
            BlendSourceRow source = sourcesByKey.get(inputs.get(i).sourceKey());
            BigDecimal reserved = repository.sumReservedVolumeBySource(source.sourceKey());
            if (reserved.add(volumes.get(i)).compareTo(source.quotaVolume()) > 0) {
                throw ApiException.quotaExceeded("来源 " + source.sourceKey() + " 可用配额不足：已预占 "
                        + WaterService.fmt(reserved) + "，配额 " + WaterService.fmt(source.quotaVolume())
                        + "，本次申请 " + WaterService.fmt(volumes.get(i)));
            }
        }

        // 全部校验通过后才落库：申请主表与来源明细同一事务提交，任一失败整体回滚
        long now = WaterService.nowNanos();
        long applicationId = repository.insertApplication(requestId, userId, purpose, threshold,
                totalVolume, weighted, actor, now);
        for (int i = 0; i < inputs.size(); i++) {
            repository.insertItem(applicationId, inputs.get(i).sourceKey(), volumes.get(i),
                    indexes.get(i), i + 1, now);
        }
        return toApplicationResponse(repository.findApplicationById(applicationId),
                repository.listItemsByApplicationId(applicationId));
    }

    private List<BlendSourceInput> validateInputs(List<BlendSourceInput> sources) {
        if (sources == null || sources.isEmpty()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "sources 不能为空，且总体积必须大于 0");
        }
        List<BlendSourceInput> inputs = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (BlendSourceInput input : sources) {
            if (input == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "sources 中存在空元素");
            }
            WaterService.requireKey("sourceKey", input.sourceKey());
            // 提前解析体积：必须为大于 0 的十进制（最多 3 位小数），故总体积不可能为零
            WaterService.parseAmount("volume", input.volume());
            if (seen.contains(input.sourceKey())) {
                throw ApiException.badRequest("DUPLICATE_SOURCE",
                        "同一申请中来源不能重复: " + input.sourceKey());
            }
            seen.add(input.sourceKey());
            inputs.add(input);
        }
        return inputs;
    }

    // ------------------------------------------------------------------
    // 纯计算与视图
    // ------------------------------------------------------------------

    /**
     * 加权水质指标：Σ(体积×指标) ÷ 总体积，保留 6 位小数四舍五入。totalVolume 必须大于 0。
     */
    static BigDecimal weightedIndex(List<BigDecimal> volumes, List<BigDecimal> indexes,
                                    BigDecimal totalVolume) {
        BigDecimal weightedSum = BigDecimal.ZERO;
        for (int i = 0; i < volumes.size(); i++) {
            weightedSum = weightedSum.add(volumes.get(i).multiply(indexes.get(i)));
        }
        return weightedSum.divide(totalVolume, INDEX_SCALE, RoundingMode.HALF_UP);
    }

    private BlendSourceResponse toSourceResponse(BlendSourceRow row) {
        BigDecimal reserved = repository.sumReservedVolumeBySource(row.sourceKey());
        return new BlendSourceResponse(row.sourceKey(), row.name(), WaterService.fmt(row.quotaVolume()),
                row.qualityIndex() == null ? null : WaterService.fmt(row.qualityIndex()),
                WaterService.fmt(reserved), WaterService.fmt(row.quotaVolume().subtract(reserved)),
                WaterService.toIso(row.createdNanos()));
    }

    private BlendApplicationResponse toApplicationResponse(BlendApplicationRow row,
                                                           List<BlendItemRow> items) {
        boolean reserved = STATUS_RESERVED.equals(row.status());
        List<BlendItemResponse> itemResponses = items.stream()
                .sorted(Comparator.comparingInt(BlendItemRow::seqNo))
                .map(item -> new BlendItemResponse(item.sourceKey(), WaterService.fmt(item.volume()),
                        WaterService.fmt(item.qualityIndex()),
                        WaterService.fmt(item.volume().multiply(item.qualityIndex())), reserved))
                .toList();
        return new BlendApplicationResponse(row.requestId(), row.userId(), row.purpose(),
                WaterService.fmt(row.qualityThreshold()), WaterService.fmt(row.totalVolume()),
                WaterService.fmt(row.weightedIndex()), row.requester(), row.status(),
                WaterService.toIso(row.createdNanos()),
                row.cancelledNanos() == null ? null : WaterService.toIso(row.cancelledNanos()),
                itemResponses);
    }

    /** 解析水质指标/阈值：十进制字符串，非负，最多 6 位小数。 */
    static BigDecimal parseIndex(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不能为空");
        }
        String trimmed = value.trim();
        if (!trimmed.matches("\\d{1,16}(\\.\\d{1,6})?")) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 必须为非负十进制字符串，最多 6 位小数: " + value);
        }
        return new BigDecimal(trimmed);
    }

    private static String requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不能为空");
        }
        String trimmed = value.trim();
        if (trimmed.length() > 128) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 最长 128 字符");
        }
        return trimmed;
    }
}
