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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 观测更正附页业务服务。
 *
 * <p>原始观测不可覆盖：更正以附页形式追加，corr_version 每个观测记录内从 1 递增，
 * 附页保存各字段提交前有效原值（from）与更正值（to）。提交须指定原观测版本
 * （须等于提交时当前版本，否则 409）、字段差异（空差异、未知字段、无实际变化的字段均 422）、
 * 原因和采集者；应用差异后的最终坐标必须是 "lat,lon" 且在合法边界内，否则 422。
 *
 * <p>冲突簇与导出视图：未裁决观测使用最新有效附页后的有效值；已人工裁决的观测冻结在
 * 裁决时采用的观测版本，之后附页不改写裁决结果，但生成待复审标记。
 *
 * <p>撤销仅允许最新有效附页（最高 corr_version 的 VALID 附页），写入不可变撤销记录，
 * 有效状态恢复上一个有效附页（无有效附页时恢复原始观测值）。
 *
 * <p>并发：附页提交/批量/撤销与合并、裁决共用 observation_current 行锁，按提交顺序串行；
 * corrKey 复用 request_log 幂等机制，指纹含原版本、差异规范化、原因和操作者，
 * 同键同参重放、同键异参 409、失败回滚不占键。
 */
@Service
public class CorrigendumService {

    private static final String SEPARATOR = "";

    /**
     * 三个可编辑字段的固定顺序：差异规范化、指纹与存储均以此顺序处理，保证结果稳定。
     */
    private static final List<String> FIELDS = List.of("location", "reading", "note");

    private static final Pattern READING_PATTERN = Pattern.compile("-?\\d+(\\.\\d{1,3})?");

    private static final Pattern COORDINATE_PATTERN =
            Pattern.compile("(-?\\d+(\\.\\d+)?),(-?\\d+(\\.\\d+)?)");

    private static final BigDecimal MAX_LATITUDE = new BigDecimal("90");
    private static final BigDecimal MAX_LONGITUDE = new BigDecimal("180");

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
     * 提交单条更正附页，成功返回 201 与附页内容。
     */
    @Transactional
    public CorrOutcome submit(String observationId, SubmitCorrigendumRequest request) {
        Map<String, String> normalizedDiffs = normalizeDiffValues(request.diffs());
        String fingerprint = fingerprint("CORRIGENDUM", observationId,
                String.valueOf(request.baseVersion()), writeNormalizedDiffs(normalizedDiffs),
                request.reason(), request.collector());
        CorrOutcome replayed = checkCorrReplay(request.corrKey(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        CorrOutcome concurrent = insertCorrPlaceholder(request.corrKey(), fingerprint, "CORRIGENDUM");
        if (concurrent != null) {
            return concurrent;
        }

        ObservationSnapshot current = lockObservation(observationId);
        CorrigendumEntry entry = buildAndInsert(observationId, current, request.baseVersion(),
                request.diffs(), request.reason(), request.collector(), request.corrKey());
        return completeCorr(request.corrKey(), HttpStatus.CREATED, CorrigendumResponse.of(entry));
    }

    /**
     * 批量提交更正附页：先校验所有原观测、采集者和最终坐标边界，任一失败整批回滚，
     * 不产生任何附页或重算。成功返回 201 与全部附页（按请求条目顺序）。
     */
    @Transactional
    public BatchOutcome submitBatch(BatchCorrigendumRequest request) {
        String fingerprint = fingerprint("CORRIGENDUM_BATCH", batchFingerprintParts(request.items()));
        BatchOutcome replayed = checkBatchReplay(request.corrKey(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        BatchOutcome concurrent = insertBatchPlaceholder(request.corrKey(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        Set<String> seen = new HashSet<>();
        for (BatchCorrigendumRequest.Item item : request.items()) {
            if (!seen.add(item.observationId())) {
                throw ApiException.unprocessable(
                        "duplicate observationId in batch: " + item.observationId());
            }
        }

        // 按 observationId 排序加锁，避免批量与单条/批量之间死锁；先全部校验再统一写入
        List<BatchCorrigendumRequest.Item> sorted = new ArrayList<>(request.items());
        sorted.sort(java.util.Comparator.comparing(BatchCorrigendumRequest.Item::observationId));
        Map<String, CorrigendumEntry> prepared = new LinkedHashMap<>();
        for (BatchCorrigendumRequest.Item item : sorted) {
            ObservationSnapshot current = lockObservation(item.observationId());
            prepared.put(item.observationId(), prepare(item.observationId(), current, item.baseVersion(),
                    item.diffs(), item.reason(), item.collector(), request.corrKey()));
        }
        List<CorrigendumResponse> responses = new ArrayList<>();
        Instant now = Instant.now(clock);
        for (BatchCorrigendumRequest.Item item : request.items()) {
            CorrigendumEntry draft = prepared.get(item.observationId());
            CorrigendumEntry entry = new CorrigendumEntry(draft.observationId(), draft.corrVersion(),
                    draft.baseVersion(), draft.diffs(), draft.reason(), draft.collector(), draft.corrKey(),
                    draft.status(), now);
            corrigendumRepository.insert(entry);
            flagIfAdjudicated(entry.observationId(), entry.corrVersion(), ReviewFlagRecord.EVENT_SUBMIT, now);
            responses.add(CorrigendumResponse.of(entry));
        }
        return completeBatch(request.corrKey(), HttpStatus.CREATED, responses);
    }

    /**
     * 撤销最新有效附页：写入不可变撤销记录，有效状态恢复上一个有效附页。
     * 非最新有效版本或已撤销版本返回 409；附页不存在返回 404。
     */
    @Transactional
    public RevokeOutcome revoke(String observationId, RevokeCorrigendumRequest request) {
        String fingerprint = fingerprint("REVOKE", observationId, String.valueOf(request.corrVersion()),
                request.operator(), request.reason() == null ? "" : request.reason());
        RevokeOutcome replayed = checkRevokeReplay(request.corrKey(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        RevokeOutcome concurrent = insertRevokePlaceholder(request.corrKey(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        // 行锁串行化撤销与提交/合并/裁决；墓碑记录的附页链仍允许维护（撤销不复活观测）
        observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        CorrigendumEntry entry = corrigendumRepository.findByVersion(observationId, request.corrVersion())
                .orElseThrow(() -> ApiException.notFound(
                        "corrigendum not found: " + observationId + "#" + request.corrVersion()));
        List<CorrigendumEntry> chain = corrigendumRepository.findByObservationId(observationId);
        CorrigendumEntry latestValid = null;
        for (CorrigendumEntry item : chain) {
            if (CorrigendumEntry.STATUS_VALID.equals(item.status())) {
                latestValid = item;
            }
        }
        if (latestValid == null || latestValid.corrVersion() != request.corrVersion()) {
            throw ApiException.conflict(
                    "only the latest valid corrigendum can be revoked: " + observationId
                            + "#" + request.corrVersion(), null);
        }

        Integer restoredVersion = null;
        for (CorrigendumEntry item : chain) {
            if (CorrigendumEntry.STATUS_VALID.equals(item.status())
                    && item.corrVersion() != request.corrVersion()) {
                restoredVersion = item.corrVersion();
            }
        }

        Instant now = Instant.now(clock);
        corrigendumRepository.markRevoked(observationId, request.corrVersion());
        RevocationRecord record = new RevocationRecord(request.corrKey(), observationId,
                request.corrVersion(), restoredVersion, request.corrKey(), request.operator(),
                request.reason(), now);
        corrigendumRepository.insertRevocation(record);
        flagIfAdjudicated(observationId, request.corrVersion(), ReviewFlagRecord.EVENT_REVOKE, now);
        return completeRevoke(request.corrKey(), HttpStatus.OK, RevocationResponse.of(record));
    }

    /**
     * 查询附页链（全部版本，含已撤销），按附页版本升序；观测记录不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<CorrigendumResponse> listCorrigenda(String observationId) {
        requireObservation(observationId);
        return corrigendumRepository.findByObservationId(observationId).stream()
                .map(CorrigendumResponse::of)
                .toList();
    }

    /**
     * 查询撤销记录，按撤销时刻先后排序；观测记录不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<RevocationResponse> listRevocations(String observationId) {
        requireObservation(observationId);
        return corrigendumRepository.findRevocationsByObservationId(observationId).stream()
                .map(RevocationResponse::of)
                .toList();
    }

    /**
     * 查询待复审标记，按生成先后排序；观测记录不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<ReviewFlagResponse> listReviewFlags(String observationId) {
        requireObservation(observationId);
        return corrigendumRepository.findReviewFlagsByObservationId(observationId).stream()
                .map(ReviewFlagResponse::of)
                .toList();
    }

    /**
     * 导出视图（冲突簇共用）：未裁决观测应用最新有效附页；已裁决观测冻结在裁决版本，
     * 附页不改写裁决结果，仅以待复审标记提示。
     */
    @Transactional(readOnly = true)
    public ObservationViewResponse view(String observationId) {
        ObservationSnapshot current = observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        Optional<ResolutionRecord> latestResolution =
                resolutionRepository.findLatestByObservationId(observationId);
        boolean pendingReview = corrigendumRepository.hasPendingReviewFlag(observationId);
        if (current.deleted()) {
            return new ObservationViewResponse(observationId, current.version(), true,
                    latestResolution.isPresent(),
                    latestResolution.map(ResolutionRecord::newVersion).orElse(null),
                    pendingReview, null, null, null, null, null, null);
        }
        if (latestResolution.isPresent()) {
            // 已裁决：冻结在裁决时采用的观测版本
            ResolutionRecord resolution = latestResolution.get();
            ObservationSnapshot frozen = observationRepository.findVersion(observationId, resolution.newVersion())
                    .orElseThrow(() -> new IllegalStateException(
                            "adjudicated version missing: " + observationId + "@" + resolution.newVersion()));
            return new ObservationViewResponse(observationId, current.version(), false, true,
                    resolution.newVersion(), pendingReview,
                    current.location(), current.reading(), current.note(),
                    frozen.location(), frozen.reading(), frozen.note());
        }
        Map<String, String> effective = computeEffective(observationId, current);
        return new ObservationViewResponse(observationId, current.version(), false, false, null,
                pendingReview, current.location(), current.reading(), current.note(),
                effective.get("location"), effective.get("reading"), effective.get("note"));
    }

    // ---------- 提交内部逻辑 ----------

    /**
     * 加行锁读取观测当前状态：不存在 404，已删除 410（墓碑不允许新增附页）。
     */
    private ObservationSnapshot lockObservation(String observationId) {
        ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.gone("observation already deleted: " + observationId);
        }
        return current;
    }

    /**
     * 单条提交：校验并插入附页，生成待复审标记，返回落库后的附页记录。
     */
    private CorrigendumEntry buildAndInsert(String observationId, ObservationSnapshot current,
                                            int baseVersion, Map<String, String> diffs,
                                            String reason, String collector, String corrKey) {
        CorrigendumEntry draft = prepare(observationId, current, baseVersion, diffs, reason, collector, corrKey);
        CorrigendumEntry entry = new CorrigendumEntry(draft.observationId(), draft.corrVersion(),
                draft.baseVersion(), draft.diffs(), draft.reason(), draft.collector(), draft.corrKey(),
                draft.status(), Instant.now(clock));
        corrigendumRepository.insert(entry);
        flagIfAdjudicated(observationId, entry.corrVersion(), ReviewFlagRecord.EVENT_SUBMIT,
                entry.createdAtUtc());
        return entry;
    }

    /**
     * 校验并构造附页草稿（不落库）：原观测版本、字段差异、最终坐标边界全部通过后，
     * 计算下一附页版本与保存用的原值/更正值。批量场景先对全部条目执行本方法再统一落库。
     */
    private CorrigendumEntry prepare(String observationId, ObservationSnapshot current,
                                     int baseVersion, Map<String, String> diffs,
                                     String reason, String collector, String corrKey) {
        if (baseVersion != current.version()) {
            throw ApiException.conflict("baseVersion mismatch", current.version());
        }
        Map<String, String> effective = computeEffective(observationId, current);
        Map<String, FieldDiff> validated = validateDiffs(diffs, effective);
        String finalLocation = validated.containsKey("location")
                ? validated.get("location").to() : effective.get("location");
        validateFinalCoordinates(observationId, finalLocation);
        int nextCorrVersion = corrigendumRepository.findMaxCorrVersion(observationId).orElse(0) + 1;
        return new CorrigendumEntry(observationId, nextCorrVersion, baseVersion, validated,
                reason, collector, corrKey, CorrigendumEntry.STATUS_VALID, Instant.now(clock));
    }

    /**
     * 计算应用全部有效附页后的有效值（按附页版本升序逐字段覆盖）。
     */
    private Map<String, String> computeEffective(String observationId, ObservationSnapshot current) {
        Map<String, String> effective = new HashMap<>();
        effective.put("location", current.location());
        effective.put("reading", current.reading());
        effective.put("note", current.note());
        for (CorrigendumEntry entry : corrigendumRepository.findByObservationId(observationId)) {
            if (!CorrigendumEntry.STATUS_VALID.equals(entry.status())) {
                continue;
            }
            entry.diffs().forEach((field, diff) -> effective.put(field, diff.to()));
        }
        return effective;
    }

    /**
     * 校验字段差异：空差异、未知字段、空更正值、读数格式、长度越界、无实际变化的字段均 422。
     * 返回按固定字段顺序排列的原值/更正值映射（from 为提交前有效原值）。
     */
    private Map<String, FieldDiff> validateDiffs(Map<String, String> rawDiffs, Map<String, String> effective) {
        if (rawDiffs == null || rawDiffs.isEmpty()) {
            throw ApiException.unprocessable("corrigendum diffs must not be empty");
        }
        Map<String, String> byField = new HashMap<>();
        for (Map.Entry<String, String> raw : rawDiffs.entrySet()) {
            String field = raw.getKey() == null ? null : raw.getKey().trim();
            if (field == null || !FIELDS.contains(field)) {
                throw ApiException.unprocessable("unknown corrigendum field: " + raw.getKey());
            }
            byField.put(field, raw.getValue());
        }
        Map<String, FieldDiff> validated = new LinkedHashMap<>();
        for (String field : FIELDS) {
            if (!byField.containsKey(field)) {
                continue;
            }
            String value = byField.get(field);
            if (value == null || value.isBlank()) {
                throw ApiException.unprocessable("corrected value for field '" + field + "' must not be blank");
            }
            switch (field) {
                case "location" -> {
                    if (value.length() > 512) {
                        throw ApiException.unprocessable("corrected location exceeds 512 characters");
                    }
                }
                case "reading" -> {
                    if (value.length() > 64 || !READING_PATTERN.matcher(value).matches()) {
                        throw ApiException.unprocessable(
                                "corrected reading must be a decimal string with at most 3 fraction digits");
                    }
                }
                case "note" -> {
                    if (value.length() > 1024) {
                        throw ApiException.unprocessable("corrected note exceeds 1024 characters");
                    }
                }
                default -> throw new IllegalStateException("unexpected field: " + field);
            }
            String currentEffective = effective.get(field);
            boolean unchanged = "reading".equals(field)
                    ? new BigDecimal(value).compareTo(new BigDecimal(currentEffective)) == 0
                    : Objects.equals(value, currentEffective);
            if (unchanged) {
                throw ApiException.unprocessable(
                        "diff for field '" + field + "' has no change against the effective value");
            }
            validated.put(field, new FieldDiff(currentEffective, value));
        }
        return validated;
    }

    /**
     * 最终坐标边界校验：应用差异后的地点必须是 "lat,lon" 十进制坐标，
     * 纬度 ∈ [-90, 90]，经度 ∈ [-180, 180]，否则 422。
     */
    private void validateFinalCoordinates(String observationId, String finalLocation) {
        java.util.regex.Matcher matcher = COORDINATE_PATTERN.matcher(
                finalLocation == null ? "" : finalLocation);
        if (!matcher.matches()) {
            throw ApiException.unprocessable(
                    "final coordinates of observation " + observationId
                            + " must be 'lat,lon' decimal degrees, got: " + finalLocation);
        }
        BigDecimal latitude = new BigDecimal(matcher.group(1));
        BigDecimal longitude = new BigDecimal(matcher.group(3));
        if (latitude.abs().compareTo(MAX_LATITUDE) > 0
                || longitude.abs().compareTo(MAX_LONGITUDE) > 0) {
            throw ApiException.unprocessable(
                    "final coordinates out of bounds for observation " + observationId
                            + ": lat must be within [-90, 90], lon within [-180, 180], got: " + finalLocation);
        }
    }

    /**
     * 已人工裁决的观测再发生附页提交/撤销时生成待复审标记；裁决结果本身冻结不改写。
     */
    private void flagIfAdjudicated(String observationId, int corrVersion, String event, Instant now) {
        resolutionRepository.findLatestByObservationId(observationId)
                .ifPresent(resolution -> corrigendumRepository.insertReviewFlag(
                        observationId, resolution.resolutionId(), corrVersion, event, now));
    }

    private void requireObservation(String observationId) {
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
    }

    // ---------- 幂等与指纹 ----------

    /**
     * 差异值规范化：读数按数值规范化（去尾随零），其余字段原文；用于指纹与存储前比较。
     */
    private Map<String, String> normalizeDiffValues(Map<String, String> rawDiffs) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (rawDiffs == null) {
            return normalized;
        }
        for (String field : FIELDS) {
            String value = null;
            for (Map.Entry<String, String> raw : rawDiffs.entrySet()) {
                if (raw.getKey() != null && raw.getKey().trim().equals(field)) {
                    value = raw.getValue();
                    break;
                }
            }
            if (value == null) {
                continue;
            }
            if ("reading".equals(field) && READING_PATTERN.matcher(value).matches()) {
                normalized.put(field, new BigDecimal(value).stripTrailingZeros().toPlainString());
            } else {
                normalized.put(field, value);
            }
        }
        return normalized;
    }

    private String writeNormalizedDiffs(Map<String, String> normalizedDiffs) {
        StringBuilder raw = new StringBuilder();
        normalizedDiffs.forEach((field, value) ->
                raw.append(field).append('=').append(value).append(SEPARATOR));
        return raw.toString();
    }

    private String batchFingerprintParts(List<BatchCorrigendumRequest.Item> items) {
        StringBuilder raw = new StringBuilder();
        for (BatchCorrigendumRequest.Item item : items) {
            raw.append(item.observationId()).append(SEPARATOR)
                    .append(item.baseVersion()).append(SEPARATOR)
                    .append(writeNormalizedDiffs(normalizeDiffValues(item.diffs())))
                    .append(item.reason()).append(SEPARATOR)
                    .append(item.collector()).append(SEPARATOR);
        }
        return raw.toString();
    }

    private CorrOutcome checkCorrReplay(String corrKey, String fingerprint) {
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

    private CorrOutcome insertCorrPlaceholder(String corrKey, String fingerprint, String operation) {
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

    private CorrOutcome completeCorr(String corrKey, HttpStatus status, CorrigendumResponse body) {
        requestLogRepository.complete(corrKey, status.value(), writeJson(body));
        return new CorrOutcome(status.value(), body);
    }

    private BatchOutcome checkBatchReplay(String corrKey, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(corrKey);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("corrKey reused with different parameters: " + corrKey, null);
        }
        return new BatchOutcome(entry.responseStatus(), readBatchBody(entry.responseBody()));
    }

    private BatchOutcome insertBatchPlaceholder(String corrKey, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(corrKey, fingerprint, "CORRIGENDUM_BATCH");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(corrKey)
                    .orElseThrow(() -> ApiException.conflict("corrKey conflict: " + corrKey, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("corrKey reused with different parameters: " + corrKey, null);
            }
            return new BatchOutcome(entry.responseStatus(), readBatchBody(entry.responseBody()));
        }
    }

    private BatchOutcome completeBatch(String corrKey, HttpStatus status, List<CorrigendumResponse> body) {
        requestLogRepository.complete(corrKey, status.value(), writeJson(body));
        return new BatchOutcome(status.value(), body);
    }

    private RevokeOutcome checkRevokeReplay(String corrKey, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(corrKey);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("corrKey reused with different parameters: " + corrKey, null);
        }
        return new RevokeOutcome(entry.responseStatus(), readRevokeBody(entry.responseBody()));
    }

    private RevokeOutcome insertRevokePlaceholder(String corrKey, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(corrKey, fingerprint, "REVOKE");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(corrKey)
                    .orElseThrow(() -> ApiException.conflict("corrKey conflict: " + corrKey, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("corrKey reused with different parameters: " + corrKey, null);
            }
            return new RevokeOutcome(entry.responseStatus(), readRevokeBody(entry.responseBody()));
        }
    }

    private RevokeOutcome completeRevoke(String corrKey, HttpStatus status, RevocationResponse body) {
        requestLogRepository.complete(corrKey, status.value(), writeJson(body));
        return new RevokeOutcome(status.value(), body);
    }

    private String fingerprint(String operation, String... parts) {
        StringBuilder raw = new StringBuilder(operation);
        for (String part : parts) {
            raw.append(SEPARATOR).append(part == null ? "<null>" : part);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(raw.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
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
            return objectMapper.readValue(json, new TypeReference<List<CorrigendumResponse>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored batch response", e);
        }
    }

    private RevocationResponse readRevokeBody(String json) {
        try {
            return objectMapper.readValue(json, RevocationResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored revocation response", e);
        }
    }

    /**
     * 单条附页提交结果：HTTP 状态码与附页响应体。
     */
    public record CorrOutcome(int status, CorrigendumResponse body) {
    }

    /**
     * 批量附页提交结果：HTTP 状态码与全部附页响应体（按请求条目顺序）。
     */
    public record BatchOutcome(int status, List<CorrigendumResponse> body) {
    }

    /**
     * 附页撤销结果：HTTP 状态码与撤销响应体。
     */
    public record RevokeOutcome(int status, RevocationResponse body) {
    }
}
