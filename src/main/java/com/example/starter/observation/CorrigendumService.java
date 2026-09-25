package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 观测更正附页业务服务：原始观测不可覆盖，更正以附页形式按版本递增追加。
 *
 * <p>写操作（提交/批量提交/撤销）均在单事务内完成：先占位写入幂等去重记录（提交以 corrKey 为键，
 * 指纹含原版本、规范化差异、原因与采集者；批量与撤销以 requestId 为键），再对 observation_current
 * 行加锁（SELECT ... FOR UPDATE）按提交顺序串行化并发，业务失败整体回滚、不占键、不留半成品。
 *
 * <p>差异校验：空差异或未知字段 422；读数更正值须为最多三位小数的十进制且绝对值不超过
 * 999999999.999（最终坐标边界），地点更正值不可为空；读数按数值规范化后落库与计入指纹。
 *
 * <p>冲突簇与导出视图：未裁决观测（无解决记录）使用最新有效附页；已人工裁决的观测冻结裁决时
 * 采用的观测版本，之后附页不改写裁决结果，但为每条解决记录生成待复审标记。
 * 撤销仅允许最新有效附页，写入不可变撤销记录并恢复上一个有效版本。
 */
@Service
public class CorrigendumService {

    private static final String SEPARATOR = "";

    /**
     * 可更正字段的固定顺序：差异规范化、原值快照与指纹均以此顺序处理，保证结果稳定。
     */
    private static final List<String> FIELDS = List.of("location", "reading", "note");

    private static final Pattern READING_PATTERN = Pattern.compile("-?\\d+(\\.\\d{1,3})?");

    /**
     * 读数（坐标类数值）最终边界：绝对值上限，超出即整批/整单 422。
     */
    private static final BigDecimal READING_BOUND = new BigDecimal("999999999.999");

    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<CorrigendumResponse>> RESPONSE_LIST_TYPE = new TypeReference<>() {
    };

    private final ObservationRepository observationRepository;
    private final CorrigendumRepository corrigendumRepository;
    private final ResolutionRepository resolutionRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CorrigendumService(ObservationRepository observationRepository,
                              CorrigendumRepository corrigendumRepository,
                              ResolutionRepository resolutionRepository,
                              RequestLogRepository requestLogRepository,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.observationRepository = observationRepository;
        this.corrigendumRepository = corrigendumRepository;
        this.resolutionRepository = resolutionRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 提交单张更正附页：校验差异后占位去重，行锁内递增附页版本并保存原值与更正值；
     * 已裁决观测同时为每条解决记录生成待复审标记。
     */
    @Transactional
    public CorrOutcome submit(String observationId, CorrigendumRequest request) {
        Map<String, String> normalizedDiffs = normalizeDiffs(request.diffs());
        String diffsJson = writeJson(normalizedDiffs);
        String fingerprint = fingerprint("CORRIGENDUM", observationId, String.valueOf(request.baseVersion()),
                diffsJson, request.reason(), request.collector());
        CorrOutcome replayed = checkReplay(request.corrKey(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        CorrOutcome concurrent = insertPlaceholder(request.corrKey(), fingerprint, "CORRIGENDUM");
        if (concurrent != null) {
            return concurrent;
        }

        ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.gone("observation already deleted: " + observationId);
        }
        ObservationSnapshot base = findBaseVersion(observationId, request.baseVersion());

        CorrigendumRecord record = appendCorrigendum(observationId, request.corrKey(), request.baseVersion(),
                diffsJson, originalValuesJson(base, normalizedDiffs), request.reason(), request.collector(),
                nextCorrVersion(observationId, new HashMap<>()));
        return complete(request.corrKey(), HttpStatus.CREATED, CorrigendumResponse.of(record, objectMapper));
    }

    /**
     * 批量提交附页：先校验全部原观测、采集者与最终坐标边界（差异内容），任一失败整批回滚，
     * 不产生任何附页或重算；校验通过后按提交顺序在各行锁内追加附页。
     */
    @Transactional
    public CorrBatchOutcome submitBatch(CorrigendumBatchRequest request) {
        // 第一阶段：纯校验（不写库）。任一条目失败即整批失败，不产生附页。
        List<Map<String, String>> normalizedDiffsList = new ArrayList<>();
        List<String> diffsJsonList = new ArrayList<>();
        StringBuilder fingerprintParts = new StringBuilder();
        for (CorrigendumBatchRequest.Item item : request.items()) {
            Map<String, String> normalizedDiffs = normalizeDiffs(item.diffs());
            String diffsJson = writeJson(normalizedDiffs);
            normalizedDiffsList.add(normalizedDiffs);
            diffsJsonList.add(diffsJson);
            ObservationSnapshot current = observationRepository.findCurrent(item.observationId())
                    .orElseThrow(() -> ApiException.notFound("observation not found: " + item.observationId()));
            if (current.deleted()) {
                throw ApiException.gone("observation already deleted: " + item.observationId());
            }
            findBaseVersion(item.observationId(), item.baseVersion());
            fingerprintParts.append(SEPARATOR).append(item.observationId())
                    .append(SEPARATOR).append(item.corrKey())
                    .append(SEPARATOR).append(item.baseVersion())
                    .append(SEPARATOR).append(diffsJson)
                    .append(SEPARATOR).append(item.reason())
                    .append(SEPARATOR).append(item.collector());
        }
        String fingerprint = fingerprint("CORRIGENDUM_BATCH", fingerprintParts.toString());
        CorrBatchOutcome replayed = checkBatchReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        CorrBatchOutcome concurrent = insertBatchPlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        // 第二阶段：按观测标识排序加行锁（避免死锁），按提交顺序追加附页。
        List<String> involvedIds = request.items().stream()
                .map(CorrigendumBatchRequest.Item::observationId).distinct().sorted().toList();
        Map<String, ObservationSnapshot> locked = new HashMap<>();
        for (String observationId : involvedIds) {
            ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                    .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
            if (current.deleted()) {
                throw ApiException.gone("observation already deleted: " + observationId);
            }
            locked.put(observationId, current);
        }

        Map<String, Integer> nextVersions = new HashMap<>();
        List<CorrigendumResponse> responses = new ArrayList<>();
        for (int i = 0; i < request.items().size(); i++) {
            CorrigendumBatchRequest.Item item = request.items().get(i);
            ObservationSnapshot base = findBaseVersion(item.observationId(), item.baseVersion());
            CorrigendumRecord record = appendCorrigendum(item.observationId(), item.corrKey(), item.baseVersion(),
                    diffsJsonList.get(i), originalValuesJson(base, normalizedDiffsList.get(i)),
                    item.reason(), item.collector(), nextCorrVersion(item.observationId(), nextVersions));
            responses.add(CorrigendumResponse.of(record, objectMapper));
        }
        return completeBatch(request.requestId(), HttpStatus.CREATED, responses);
    }

    /**
     * 撤销附页：只允许撤销当前最新有效附页；写入不可变撤销记录，撤销后恢复上一个有效版本。
     */
    @Transactional
    public CorrRevokeOutcome revoke(String observationId, CorrigendumRevokeRequest request) {
        String fingerprint = fingerprint("CORRIGENDUM_REVOKE", observationId,
                String.valueOf(request.corrVersion()), request.operator());
        CorrRevokeOutcome replayed = checkRevokeReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        CorrRevokeOutcome concurrent = insertRevokePlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.gone("observation already deleted: " + observationId);
        }
        corrigendumRepository.find(observationId, request.corrVersion())
                .orElseThrow(() -> ApiException.notFound(
                        "corrigendum not found: " + observationId + "#" + request.corrVersion()));
        CorrigendumRecord latestValid = corrigendumRepository.findLatestValid(observationId)
                .orElseThrow(() -> ApiException.conflict(
                        "no valid corrigendum to revoke: " + observationId, current.version()));
        if (latestValid.corrVersion() != request.corrVersion()) {
            throw ApiException.conflict(
                    "only the latest valid corrigendum can be revoked: " + observationId
                            + "#" + request.corrVersion() + ", latest valid is #" + latestValid.corrVersion(),
                    current.version());
        }

        RevocationRecord revocation = new RevocationRecord(observationId, request.corrVersion(),
                request.requestId(), request.operator(), Instant.now(clock));
        corrigendumRepository.markRevoked(observationId, request.corrVersion());
        corrigendumRepository.insertRevocation(revocation);
        return completeRevoke(request.requestId(), HttpStatus.OK, revocation);
    }

    /**
     * 查询附页链（含已撤销），按附页版本先后排序；观测记录不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<CorrigendumRecord> listCorrigenda(String observationId) {
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        return corrigendumRepository.findByObservationId(observationId);
    }

    /**
     * 查询观测记录的撤销历史，按撤销时刻先后排序；观测记录不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<RevocationRecord> listRevocations(String observationId) {
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        return corrigendumRepository.findRevocations(observationId);
    }

    /**
     * 查询待复审标记，按生成时刻先后排序；观测记录不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<ReReviewMarker> listMarkers(String observationId) {
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        return corrigendumRepository.findMarkers(observationId);
    }

    /**
     * 导出视图：未裁决观测应用最新有效附页后的有效值；已人工裁决的观测冻结裁决结果，
     * 附页不改写有效值。墓碑只返回删除状态和版本。
     */
    @Transactional(readOnly = true)
    public ExportViewResponse exportView(String observationId) {
        ObservationSnapshot current = observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        int pendingCount = corrigendumRepository.countMarkers(observationId);
        if (current.deleted()) {
            return new ExportViewResponse(observationId, current.version(), true, true,
                    null, null, null, null, pendingCount);
        }
        boolean resolved = !resolutionRepository.findByObservationId(observationId).isEmpty();
        if (resolved) {
            // 已人工裁决：冻结裁决时采用的观测版本，附页不改写有效值
            return new ExportViewResponse(observationId, current.version(), false, true,
                    current.location(), current.reading(), current.note(), null, pendingCount);
        }
        Optional<CorrigendumRecord> latestValid = corrigendumRepository.findLatestValid(observationId);
        if (latestValid.isEmpty()) {
            return new ExportViewResponse(observationId, current.version(), false, false,
                    current.location(), current.reading(), current.note(), null, pendingCount);
        }
        CorrigendumRecord corrigendum = latestValid.get();
        ObservationSnapshot effective = applyDiffs(current, readMap(corrigendum.diffs()));
        return new ExportViewResponse(observationId, current.version(), false, false,
                effective.location(), effective.reading(), effective.note(),
                corrigendum.corrVersion(), pendingCount);
    }

    /**
     * 计算用于冲突簇（三方合并/解决重算）的当前侧有效快照：未裁决观测应用最新有效附页；
     * 已人工裁决或无有效附页时返回原快照。供 ObservationService 在持有观测行锁的写事务内调用。
     */
    public ObservationSnapshot effectiveSnapshot(ObservationSnapshot current) {
        if (current.deleted()) {
            return current;
        }
        if (!resolutionRepository.findByObservationId(current.observationId()).isEmpty()) {
            return current;
        }
        return corrigendumRepository.findLatestValid(current.observationId())
                .map(corrigendum -> applyDiffs(current, readMap(corrigendum.diffs())))
                .orElse(current);
    }

    // ---------- 附页追加与校验 ----------

    /**
     * 在持有观测行锁的写事务内追加附页：版本递增，保存原值与更正值；已裁决观测生成待复审标记。
     */
    private CorrigendumRecord appendCorrigendum(String observationId, String corrKey, int baseVersion,
                                                String diffsJson, String originalValuesJson,
                                                String reason, String collector,
                                                int corrVersion) {
        CorrigendumRecord record = new CorrigendumRecord(observationId, corrVersion, corrKey, baseVersion,
                diffsJson, originalValuesJson, reason, collector, false, Instant.now(clock));
        corrigendumRepository.insert(record);
        // 已人工裁决的簇：附页不改写裁决结果，但为每条冻结的解决记录生成待复审标记
        for (ResolutionRecord resolution : resolutionRepository.findByObservationId(observationId)) {
            corrigendumRepository.insertMarker(new ReReviewMarker(
                    resolution.resolutionId(), observationId, corrVersion, "PENDING", Instant.now(clock)));
        }
        return record;
    }

    /**
     * 计算同一观测记录的下一个附页版本号；批量场景用内存计数避免重复读取。
     */
    private int nextCorrVersion(String observationId, Map<String, Integer> nextVersions) {
        int next = nextVersions.computeIfAbsent(observationId,
                id -> corrigendumRepository.maxCorrVersion(id) + 1);
        nextVersions.put(observationId, next + 1);
        return next;
    }

    /**
     * 读取附页指定的原观测版本；版本不存在 404，墓碑版本不可作为更正对象（422）。
     */
    private ObservationSnapshot findBaseVersion(String observationId, int baseVersion) {
        ObservationSnapshot base = observationRepository.findVersion(observationId, baseVersion)
                .orElseThrow(() -> ApiException.notFound(
                        "base version not found: " + observationId + "@" + baseVersion));
        if (base.deleted()) {
            throw ApiException.unprocessable(
                    "base version is a deletion tombstone: " + observationId + "@" + baseVersion);
        }
        return base;
    }

    /**
     * 校验并规范化字段差异：空差异或未知字段 422；更正值按字段规则校验（含读数最终坐标边界），
     * 读数按数值规范化；返回按固定字段顺序排列的差异映射。
     */
    private Map<String, String> normalizeDiffs(Map<String, String> raw) {
        if (raw.isEmpty()) {
            throw ApiException.unprocessable("diffs must not be empty");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String field = entry.getKey() == null ? null : entry.getKey().trim();
            if (field == null || !FIELDS.contains(field)) {
                throw ApiException.unprocessable("unknown diff field: " + field
                        + "; allowed fields: location, reading, note");
            }
            String value = entry.getValue();
            if (value == null) {
                throw ApiException.unprocessable("corrected value for field '" + field + "' must not be null");
            }
            normalized.put(field, switch (field) {
                case "location" -> normalizeLocation(value);
                case "reading" -> normalizeReading(value);
                default -> normalizeNote(value);
            });
        }
        return normalized;
    }

    private String normalizeLocation(String value) {
        if (value.isBlank()) {
            throw ApiException.unprocessable("corrected location must not be blank");
        }
        if (value.length() > 512) {
            throw ApiException.unprocessable("corrected location exceeds 512 characters");
        }
        return value;
    }

    /**
     * 读数更正值校验：十进制字符串、最多三位小数，且绝对值不超过最终坐标边界。
     */
    private String normalizeReading(String value) {
        if (!READING_PATTERN.matcher(value).matches()) {
            throw ApiException.unprocessable(
                    "corrected reading must be a decimal string with at most 3 fraction digits: " + value);
        }
        BigDecimal reading = new BigDecimal(value);
        if (reading.abs().compareTo(READING_BOUND) > 0) {
            throw ApiException.unprocessable(
                    "corrected reading out of final coordinate boundary [-" + READING_BOUND.toPlainString()
                            + ", " + READING_BOUND.toPlainString() + "]: " + value);
        }
        return reading.stripTrailingZeros().toPlainString();
    }

    private String normalizeNote(String value) {
        if (value.length() > 1024) {
            throw ApiException.unprocessable("corrected note exceeds 1024 characters");
        }
        return value;
    }

    /**
     * 按固定字段顺序从原观测版本快照提取差异字段的原值，序列化为 JSON 原文。
     */
    private String originalValuesJson(ObservationSnapshot base, Map<String, String> normalizedDiffs) {
        Map<String, String> originals = new LinkedHashMap<>();
        for (String field : FIELDS) {
            if (normalizedDiffs.containsKey(field)) {
                originals.put(field, fieldValue(base, field));
            }
        }
        return writeJson(originals);
    }

    /**
     * 将差异按字段覆盖到快照上，得到有效值快照（版本号与删除标志不变）。
     */
    private ObservationSnapshot applyDiffs(ObservationSnapshot snapshot, Map<String, String> diffs) {
        String location = diffs.getOrDefault("location", snapshot.location());
        String reading = diffs.getOrDefault("reading", snapshot.reading());
        String note = diffs.getOrDefault("note", snapshot.note());
        return new ObservationSnapshot(snapshot.observationId(), snapshot.version(),
                location, reading, note, snapshot.deleted());
    }

    private String fieldValue(ObservationSnapshot snapshot, String field) {
        return switch (field) {
            case "location" -> snapshot.location();
            case "reading" -> snapshot.reading();
            default -> snapshot.note();
        };
    }

    // ---------- 幂等去重（提交/批量/撤销共用 request_log 模式） ----------

    private CorrOutcome checkReplay(String corrKey, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(corrKey);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("corrKey reused with different parameters: " + corrKey, null);
        }
        return new CorrOutcome(entry.responseStatus(), readCorrBody(entry.responseBody()));
    }

    private CorrOutcome insertPlaceholder(String corrKey, String fingerprint, String operation) {
        try {
            requestLogRepository.insertPlaceholder(corrKey, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(corrKey)
                    .orElseThrow(() -> ApiException.conflict("corrKey conflict: " + corrKey, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("corrKey reused with different parameters: " + corrKey, null);
            }
            return new CorrOutcome(entry.responseStatus(), readCorrBody(entry.responseBody()));
        }
    }

    private CorrOutcome complete(String corrKey, HttpStatus status, CorrigendumResponse body) {
        requestLogRepository.complete(corrKey, status.value(), writeJson(body));
        return new CorrOutcome(status.value(), body);
    }

    private CorrBatchOutcome checkBatchReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new CorrBatchOutcome(entry.responseStatus(), readBatchBody(entry.responseBody()));
    }

    private CorrBatchOutcome insertBatchPlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "CORRIGENDUM_BATCH");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new CorrBatchOutcome(entry.responseStatus(), readBatchBody(entry.responseBody()));
        }
    }

    private CorrBatchOutcome completeBatch(String requestId, HttpStatus status, List<CorrigendumResponse> body) {
        requestLogRepository.complete(requestId, status.value(), writeJson(body));
        return new CorrBatchOutcome(status.value(), body);
    }

    private CorrRevokeOutcome checkRevokeReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new CorrRevokeOutcome(entry.responseStatus(), readRevokeBody(entry.responseBody()));
    }

    private CorrRevokeOutcome insertRevokePlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "CORRIGENDUM_REVOKE");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new CorrRevokeOutcome(entry.responseStatus(), readRevokeBody(entry.responseBody()));
        }
    }

    private CorrRevokeOutcome completeRevoke(String requestId, HttpStatus status, RevocationRecord body) {
        requestLogRepository.complete(requestId, status.value(), writeJson(body));
        return new CorrRevokeOutcome(status.value(), body);
    }

    // ---------- 序列化与指纹 ----------

    private String fingerprint(String operation, String... parts) {
        StringBuilder raw = new StringBuilder(operation);
        for (String part : parts) {
            raw.append(SEPARATOR).append(part == null ? "<null>" : part);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize corrigendum payload", e);
        }
    }

    private Map<String, String> readMap(String json) {
        try {
            return objectMapper.readValue(json, STRING_MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored diffs", e);
        }
    }

    private CorrigendumResponse readCorrBody(String json) {
        try {
            return objectMapper.readValue(json, CorrigendumResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored corrigendum response", e);
        }
    }

    private List<CorrigendumResponse> readBatchBody(String json) {
        try {
            return objectMapper.readValue(json, RESPONSE_LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored batch response", e);
        }
    }

    private RevocationRecord readRevokeBody(String json) {
        try {
            return objectMapper.readValue(json, RevocationRecord.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored revocation response", e);
        }
    }

    /**
     * 附页提交结果：HTTP 状态码与附页响应体。
     */
    public record CorrOutcome(int status, CorrigendumResponse body) {
    }

    /**
     * 批量附页提交结果：HTTP 状态码与按提交顺序排列的附页响应体列表。
     */
    public record CorrBatchOutcome(int status, List<CorrigendumResponse> body) {
    }

    /**
     * 附页撤销结果：HTTP 状态码与不可变撤销记录。
     */
    public record CorrRevokeOutcome(int status, RevocationRecord body) {
    }
}
