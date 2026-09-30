package com.example.starter.water;

import com.example.starter.water.BlendRepository.BlendApplicationRow;
import com.example.starter.water.BlendRepository.BlendSourceItemRow;
import com.example.starter.water.BlendRepository.BlendSourceRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.dto.Dtos.BlendApplicationResponse;
import com.example.starter.water.dto.Dtos.BlendSourceInput;
import com.example.starter.water.dto.Dtos.BlendSourceItemResponse;
import com.example.starter.water.dto.Dtos.BlendSourceResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 多来源水质混配业务服务。
 *
 * <p>受理：加权指标 = Σ(来源体积 × 来源指标) / Σ来源体积，总体积不得为零；
 * 任一来源不存在、额度不足、指标缺失或加权结果超过用途阈值，整单 422 回滚，不扣任何来源额度；
 * 等于阈值可受理。所有来源按 source_key 升序一次性锁定后预占，两个申请争用同一来源时
 * 以行锁与 CHECK 约束保证已提交预占总额不超过可用量。</p>
 *
 * <p>幂等：同一 requestId 同来源同体积（含指标、用途、阈值、申请人一致）重放首次配额与计算结果；
 * 改来源或体积返回 409。注册来源与取消沿用 command_log 命令幂等。
 * 取消释放各来源原预占量，历史体积、指标与加权值保留不重算。</p>
 */
@Service
public class BlendService {

    static final String STATUS_RESERVED = "RESERVED";
    static final String STATUS_CANCELLED = "CANCELLED";
    static final String ITEM_RESERVED = "RESERVED";
    static final String ITEM_RELEASED = "RELEASED";

    private static final int WEIGHTED_CALC_SCALE = 10;
    private static final int WEIGHTED_STORE_SCALE = 4;
    private static final Pattern INDEX_PATTERN = Pattern.compile("\\d{1,9}(\\.\\d{1,4})?");

    private final BlendRepository repository;
    private final WaterRepository commandRepository;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public BlendService(BlendRepository repository, WaterRepository commandRepository,
                        PlatformTransactionManager transactionManager, ObjectMapper objectMapper) {
        this.repository = repository;
        this.commandRepository = commandRepository;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /** 注册水质来源配额账户。 */
    public BlendSourceResponse registerSource(String commandKey, String sourceKey, String name,
                                              String qualityIndex, String totalQuota, String actor) {
        WaterService.requireKey("commandKey", commandKey);
        WaterService.requireKey("sourceKey", sourceKey);
        WaterService.requireKey("X-Actor-Id", actor);
        if (name == null || name.isBlank() || name.length() > 128) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "name 不能为空，且最长 128 字符");
        }
        BigDecimal index = parseIndicator("qualityIndex", qualityIndex);
        BigDecimal quota = WaterService.parseAmount("totalQuota", totalQuota);
        String trimmedName = name.trim();
        String params = "BLEND_SOURCE_REGISTER|" + sourceKey + "|" + trimmedName + "|"
                + index.toPlainString() + "|" + quota.toPlainString() + "|" + actor;
        return runCommand("BLEND_SOURCE_REGISTER", commandKey, params, BlendSourceResponse.class,
                () -> {
                    if (repository.findSourceByKey(sourceKey) != null) {
                        throw ApiException.conflict("BLEND_SOURCE_EXISTS", "水质来源已存在: " + sourceKey);
                    }
                    long now = WaterService.nowNanos();
                    repository.insertSource(sourceKey, trimmedName, index, quota, now);
                    return toSourceResponse(repository.findSourceByKey(sourceKey));
                });
    }

    /** 查询来源当前配额与预占情况。 */
    public BlendSourceResponse getSource(String sourceKey) {
        WaterService.requireKey("sourceKey", sourceKey);
        BlendSourceRow source = repository.findSourceByKey(sourceKey);
        if (source == null) {
            throw ApiException.notFound("BLEND_SOURCE_NOT_FOUND", "水质来源不存在: " + sourceKey);
        }
        return toSourceResponse(source);
    }

    /**
     * 提交混配申请（受理即预占）。输入中每个来源携带体积与指标；指标缺失、来源不存在、
     * 额度不足或加权结果超过阈值均整单失败回滚，不扣任何来源额度。
     */
    public BlendApplicationResponse submitBlend(String requestId, String userId, String purpose,
                                                String threshold, List<BlendSourceInput> sources,
                                                String actor) {
        WaterService.requireKey("requestId", requestId);
        WaterService.requireKey("userId", userId);
        WaterService.requireKey("purpose", purpose);
        WaterService.requireKey("X-Actor-Id", actor);
        BigDecimal thresholdValue = parseIndicator("threshold", threshold);
        List<ParsedSource> requested = parseSources(sources);

        // requestId 即申请幂等键：已存在则按首次快照重放或 409
        BlendApplicationRow existing = repository.findApplicationByRequestId(requestId);
        if (existing != null) {
            return replayOrConflict(existing, requested, userId, purpose, thresholdValue, actor);
        }
        try {
            return tx.execute(status -> acceptBlend(requestId, userId, purpose, thresholdValue, requested, actor));
        } catch (DuplicateKeyException e) {
            // 并发提交同一 requestId：唯一约束裁决，读取已提交首次申请重放或 409
            BlendApplicationRow committed = repository.findApplicationByRequestId(requestId);
            if (committed == null) {
                throw ApiException.conflict("BLEND_REQUEST_CONFLICT",
                        "相同 requestId 的申请正在处理，请重试: " + requestId);
            }
            return replayOrConflict(committed, requested, userId, purpose, thresholdValue, actor);
        }
    }

    /** 查询混配申请（含每种来源、体积、指标、加权值与预占状态）。 */
    public BlendApplicationResponse getBlend(String requestId) {
        WaterService.requireKey("requestId", requestId);
        BlendApplicationRow application = repository.findApplicationByRequestId(requestId);
        if (application == null) {
            throw ApiException.notFound("BLEND_APPLICATION_NOT_FOUND", "混配申请不存在: " + requestId);
        }
        return toApplicationResponse(application, repository.listItems(requestId));
    }

    /** 取消混配申请：仅申请人本人可取消 RESERVED 申请，释放各来源原预占量，历史依据保留不重算。 */
    public BlendApplicationResponse cancelBlend(String commandKey, String requestId, String actor) {
        WaterService.requireKey("commandKey", commandKey);
        WaterService.requireKey("requestId", requestId);
        WaterService.requireKey("X-Actor-Id", actor);
        String params = "BLEND_CANCEL|" + requestId + "|" + actor;
        return runCommand("BLEND_CANCEL", commandKey, params, BlendApplicationResponse.class,
                () -> {
                    BlendApplicationRow application = repository.lockApplicationByRequestId(requestId);
                    if (application == null) {
                        throw ApiException.notFound("BLEND_APPLICATION_NOT_FOUND",
                                "混配申请不存在: " + requestId);
                    }
                    if (!application.requester().equals(actor)) {
                        throw ApiException.conflict("NOT_OWNER", "只有申请人本人可以取消该混配申请");
                    }
                    if (STATUS_CANCELLED.equals(application.status())) {
                        throw ApiException.conflict("BLEND_ALREADY_CANCELLED", "混配申请已取消，不能重复取消");
                    }
                    long now = WaterService.nowNanos();
                    List<BlendSourceItemRow> items = repository.listItems(requestId);
                    List<String> sourceKeys = items.stream().map(BlendSourceItemRow::sourceKey).sorted().toList();
                    if (!sourceKeys.isEmpty()) {
                        Map<String, BlendSourceRow> locked = new LinkedHashMap<>();
                        for (BlendSourceRow row : repository.lockSourcesByKeys(sourceKeys)) {
                            locked.put(row.sourceKey(), row);
                        }
                        for (BlendSourceItemRow item : items) {
                            if (!ITEM_RESERVED.equals(item.reservedStatus())) {
                                continue;
                            }
                            BlendSourceRow source = locked.get(item.sourceKey());
                            if (source == null) {
                                throw ApiException.notFound("BLEND_SOURCE_NOT_FOUND",
                                        "水质来源不存在: " + item.sourceKey());
                            }
                            repository.releaseReservedVolume(source.id(), item.volume(), now);
                        }
                    }
                    repository.releaseItems(requestId);
                    repository.markApplicationCancelled(application.id(), now);
                    return toApplicationResponse(
                            repository.findApplicationByRequestId(requestId), repository.listItems(requestId));
                });
    }

    // ------------------------------------------------------------------
    // 受理
    // ------------------------------------------------------------------

    private BlendApplicationResponse acceptBlend(String requestId, String userId, String purpose,
                                                 BigDecimal threshold, List<ParsedSource> requested,
                                                 String actor) {
        List<String> sourceKeys = requested.stream().map(ParsedSource::sourceKey).sorted().toList();
        // 固定顺序一次性锁定本单全部来源，杜绝跨申请以不同顺序加锁导致死锁
        List<BlendSourceRow> lockedRows = repository.lockSourcesByKeys(sourceKeys);
        Map<String, BlendSourceRow> locked = new LinkedHashMap<>();
        for (BlendSourceRow row : lockedRows) {
            locked.put(row.sourceKey(), row);
        }

        BigDecimal totalVolume = BigDecimal.ZERO;
        // 先完成全部校验，再执行任何预占写入
        for (ParsedSource source : requested) {
            BlendSourceRow row = locked.get(source.sourceKey());
            if (row == null) {
                throw ApiException.unprocessable("BLEND_SOURCE_NOT_FOUND",
                        "水质来源不存在: " + source.sourceKey());
            }
            BigDecimal afterReserve = row.reservedVolume().add(source.volume());
            if (afterReserve.compareTo(row.totalQuota()) > 0) {
                throw ApiException.quotaExceeded("来源 " + source.sourceKey() + " 可用配额不足：总配额 "
                        + WaterService.fmt(row.totalQuota()) + "，已预占 " + WaterService.fmt(row.reservedVolume())
                        + "，本次申请 " + WaterService.fmt(source.volume()));
            }
            totalVolume = totalVolume.add(source.volume());
        }
        if (totalVolume.signum() == 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "混配总体积不得为零");
        }
        BigDecimal weighted = weightedIndex(requested);
        // 等于阈值可受理，严格大于阈值才拒绝
        if (weighted.compareTo(threshold) > 0) {
            throw ApiException.unprocessable("BLEND_THRESHOLD_EXCEEDED",
                    "混配加权指标 " + WaterService.fmt(weighted.setScale(WEIGHTED_STORE_SCALE, RoundingMode.HALF_UP))
                            + " 超过用途 " + purpose + " 阈值 " + WaterService.fmt(threshold));
        }
        BigDecimal storedWeighted = weighted.setScale(WEIGHTED_STORE_SCALE, RoundingMode.HALF_UP);

        long now = WaterService.nowNanos();
        repository.insertApplication(requestId, userId, purpose, threshold, totalVolume, storedWeighted,
                actor, now);
        for (ParsedSource source : requested) {
            BlendSourceRow row = locked.get(source.sourceKey());
            repository.addReservedVolume(row.id(), source.volume(), now);
            repository.insertItem(requestId, source.sourceKey(), source.volume(), source.qualityIndex(),
                    storedWeighted, now);
        }
        return toApplicationResponse(
                repository.findApplicationByRequestId(requestId), repository.listItems(requestId));
    }

    private BlendApplicationResponse replayOrConflict(BlendApplicationRow application,
                                                      List<ParsedSource> requested, String userId,
                                                      String purpose, BigDecimal threshold, String actor) {
        List<BlendSourceItemRow> storedItems = repository.listItems(application.requestId());
        Map<String, ParsedSource> incoming = new LinkedHashMap<>();
        for (ParsedSource source : requested) {
            incoming.put(source.sourceKey(), source);
        }
        boolean same = application.userId().equals(userId)
                && application.purpose().equals(purpose)
                && application.requester().equals(actor)
                && application.threshold().compareTo(threshold) == 0
                && storedItems.size() == incoming.size();
        if (same) {
            for (BlendSourceItemRow stored : storedItems) {
                ParsedSource candidate = incoming.get(stored.sourceKey());
                if (candidate == null
                        || stored.volume().compareTo(candidate.volume()) != 0
                        || stored.qualityIndex().compareTo(candidate.qualityIndex()) != 0) {
                    same = false;
                    break;
                }
            }
        }
        if (!same) {
            throw ApiException.conflict("BLEND_REQUEST_REUSED",
                    "相同 requestId 但来源、体积、指标或其他参数不同，拒绝重放: " + application.requestId());
        }
        return toApplicationResponse(application, storedItems);
    }

    // ------------------------------------------------------------------
    // 命令幂等
    // ------------------------------------------------------------------

    private <T> T runCommand(String operation, String commandKey, String params, Class<T> type,
                            Supplier<T> business) {
        try {
            return tx.execute(status -> {
                CommandRow existing = commandRepository.findCommand(commandKey);
                if (existing != null) {
                    return replayCommand(existing, operation, params, type);
                }
                commandRepository.insertCommand(commandKey, operation, params, WaterService.nowNanos());
                T result = business.get();
                commandRepository.updateCommandResponse(commandKey, toJson(result));
                return result;
            });
        } catch (DuplicateKeyException e) {
            CommandRow committed = commandRepository.findCommand(commandKey);
            if (committed == null || committed.response() == null) {
                throw ApiException.conflict("COMMAND_CONFLICT", "相同 commandKey 的命令正在处理，请重试");
            }
            return replayCommand(committed, operation, params, type);
        }
    }

    private <T> T replayCommand(CommandRow existing, String operation, String params, Class<T> type) {
        if (!existing.operation().equals(operation) || !existing.params().equals(params)) {
            throw ApiException.conflict("COMMAND_KEY_REUSED", "相同 commandKey 但参数不同，拒绝重放");
        }
        return fromJson(existing.response(), type);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private List<ParsedSource> parseSources(List<BlendSourceInput> sources) {
        if (sources == null || sources.isEmpty()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "sources 至少包含一个来源");
        }
        List<ParsedSource> parsed = new ArrayList<>();
        for (BlendSourceInput input : sources) {
            if (input == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "来源项不能为空");
            }
            WaterService.requireKey("sourceKey", input.sourceKey());
            BigDecimal volume = WaterService.parseAmount("volume", input.volume());
            // 指标缺失属于受理规则：整单 422
            if (input.qualityIndex() == null || input.qualityIndex().isBlank()) {
                throw ApiException.unprocessable("BLEND_INDICATOR_MISSING",
                        "来源 " + input.sourceKey() + " 缺少水质指标");
            }
            BigDecimal index = parseIndicator("qualityIndex", input.qualityIndex());
            parsed.add(new ParsedSource(input.sourceKey(), volume, index));
        }
        if (parsed.stream().map(ParsedSource::sourceKey).distinct().count() != parsed.size()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "同一申请内来源不得重复");
        }
        return parsed;
    }

    /** 解析水质指标/阈值：十进制字符串，非负，最多 4 位小数。 */
    static BigDecimal parseIndicator(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不能为空");
        }
        String trimmed = value.trim();
        if (!INDEX_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 必须为非负十进制字符串，最多 4 位小数: " + value);
        }
        return new BigDecimal(trimmed);
    }

    private BlendSourceResponse toSourceResponse(BlendSourceRow row) {
        BigDecimal remaining = row.totalQuota().subtract(row.reservedVolume());
        return new BlendSourceResponse(row.sourceKey(), row.name(), WaterService.fmt(row.qualityIndex()),
                WaterService.fmt(row.totalQuota()), WaterService.fmt(row.reservedVolume()),
                WaterService.fmt(remaining), WaterService.toIso(row.createdNanos()),
                WaterService.toIso(row.updatedNanos()));
    }

    private BlendApplicationResponse toApplicationResponse(BlendApplicationRow row,
                                                           List<BlendSourceItemRow> items) {
        List<BlendSourceItemResponse> sourceResponses = items.stream().map(item ->
                new BlendSourceItemResponse(item.sourceKey(), WaterService.fmt(item.volume()),
                        WaterService.fmt(item.qualityIndex()), WaterService.fmt(item.weightedIndex()),
                        item.reservedStatus())).toList();
        return new BlendApplicationResponse(row.requestId(), row.userId(), row.purpose(),
                WaterService.fmt(row.threshold()), WaterService.fmt(row.totalVolume()),
                WaterService.fmt(row.weightedIndex()), row.requester(), row.status(),
                WaterService.toIso(row.createdNanos()), WaterService.toIso(row.updatedNanos()),
                row.cancelledNanos() == null ? null : WaterService.toIso(row.cancelledNanos()),
                sourceResponses);
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

    /**
     * 按体积加权水质指标：Σ(体积 × 指标) / Σ体积，HALF_UP 保留 10 位计算精度。
     * 调用方保证各体积大于 0 且总体积大于 0。
     */
    static BigDecimal weightedIndex(List<? extends WeightedSource> sources) {
        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal totalVolume = BigDecimal.ZERO;
        for (WeightedSource source : sources) {
            weightedSum = weightedSum.add(source.volume().multiply(source.qualityIndex()));
            totalVolume = totalVolume.add(source.volume());
        }
        if (totalVolume.signum() == 0) {
            throw new IllegalArgumentException("总体积不得为零");
        }
        return weightedSum.divide(totalVolume, WEIGHTED_CALC_SCALE, RoundingMode.HALF_UP);
    }

    /** 加权计算输入：取用体积（立方米）与水质指标（无量纲）。 */
    interface WeightedSource {
        BigDecimal volume();

        BigDecimal qualityIndex();
    }

    /** 规范化后的申请来源：业务键 + 取用体积（立方米）+ 水质指标（无量纲）。 */
    record ParsedSource(String sourceKey, BigDecimal volume, BigDecimal qualityIndex) implements WeightedSource {
    }
}
