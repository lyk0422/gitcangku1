package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 重复观测簇字段级溯源归并服务。
 *
 * <p>候选预览只读、不锁定、不落库、不后台聚类；成员集合完全由审核人提交的完整集合决定。
 * 归并确认在单事务内：request_log 占位 → 对全部成员行按记录键升序加锁（SELECT ... FOR UPDATE）
 * → 锁内重读 generation/墓碑/归并状态、重算时间范围与分组一致性、校验字段来源恰好覆盖三个业务字段
 * → 原子插入 generation=1 的 canonical 主记录、不可变簇/成员/字段证据 → 成员置 MERGED → 回填幂等响应。
 * 任一前置条件失败均整体回滚（409），失败不占键、不占 clusterKey。
 */
@Service
public class ClusterMergeService {

    private static final String SEPARATOR = "";

    /**
     * 三个可溯源业务字段的固定顺序。
     */
    private static final List<String> FIELDS = List.of("location", "reading", "note");

    /**
     * 题设允许的最大观测时刻跨度（含边界），单位秒。
     */
    private static final long MAX_WINDOW_SECONDS = 60;

    private final ObservationRepository observationRepository;
    private final ClusterRepository clusterRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;

    public ClusterMergeService(ObservationRepository observationRepository,
                               ClusterRepository clusterRepository,
                               RequestLogRepository requestLogRepository,
                               ObjectMapper objectMapper) {
        this.observationRepository = observationRepository;
        this.clusterRepository = clusterRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 候选预览：返回同 siteKey + type、观测时刻距锚点不超过窗口半宽的活跃、未归并、未删除记录。
     * 只读，不代表后台聚类结果。
     */
    @Transactional(readOnly = true)
    public ClusterPreviewResponse preview(ClusterPreviewRequest request) {
        int window = request.effectiveWindowSeconds();
        Instant anchor = request.observedAt();
        Instant from = anchor.minus(Duration.ofSeconds(window));
        Instant to = anchor.plus(Duration.ofSeconds(window));
        List<ClusterCandidateView> candidates = observationRepository
                .findActiveCandidates(request.siteKey(), request.type(), from, to).stream()
                .map(ClusterCandidateView::of)
                .toList();
        return new ClusterPreviewResponse(request.siteKey(), request.type(), anchor, window, candidates);
    }

    /**
     * 簇归并确认。成功后 canonical generation 从 1 开始，成员置 MERGED，证据不可变。
     */
    @Transactional
    public ClusterOutcome merge(ClusterMergeRequest request) {
        NormalizedSubmit normalized = normalize(request);
        String fingerprint = fingerprint(request, normalized);

        ClusterOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        ClusterOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        // 成员按记录键升序加锁，保证簇事务与单记录写事务之间的加锁顺序一致，避免死锁。
        List<String> orderedKeys = normalized.members().keySet().stream().sorted().toList();
        List<ObservationSnapshot> locked = observationRepository.findCurrentForUpdate(orderedKeys);
        Map<String, ObservationSnapshot> currentByKey = new LinkedHashMap<>();
        for (ObservationSnapshot snapshot : locked) {
            currentByKey.put(snapshot.observationId(), snapshot);
        }

        validate(request, normalized, currentByKey);

        // 锁内重算后的成员快照（保持提交顺序），用于冻结证据与字段取值。
        List<ObservationSnapshot> membersInOrder = orderedKeys.stream()
                .map(currentByKey::get)
                .sorted((a, b) -> Integer.compare(
                        normalized.ordinal(a.observationId()), normalized.ordinal(b.observationId())))
                .toList();

        String siteKey = membersInOrder.get(0).siteKey();
        String obsType = membersInOrder.get(0).obsType();
        Instant windowStart = membersInOrder.stream().map(ObservationSnapshot::observedAt).min(Instant::compareTo).orElseThrow();
        Instant windowEnd = membersInOrder.stream().map(ObservationSnapshot::observedAt).max(Instant::compareTo).orElseThrow();

        // 按字段来源从锁内成员快照取值。
        Map<String, String> resolvedValues = new LinkedHashMap<>();
        List<ClusterFieldSource> fieldSources = new ArrayList<>();
        for (String field : FIELDS) {
            String sourceKey = normalized.fieldSources().get(field);
            ObservationSnapshot source = currentByKey.get(sourceKey);
            String value = fieldValue(field, source);
            resolvedValues.put(field, value);
            fieldSources.add(new ClusterFieldSource(request.clusterKey(), field, sourceKey,
                    source.version(), value));
        }

        // canonical 主记录：generation 从 1 开始；分组沿用成员一致维度，观测时刻取重算范围上限（最新成员时刻）。
        ObservationSnapshot canonical = new ObservationSnapshot(
                request.canonicalRecordKey(), 1,
                resolvedValues.get("location"), resolvedValues.get("reading"), resolvedValues.get("note"),
                false, siteKey, obsType, windowEnd, null, MergeStatus.ACTIVE);
        try {
            observationRepository.insertCurrent(canonical);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict(
                    "canonical record key conflicts with an existing observation: " + request.canonicalRecordKey(),
                    null);
        }
        observationRepository.insertVersion(canonical);

        // 成员置 MERGED：仅改归并状态，generation（version）与内容冻结不变，不新增内容版本。
        for (String key : orderedKeys) {
            observationRepository.markMerged(key);
        }

        List<ClusterMemberEvidence> evidences = new ArrayList<>();
        for (int i = 0; i < membersInOrder.size(); i++) {
            ObservationSnapshot member = membersInOrder.get(i);
            evidences.add(new ClusterMemberEvidence(
                    request.clusterKey(), member.observationId(), member.version(), member.deviceId(),
                    member.observedAt(), member.location(), member.reading(), member.note(), i));
        }

        DuplicateCluster cluster = new DuplicateCluster(
                request.clusterKey(), request.canonicalRecordKey(), siteKey, obsType,
                windowStart, windowEnd, membersInOrder.size(), request.requestId());
        try {
            clusterRepository.insertCluster(cluster);
        } catch (DuplicateKeyException e) {
            // clusterKey 全局唯一：并发或异参复用一律 409（业务整体回滚）。
            throw ApiException.conflict("clusterKey already exists: " + request.clusterKey(), null);
        }
        for (ClusterMemberEvidence evidence : evidences) {
            clusterRepository.insertMember(evidence);
        }
        for (ClusterFieldSource source : fieldSources) {
            clusterRepository.insertFieldSource(source);
        }

        ClusterMergeResponse body = buildResponse(cluster, canonical, evidences, fieldSources);
        return complete(request.requestId(), HttpStatus.CREATED, body);
    }

    /**
     * 按 clusterKey 只读查询归并结果：主记录、成员冻结证据与字段级溯源；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public ClusterMergeResponse getCluster(String clusterKey) {
        DuplicateCluster cluster = clusterRepository.findByClusterKey(clusterKey)
                .orElseThrow(() -> ApiException.notFound("cluster not found: " + clusterKey));
        return assemble(cluster);
    }

    // ---------- 校验 ----------

    /**
     * 锁内全量前置校验。任一记录被更新（generation 变）、墓碑、已归并、分组不一致、时间窗超限、
     * 字段来源遗漏/多余/不在簇内、新主键冲突或形成归并链，均抛 409。
     */
    private void validate(ClusterMergeRequest request, NormalizedSubmit normalized,
                          Map<String, ObservationSnapshot> currentByKey) {
        // 提交成员必须全部存在。
        if (currentByKey.size() != normalized.members().size()) {
            List<String> missing = normalized.members().keySet().stream()
                    .filter(key -> !currentByKey.containsKey(key)).toList();
            throw ApiException.conflict("cluster member record(s) not found: " + missing, null);
        }

        // 新主记录键不得与成员键相同，也不得与任何既有观测键冲突（ACTIVE/MERGED/墓碑均算既有）。
        if (currentByKey.containsKey(request.canonicalRecordKey())) {
            throw ApiException.conflict(
                    "canonical record key must not be a cluster member: " + request.canonicalRecordKey(), null);
        }
        if (observationRepository.findCurrent(request.canonicalRecordKey()).isPresent()) {
            throw ApiException.conflict(
                    "canonical record key conflicts with an existing observation: " + request.canonicalRecordKey(),
                    null);
        }

        String siteKey = null;
        String obsType = null;
        Instant minTime = null;
        Instant maxTime = null;
        for (Map.Entry<String, Integer> entry : normalized.members().entrySet()) {
            ObservationSnapshot current = currentByKey.get(entry.getKey());

            if (current.version() != entry.getValue()) {
                throw ApiException.conflict(
                        "member generation changed since preview: " + entry.getKey()
                                + ", expected " + entry.getValue() + ", actual " + current.version(),
                        current.version());
            }
            if (current.deleted()) {
                throw ApiException.conflict("cluster member is tombstoned: " + entry.getKey(), current.version());
            }
            if (current.mergeStatus() == MergeStatus.MERGED) {
                throw ApiException.conflict("cluster member already merged: " + entry.getKey(), current.version());
            }
            if (current.siteKey() == null || current.obsType() == null || current.observedAt() == null) {
                throw ApiException.conflict(
                        "cluster member lacks siteKey/type/observedAt and cannot form a cluster: " + entry.getKey(),
                        current.version());
            }
            // 防止归并链环：已作为既有 canonical 产物的记录不得再次作为成员入簇。
            if (clusterRepository.existsClusterByCanonicalKey(entry.getKey())) {
                throw ApiException.conflict(
                        "cluster member is itself a canonical record; merge chaining is forbidden: "
                                + entry.getKey(), current.version());
            }

            if (siteKey == null) {
                siteKey = current.siteKey();
                obsType = current.obsType();
            } else if (!siteKey.equals(current.siteKey()) || !obsType.equals(current.obsType())) {
                throw ApiException.conflict(
                        "cluster members must share the same siteKey and type; mismatch at member: "
                                + entry.getKey(), current.version());
            }
            Instant observedAt = current.observedAt();
            if (minTime == null || observedAt.isBefore(minTime)) {
                minTime = observedAt;
            }
            if (maxTime == null || observedAt.isAfter(maxTime)) {
                maxTime = observedAt;
            }
        }

        long spreadSeconds = Duration.between(minTime, maxTime).toSeconds();
        if (spreadSeconds > MAX_WINDOW_SECONDS) {
            throw ApiException.conflict(
                    "member observedAt spread exceeds " + MAX_WINDOW_SECONDS + " seconds: " + spreadSeconds, null);
        }

        // 字段来源必须恰好覆盖三个业务字段，且每个来源都在簇内。
        Set<String> submittedFields = normalized.fieldSources().keySet();
        if (!submittedFields.equals(Set.copyOf(FIELDS))) {
            List<String> missing = new ArrayList<>(FIELDS);
            missing.removeAll(submittedFields);
            List<String> extra = new ArrayList<>(submittedFields);
            extra.removeAll(Set.copyOf(FIELDS));
            List<String> problems = new ArrayList<>();
            if (!missing.isEmpty()) {
                problems.add("missing field source(s): " + missing);
            }
            if (!extra.isEmpty()) {
                problems.add("unexpected field source(s): " + extra);
            }
            throw ApiException.conflict("field sources must cover exactly location/reading/note; "
                    + String.join("; ", problems), null);
        }
        for (Map.Entry<String, String> source : normalized.fieldSources().entrySet()) {
            if (!normalized.members().containsKey(source.getValue())) {
                throw ApiException.conflict(
                        "field '" + source.getKey() + "' source record is not within the cluster: "
                                + source.getValue(), null);
            }
        }
    }

    // ---------- 规范化与幂等 ----------

    /**
     * 规范化提交：成员去重并保留提交序号，字段来源收集为映射。重复成员键直接 409。
     */
    private NormalizedSubmit normalize(ClusterMergeRequest request) {
        Map<String, Integer> members = new LinkedHashMap<>();
        Map<String, Integer> ordinals = new LinkedHashMap<>();
        for (int i = 0; i < request.members().size(); i++) {
            ClusterMemberRef ref = request.members().get(i);
            if (members.put(ref.recordKey(), ref.generation()) != null) {
                throw ApiException.conflict("duplicate cluster member record key: " + ref.recordKey(), null);
            }
            ordinals.put(ref.recordKey(), i);
        }
        Map<String, String> fieldSources = new TreeMap<>();
        for (Map.Entry<String, String> entry : request.fieldSources().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getKey().isBlank()
                    || entry.getValue().isBlank()) {
                throw ApiException.badRequest("field source keys and values must be non-blank");
            }
            fieldSources.put(entry.getKey(), entry.getValue());
        }
        return new NormalizedSubmit(members, ordinals, fieldSources);
    }

    /**
     * 幂等指纹：clusterKey 与新主键作为请求参数纳入；成员集合按记录键排序（换序同参），
     * 字段映射按字段名排序（键比较），整体与提交顺序无关。
     */
    private String fingerprint(ClusterMergeRequest request, NormalizedSubmit normalized) {
        StringBuilder raw = new StringBuilder("DEDUP");
        raw.append(SEPARATOR).append(request.clusterKey());
        raw.append(SEPARATOR).append(request.canonicalRecordKey());
        for (String key : new TreeMap<>(normalized.members()).keySet()) {
            raw.append(SEPARATOR).append("M").append(key).append("@").append(normalized.members().get(key));
        }
        for (String field : new TreeMap<>(normalized.fieldSources()).keySet()) {
            raw.append(SEPARATOR).append("F").append(field).append("=").append(normalized.fieldSources().get(field));
        }
        return sha256(raw.toString());
    }

    private ClusterOutcome checkReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new ClusterOutcome(entry.responseStatus(), readBody(entry.responseBody()));
    }

    private ClusterOutcome insertPlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "DEDUP");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new ClusterOutcome(entry.responseStatus(), readBody(entry.responseBody()));
        }
    }

    private ClusterOutcome complete(String requestId, HttpStatus status, ClusterMergeResponse body) {
        try {
            requestLogRepository.complete(requestId, status.value(), objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize cluster response", e);
        }
        return new ClusterOutcome(status.value(), body);
    }

    private ClusterMergeResponse readBody(String json) {
        try {
            return objectMapper.readValue(json, ClusterMergeResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored cluster response", e);
        }
    }

    // ---------- 组装 ----------

    private ClusterMergeResponse buildResponse(DuplicateCluster cluster, ObservationSnapshot canonical,
                                               List<ClusterMemberEvidence> evidences,
                                               List<ClusterFieldSource> fieldSources) {
        List<ClusterMergeResponse.MemberView> memberViews = evidences.stream()
                .map(ClusterMergeResponse.MemberView::of).toList();
        List<ClusterMergeResponse.FieldSourceView> sourceViews = fieldSources.stream()
                .map(ClusterMergeResponse.FieldSourceView::of).toList();
        return new ClusterMergeResponse(cluster.clusterKey(), ObservationResponse.of(canonical),
                cluster.siteKey(), cluster.obsType(), cluster.windowStart(), cluster.windowEnd(),
                memberViews, sourceViews);
    }

    /**
     * 依据已落库的不可变簇记录组装只读响应（查询与重放路径使用）。
     */
    private ClusterMergeResponse assemble(DuplicateCluster cluster) {
        ObservationSnapshot canonicalSnapshot = observationRepository.findCurrent(cluster.canonicalRecordKey())
                .orElseThrow(() -> new IllegalStateException(
                        "canonical record missing for cluster: " + cluster.clusterKey()));
        List<ClusterMemberEvidence> evidences = clusterRepository.findMembers(cluster.clusterKey());
        List<ClusterFieldSource> sources = clusterRepository.findFieldSources(cluster.clusterKey());
        return buildResponse(cluster, canonicalSnapshot, evidences, sources);
    }

    private String fieldValue(String field, ObservationSnapshot source) {
        return switch (field) {
            case "location" -> source.location();
            case "reading" -> source.reading();
            case "note" -> source.note();
            default -> throw ApiException.badRequest("unknown business field: " + field);
        };
    }

    private String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 规范化后的提交内容：成员（键→冻结 generation）、成员提交序号、字段来源（字段→来源成员键）。
     */
    private record NormalizedSubmit(
            Map<String, Integer> members,
            Map<String, Integer> ordinals,
            Map<String, String> fieldSources) {

        int ordinal(String recordKey) {
            return ordinals.get(recordKey);
        }
    }

    /**
     * 簇归并结果：HTTP 状态码与响应体。
     */
    public record ClusterOutcome(int status, ClusterMergeResponse body) {
    }
}
