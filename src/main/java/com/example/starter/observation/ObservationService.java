package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 现场观测离线三方合并业务服务。
 *
 * <p>写操作（创建/离线提交/删除）均在单事务内完成：先占位写入幂等去重记录，
 * 再对 observation_current 行加锁（SELECT ... FOR UPDATE）完成业务判定与变更，
 * 最后回填去重记录的响应并整体提交；任何业务失败都会回滚，去重记录不占键。
 *
 * <p>合并规则（按字段比较基线 base、当前 current、候选 candidate）：
 * 候选未改（等于基线）保留当前；当前未改接受候选；两边改为相同值也接受；
 * 否则整次 409，不写入任何部分结果。读数按数值比较，其余字段按原文比较。
 *
 * <p>显式冲突解决（RESOLVE）在同一套占位与行锁机制内执行：事务内重读基线与最新当前版本重算冲突，
 * 人工选择必须恰好覆盖重算后的冲突字段；成功后原子写入新观测版本（内容无变化则不加版本）、
 * 不可变 conflict_resolution 记录与 request_log 响应。resolutionId 全局唯一，同参重放、改参 409。
 */
@Service
public class ObservationService {

    private static final String SEPARATOR = "\u0001";

    /**
     * 三个可编辑字段的固定顺序：冲突重算、选择校验与指纹均以此顺序处理，保证结果稳定。
     */
    private static final List<String> FIELDS = List.of("location", "reading", "note");

    private final ObservationRepository observationRepository;
    private final RequestLogRepository requestLogRepository;
    private final ResolutionRepository resolutionRepository;
    private final ClusterRepository clusterRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ObservationService(ObservationRepository observationRepository,
                              RequestLogRepository requestLogRepository,
                              ResolutionRepository resolutionRepository,
                              ClusterRepository clusterRepository,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.observationRepository = observationRepository;
        this.requestLogRepository = requestLogRepository;
        this.resolutionRepository = resolutionRepository;
        this.clusterRepository = clusterRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 创建观测记录，初始版本为 1。记录已存在返回 409。
     */
    @Transactional
    public WriteOutcome create(CreateObservationRequest request) {
        String fingerprint = fingerprint("CREATE", request.observationId(), request.location(),
                request.reading(), request.note());
        WriteOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        WriteOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "CREATE");
        if (concurrent != null) {
            return concurrent;
        }

        if (observationRepository.findCurrentForUpdate(request.observationId()).isPresent()) {
            throw ApiException.conflict("observation already exists: " + request.observationId(), null);
        }
        Instant observedAt = parseObservedAt(request.observedAt());
        ObservationSnapshot snapshot = new ObservationSnapshot(request.observationId(), 1,
                request.location(), request.reading(), request.note(), false,
                request.siteKey(), request.observationType(), observedAt, request.deviceId(),
                RecordStatus.ACTIVE, RecordOrigin.RAW, null);
        try {
            observationRepository.insertCurrent(snapshot);
        } catch (DuplicateKeyException e) {
            // 并发创建同一 observationId：由主键串行化，后到者按冲突处理
            throw ApiException.conflict("observation already exists: " + request.observationId(), null);
        }
        observationRepository.insertVersion(snapshot);
        return complete(request.requestId(), HttpStatus.CREATED, ObservationResponse.of(snapshot));
    }

    /**
     * 离线提交：基于 baseVersion 的三方合并。合并结果与当前完全相同则不加版本，否则版本加一。
     */
    @Transactional
    public WriteOutcome merge(String observationId, MergeObservationRequest request) {
        String fingerprint = fingerprint("MERGE", observationId, String.valueOf(request.baseVersion()),
                request.location(), request.reading(), request.note());
        WriteOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        WriteOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "MERGE");
        if (concurrent != null) {
            return concurrent;
        }

        ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.gone("observation already deleted: " + observationId);
        }
        if (current.status() == RecordStatus.MERGED) {
            // 已归并记录拒绝后续离线更新，也不得通过合并恢复为活跃
            throw ApiException.conflict(
                    "observation already merged into canonical record: " + current.mergedInto(),
                    current.version());
        }
        ObservationSnapshot base = observationRepository.findVersion(observationId, request.baseVersion())
                .orElseThrow(() -> ApiException.notFound(
                        "base version not found: " + observationId + "@" + request.baseVersion()));

        List<String> conflictFields = new ArrayList<>();
        String mergedLocation = mergeField("location", base.location(), current.location(),
                request.location(), conflictFields);
        String mergedReading = mergeReadingField(base.reading(), current.reading(),
                request.reading(), conflictFields);
        String mergedNote = mergeField("note", base.note(), current.note(), request.note(), conflictFields);
        if (!conflictFields.isEmpty()) {
            throw ApiException.mergeConflict(conflictFields, current.version());
        }

        ObservationSnapshot merged = new ObservationSnapshot(observationId, current.version(),
                mergedLocation, mergedReading, mergedNote, false,
                current.siteKey(), current.observationType(), current.observedAt(), current.deviceId(),
                current.status(), current.origin(), current.mergedInto());
        if (sameContent(merged, current)) {
            // 合并结果与当前完全相同：返回当前版本，不加版本
            return complete(request.requestId(), HttpStatus.OK, ObservationResponse.of(current));
        }
        ObservationSnapshot next = new ObservationSnapshot(observationId, current.version() + 1,
                mergedLocation, mergedReading, mergedNote, false,
                current.siteKey(), current.observationType(), current.observedAt(), current.deviceId(),
                current.status(), current.origin(), current.mergedInto());
        observationRepository.updateCurrent(next);
        observationRepository.insertVersion(next);
        return complete(request.requestId(), HttpStatus.OK, ObservationResponse.of(next));
    }

    /**
     * 删除观测记录：expectedVersion 匹配当前版本时生成新版本墓碑；已删除记录返回 410。
     */
    @Transactional
    public WriteOutcome delete(String observationId, DeleteObservationRequest request) {
        String fingerprint = fingerprint("DELETE", observationId, String.valueOf(request.expectedVersion()));
        WriteOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        WriteOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "DELETE");
        if (concurrent != null) {
            return concurrent;
        }

        ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.gone("observation already deleted: " + observationId);
        }
        if (current.status() == RecordStatus.MERGED) {
            // 已归并记录拒绝删除等后续变更，历史仍可查
            throw ApiException.conflict(
                    "observation already merged into canonical record: " + current.mergedInto(),
                    current.version());
        }
        if (request.expectedVersion() != current.version()) {
            throw ApiException.conflict("expectedVersion mismatch", current.version());
        }
        ObservationSnapshot tombstone = new ObservationSnapshot(observationId, current.version() + 1,
                null, null, null, true,
                current.siteKey(), current.observationType(), current.observedAt(), current.deviceId(),
                current.status(), current.origin(), current.mergedInto());
        observationRepository.markDeleted(observationId, tombstone.version());
        observationRepository.insertVersion(tombstone);
        return complete(request.requestId(), HttpStatus.OK, ObservationResponse.of(tombstone));
    }

    /**
     * 查询当前内容；墓碑只返回删除状态和版本。
     */
    @Transactional(readOnly = true)
    public ObservationResponse getCurrent(String observationId) {
        ObservationSnapshot current = observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        return ObservationResponse.of(current);
    }

    /**
     * 显式冲突解决：事务内重读基线与最新当前版本并按原三方规则重算冲突，不信任客户端上次看到的冲突列表。
     * 选择恰好覆盖本次仍冲突字段时生成新版本（内容无变化则不加版本）并原子写入不可变解决记录。
     */
    @Transactional
    public ResolveOutcome resolveConflict(String observationId, ResolveConflictRequest request) {
        Map<String, FieldSelection> selections = parseSelections(request.selections());
        String normalizedSelections = writeSelectionsJson(selections);
        String fingerprint = fingerprint("RESOLVE", observationId, request.resolutionId(),
                String.valueOf(request.baseVersion()), String.valueOf(request.expectedCurrentVersion()),
                request.location(), request.reading(), request.note(), normalizedSelections, request.operator());
        ResolveOutcome replayed = checkResolveReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        ResolveOutcome concurrent = insertResolvePlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));

        // resolutionId 重放判定先于墓碑与版本校验：同参重放始终返回原结果（即使记录之后已被删除）；
        // 改参或跨记录复用一律 409。
        ResolutionRecord existing = resolutionRepository.findByResolutionId(request.resolutionId()).orElse(null);
        if (existing != null) {
            if (!sameResolutionParams(existing, observationId, request, normalizedSelections)) {
                throw ApiException.conflict(
                        "resolutionId reused with different parameters: " + request.resolutionId(),
                        current.version());
            }
            return completeResolve(request.requestId(), HttpStatus.OK, resolutionResponseOf(existing));
        }

        if (current.deleted()) {
            throw ApiException.gone("observation already deleted: " + observationId);
        }
        if (current.status() == RecordStatus.MERGED) {
            // 已归并记录拒绝冲突解决与恢复
            throw ApiException.conflict(
                    "observation already merged into canonical record: " + current.mergedInto(),
                    current.version());
        }

        if (request.expectedCurrentVersion() != current.version()) {
            throw ApiException.conflict("expectedCurrentVersion mismatch", current.version());
        }
        ObservationSnapshot base = observationRepository.findVersion(observationId, request.baseVersion())
                .orElseThrow(() -> ApiException.notFound(
                        "base version not found: " + observationId + "@" + request.baseVersion()));

        List<String> conflictFields = new ArrayList<>();
        boolean locationConflict = fieldConflicts(base.location(), current.location(), request.location(), false);
        if (locationConflict) {
            conflictFields.add("location");
        }
        boolean readingConflict = fieldConflicts(base.reading(), current.reading(), request.reading(), true);
        if (readingConflict) {
            conflictFields.add("reading");
        }
        boolean noteConflict = fieldConflicts(base.note(), current.note(), request.note(), false);
        if (noteConflict) {
            conflictFields.add("note");
        }
        validateSelectionCoverage(selections.keySet(), conflictFields);

        String resolvedLocation = resolveFieldValue(locationConflict, selections.get("location"),
                base.location(), current.location(), request.location(), false);
        String resolvedReading = resolveFieldValue(readingConflict, selections.get("reading"),
                base.reading(), current.reading(), request.reading(), true);
        String resolvedNote = resolveFieldValue(noteConflict, selections.get("note"),
                base.note(), current.note(), request.note(), false);

        ObservationSnapshot merged = new ObservationSnapshot(observationId, current.version(),
                resolvedLocation, resolvedReading, resolvedNote, false,
                current.siteKey(), current.observationType(), current.observedAt(), current.deviceId(),
                current.status(), current.origin(), current.mergedInto());
        boolean contentChanged = !sameContent(merged, current);
        int newVersion = contentChanged ? current.version() + 1 : current.version();
        if (contentChanged) {
            ObservationSnapshot next = new ObservationSnapshot(observationId, newVersion,
                    resolvedLocation, resolvedReading, resolvedNote, false,
                    current.siteKey(), current.observationType(), current.observedAt(), current.deviceId(),
                    current.status(), current.origin(), current.mergedInto());
            observationRepository.updateCurrent(next);
            observationRepository.insertVersion(next);
        }

        ResolutionRecord record = new ResolutionRecord(
                request.resolutionId(), observationId, request.requestId(),
                request.baseVersion(), current.version(), newVersion,
                request.location(), request.reading(), request.note(),
                List.copyOf(conflictFields), normalizedSelections, request.operator(),
                Instant.now(clock), contentChanged);
        try {
            resolutionRepository.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发下 resolutionId 已被其他事务占用（可能指向其他观测记录）：重读后同参重放，否则 409。
            ResolutionRecord winner = resolutionRepository.findByResolutionId(request.resolutionId())
                    .orElseThrow(() -> ApiException.conflict(
                            "resolutionId conflict: " + request.resolutionId(), current.version()));
            if (!sameResolutionParams(winner, observationId, request, normalizedSelections)) {
                throw ApiException.conflict(
                        "resolutionId reused with different parameters: " + request.resolutionId(),
                        current.version());
            }
            return completeResolve(request.requestId(), HttpStatus.OK, resolutionResponseOf(winner));
        }

        ObservationSnapshot pointed = contentChanged
                ? new ObservationSnapshot(observationId, newVersion,
                        resolvedLocation, resolvedReading, resolvedNote, false,
                        current.siteKey(), current.observationType(), current.observedAt(), current.deviceId(),
                        current.status(), current.origin(), current.mergedInto())
                : current;
        return completeResolve(request.requestId(), HttpStatus.OK,
                ResolutionResponse.of(record, pointed, objectMapper));
    }

    /**
     * 按全局唯一 resolutionId 查询不可变解决记录；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public ResolutionRecord getResolution(String resolutionId) {
        return resolutionRepository.findByResolutionId(resolutionId)
                .orElseThrow(() -> ApiException.notFound("resolution not found: " + resolutionId));
    }

    /**
     * 按 observationId 查询解决历史，按解决时刻先后排序；观测记录不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<ResolutionRecord> listResolutions(String observationId) {
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        return resolutionRepository.findByObservationId(observationId);
    }

    /**
     * 读取指定历史版本快照（供解决记录响应组装使用）；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public ObservationSnapshot getVersionSnapshot(String observationId, int version) {
        return observationRepository.findVersion(observationId, version)
                .orElseThrow(() -> ApiException.notFound(
                        "version not found: " + observationId + "@" + version));
    }

    /**
     * 查询指定历史版本；墓碑版本只返回删除状态和版本。
     */
    @Transactional(readOnly = true)
    public ObservationResponse getVersion(String observationId, int version) {
        ObservationSnapshot snapshot = observationRepository.findVersion(observationId, version)
                .orElseThrow(() -> ApiException.notFound(
                        "version not found: " + observationId + "@" + version));
        return ObservationResponse.of(snapshot);
    }

    // ---------- 重复观测簇归并 ----------

    /**
     * 候选簇预览：只读冻结提交集合内每条记录的代次、设备、时间与字段值，不落库、不改状态。
     * 候选资格完全由提交的完整集合决定：键唯一、2-20 条、活跃未归并、非墓碑、RAW 来源、
     * 相同 siteKey/type、观测时间最大差不超过 60 秒；任一条不满足返回 409（键不存在为 404）。
     */
    @Transactional(readOnly = true)
    public ClusterPreviewResponse previewCluster(ClusterPreviewRequest request) {
        List<String> recordKeys = normalizeKeys(request.recordKeys());
        List<ObservationSnapshot> snapshots = loadClusterCandidates(recordKeys);
        return buildPreview(snapshots);
    }

    /**
     * 重复观测簇归并提交：校验冻结代次/状态/时间范围/字段来源后，原子创建 canonical 主记录、
     * 字段级不可变证据并把成员置 MERGED。任一成员在此期间更新、墓碑化或被归并，
     * 或字段遗漏/多选/来源越界，均整体 409 回滚，失败不占用 requestId/clusterKey。
     */
    @Transactional
    public ClusterOutcome commitCluster(ClusterMergeRequest request) {
        List<String> memberKeys = normalizeMemberKeys(request.members());
        Map<String, Integer> generationsByKey = normalizeGenerations(request.members(), memberKeys);
        Map<String, String> sourcesByField = normalizeFieldSources(request.fieldSources());

        String memberPart = memberKeys.stream()
                .map(key -> key + ":" + generationsByKey.get(key))
                .collect(java.util.stream.Collectors.joining(","));
        String sourcePart = sourcesByField.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(","));
        String fingerprint = fingerprint("CLUSTER_MERGE", request.clusterKey(), request.canonicalRecordId(),
                request.operator(), memberPart, sourcePart);

        ClusterOutcome replayed = checkClusterReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        ClusterOutcome concurrent = insertClusterPlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        // clusterKey 全局唯一：同参重放已在 request_log 阶段返回；任何已存在的簇均为异参冲突。
        if (clusterRepository.findHeader(request.clusterKey()).isPresent()) {
            throw ApiException.conflict("clusterKey already used: " + request.clusterKey(), null);
        }

        // 对全部成员按键排序加行锁，与离线合并/冲突解决/删除按提交顺序串行化，不丢已提交代次。
        List<ObservationSnapshot> members = observationRepository.findCurrentForUpdate(memberKeys);
        if (members.size() != memberKeys.size()) {
            throw ApiException.notFound("one or more cluster members do not exist: " + request.clusterKey());
        }
        Map<String, ObservationSnapshot> memberByKey = new LinkedHashMap<>();
        for (ObservationSnapshot member : members) {
            memberByKey.put(member.observationId(), member);
        }
        validateClusterCandidates(members);
        for (String key : memberKeys) {
            ObservationSnapshot member = memberByKey.get(key);
            if (member.version() != generationsByKey.get(key)) {
                // 预览后记录发生过更新：冻结代次失效，拒绝整次归并
                throw ApiException.conflict(
                        "member generation changed since preview: " + key, member.version());
            }
        }

        if (memberKeys.contains(request.canonicalRecordId())) {
            throw ApiException.conflict(
                    "canonical record key must not be a cluster member: " + request.canonicalRecordId(), null);
        }
        if (observationRepository.findCurrent(request.canonicalRecordId()).isPresent()) {
            // 新主记录键与既有记录键冲突（含已是其他簇 canonical 的键）
            throw ApiException.conflict(
                    "canonical record key already exists: " + request.canonicalRecordId(), null);
        }

        // 每个业务字段恰好选择一次，来源必须在簇成员内。
        if (!sourcesByField.keySet().equals(new java.util.HashSet<>(FIELDS))) {
            List<String> missing = new ArrayList<>(FIELDS);
            missing.removeAll(sourcesByField.keySet());
            List<String> extra = new ArrayList<>(sourcesByField.keySet());
            extra.removeAll(FIELDS);
            List<String> problems = new ArrayList<>();
            if (!missing.isEmpty()) {
                problems.add("missing field sources: " + String.join(", ", missing));
            }
            if (!extra.isEmpty()) {
                problems.add("unexpected field sources: " + String.join(", ", extra));
            }
            throw ApiException.conflict(
                    "every business field must be sourced exactly once; " + String.join("; ", problems), null);
        }
        for (Map.Entry<String, String> entry : sourcesByField.entrySet()) {
            if (!memberByKey.containsKey(entry.getValue())) {
                throw ApiException.conflict(
                        "source for field '" + entry.getKey() + "' is not a cluster member: "
                                + entry.getValue(), null);
            }
        }

        ObservationSnapshot anchor = members.get(0);
        Instant observedFrom = members.stream().map(ObservationSnapshot::observedAt).min(Instant::compareTo).orElseThrow();
        Instant observedTo = members.stream().map(ObservationSnapshot::observedAt).max(Instant::compareTo).orElseThrow();

        String canonicalLocation = memberByKey.get(sourcesByField.get("location")).location();
        String canonicalReading = memberByKey.get(sourcesByField.get("reading")).reading();
        String canonicalNote = memberByKey.get(sourcesByField.get("note")).note();

        ObservationSnapshot canonical = new ObservationSnapshot(request.canonicalRecordId(), 1,
                canonicalLocation, canonicalReading, canonicalNote, false,
                anchor.siteKey(), anchor.observationType(), observedFrom, null,
                RecordStatus.ACTIVE, RecordOrigin.CANONICAL, null);
        try {
            observationRepository.insertCurrent(canonical);
        } catch (DuplicateKeyException e) {
            // 并发下主记录键被其他事务抢先占用：整体回滚，不占键
            throw ApiException.conflict(
                    "canonical record key already exists: " + request.canonicalRecordId(), null);
        }
        observationRepository.insertVersion(canonical);

        for (String key : memberKeys) {
            observationRepository.markMerged(key, request.canonicalRecordId());
        }

        ClusterHeader header = new ClusterHeader(request.clusterKey(), request.canonicalRecordId(),
                anchor.siteKey(), anchor.observationType(), observedFrom, observedTo, members.size(),
                request.requestId(), request.operator(), Instant.now(clock));
        try {
            clusterRepository.insertHeader(header);
        } catch (DuplicateKeyException e) {
            // clusterKey 并发复用：数据库唯一约束兜底，整体回滚
            throw ApiException.conflict("clusterKey already used: " + request.clusterKey(), null);
        }
        for (String key : memberKeys) {
            ObservationSnapshot member = memberByKey.get(key);
            clusterRepository.insertMember(new ClusterMemberRecord(request.clusterKey(), key,
                    member.version(), member.siteKey(), member.observationType(), member.deviceId(),
                    member.observedAt(), member.location(), member.reading(), member.note()));
        }
        for (String field : FIELDS) {
            ObservationSnapshot source = memberByKey.get(sourcesByField.get(field));
            String value = switch (field) {
                case "location" -> source.location();
                case "reading" -> source.reading();
                default -> source.note();
            };
            clusterRepository.insertFieldSource(new ClusterFieldSourceRecord(
                    request.clusterKey(), field, source.observationId(), source.version(), value));
        }

        ClusterResponse body = ClusterResponse.of(header, canonical,
                clusterRepository.findMembers(request.clusterKey()),
                clusterRepository.findFieldSources(request.clusterKey()));
        return completeCluster(request.requestId(), HttpStatus.CREATED, body);
    }

    /**
     * 按 clusterKey 查询归并簇：返回主记录、成员冻结快照与字段级来源，只读。
     */
    @Transactional(readOnly = true)
    public ClusterResponse getCluster(String clusterKey) {
        ClusterHeader header = clusterRepository.findHeader(clusterKey)
                .orElseThrow(() -> ApiException.notFound("cluster not found: " + clusterKey));
        List<ClusterMemberRecord> memberRecords = clusterRepository.findMembers(clusterKey);
        List<ClusterFieldSourceRecord> sourceRecords = clusterRepository.findFieldSources(clusterKey);
        ObservationSnapshot canonical = observationRepository.findCurrent(header.canonicalRecordId())
                .orElseThrow(() -> new IllegalStateException(
                        "canonical record missing for cluster: " + clusterKey));
        return ClusterResponse.of(header, canonical, memberRecords, sourceRecords);
    }

    /**
     * 归并提交结果：HTTP 状态码与归并响应体。
     */
    public record ClusterOutcome(int status, ClusterResponse body) {
    }

    // ---------- 重复观测簇辅助逻辑 ----------

    /**
     * 归一化预览记录键：去空白判空、去重并按键排序；重复键按冲突处理（集合必须唯一）。
     */
    private List<String> normalizeKeys(List<String> rawKeys) {
        List<String> keys = rawKeys.stream()
                .map(key -> key == null ? null : key.trim())
                .peek(key -> {
                    if (key == null || key.isEmpty()) {
                        throw ApiException.badRequest("recordKey must not be blank");
                    }
                })
                .sorted()
                .toList();
        if (new java.util.HashSet<>(keys).size() != keys.size()) {
            throw ApiException.conflict("cluster record keys must be unique", null);
        }
        return keys;
    }

    /**
     * 归一化提交成员：记录键去重、排序；重复键（即使代次相同）按冲突处理。
     */
    private List<String> normalizeMemberKeys(List<ClusterMergeRequest.MemberGeneration> members) {
        List<String> keys = members.stream()
                .map(member -> member.recordKey().trim())
                .sorted()
                .toList();
        if (new java.util.HashSet<>(keys).size() != keys.size()) {
            throw ApiException.conflict("cluster members must be unique", null);
        }
        return keys;
    }

    /**
     * 提取“记录键 -> 冻结代次”映射，键顺序由已归一化的 memberKeys 决定。
     */
    private Map<String, Integer> normalizeGenerations(List<ClusterMergeRequest.MemberGeneration> members,
                                                      List<String> memberKeys) {
        Map<String, Integer> generations = new LinkedHashMap<>();
        for (ClusterMergeRequest.MemberGeneration member : members) {
            generations.put(member.recordKey().trim(), member.generation());
        }
        for (String key : memberKeys) {
            if (generations.get(key) == null || generations.get(key) < 1) {
                throw ApiException.badRequest("member generation must be positive: " + key);
            }
        }
        return generations;
    }

    /**
     * 归一化字段来源映射：键去空白并按字段名排序，来源键去空白；恰好覆盖校验在提交事务内完成。
     */
    private Map<String, String> normalizeFieldSources(Map<String, ClusterMergeRequest.FieldSource> raw) {
        Map<String, String> normalized = new java.util.TreeMap<>();
        for (Map.Entry<String, ClusterMergeRequest.FieldSource> entry : raw.entrySet()) {
            String field = entry.getKey() == null ? null : entry.getKey().trim();
            String source = entry.getValue() == null || entry.getValue().sourceRecordKey() == null
                    ? null : entry.getValue().sourceRecordKey().trim();
            if (field == null || field.isEmpty() || source == null || source.isEmpty()) {
                throw ApiException.badRequest("field source must name a field and a sourceRecordKey");
            }
            normalized.put(field, source);
        }
        return normalized;
    }

    /**
     * 只读加载候选集合并校验候选资格，返回按记录键排序的快照。
     */
    private List<ObservationSnapshot> loadClusterCandidates(List<String> recordKeys) {
        List<ObservationSnapshot> snapshots = new ArrayList<>();
        for (String key : recordKeys) {
            ObservationSnapshot snapshot = observationRepository.findCurrent(key)
                    .orElseThrow(() -> ApiException.notFound("observation not found: " + key));
            snapshots.add(snapshot);
        }
        snapshots.sort(java.util.Comparator.comparing(ObservationSnapshot::observationId));
        validateClusterCandidates(snapshots);
        return snapshots;
    }

    /**
     * 校验候选集合：均为活跃、未归并、非墓碑、RAW 来源，相同 siteKey/type，观测时间最大差不超过 60 秒。
     */
    private void validateClusterCandidates(List<ObservationSnapshot> snapshots) {
        String siteKey = snapshots.get(0).siteKey();
        String type = snapshots.get(0).observationType();
        for (ObservationSnapshot snapshot : snapshots) {
            if (snapshot.deleted()) {
                throw ApiException.conflict(
                        "cluster member is tombstoned: " + snapshot.observationId(), snapshot.version());
            }
            if (snapshot.status() == RecordStatus.MERGED) {
                throw ApiException.conflict(
                        "cluster member already merged: " + snapshot.observationId(), snapshot.version());
            }
            if (snapshot.origin() == RecordOrigin.CANONICAL) {
                // canonical 主记录不得再次入簇，防止形成归并链环
                throw ApiException.conflict(
                        "canonical record cannot be a cluster member: " + snapshot.observationId(),
                        snapshot.version());
            }
            if (!Objects.equals(siteKey, snapshot.siteKey()) || !Objects.equals(type, snapshot.observationType())) {
                throw ApiException.conflict(
                        "cluster members must share the same siteKey and type: " + snapshot.observationId(),
                        snapshot.version());
            }
            if (snapshot.observedAt() == null) {
                throw ApiException.conflict(
                        "cluster member has no observedAt: " + snapshot.observationId(), snapshot.version());
            }
        }
        Instant from = snapshots.stream().map(ObservationSnapshot::observedAt).min(Instant::compareTo).orElseThrow();
        Instant to = snapshots.stream().map(ObservationSnapshot::observedAt).max(Instant::compareTo).orElseThrow();
        if (Duration.between(from, to).compareTo(Duration.ofSeconds(60)) > 0) {
            throw ApiException.conflict(
                    "observedAt range of cluster members exceeds 60 seconds: "
                            + Duration.between(from, to).toSeconds() + "s", null);
        }
    }

    /**
     * 由候选快照组装只读预览：成员按记录键排序，时间范围取观测时刻的最小/最大值。
     */
    private ClusterPreviewResponse buildPreview(List<ObservationSnapshot> snapshots) {
        Instant from = snapshots.stream().map(ObservationSnapshot::observedAt).min(Instant::compareTo).orElseThrow();
        Instant to = snapshots.stream().map(ObservationSnapshot::observedAt).max(Instant::compareTo).orElseThrow();
        List<ClusterMemberPreview> members = snapshots.stream()
                .map(snapshot -> new ClusterMemberPreview(
                        snapshot.observationId(), snapshot.version(), snapshot.siteKey(),
                        snapshot.observationType(), snapshot.deviceId(), snapshot.observedAt(),
                        snapshot.location(), snapshot.reading(), snapshot.note()))
                .toList();
        return new ClusterPreviewResponse(snapshots.get(0).siteKey(), snapshots.get(0).observationType(),
                from, to, List.copyOf(members));
    }

    /**
     * 归并请求的 requestId 幂等检查：同键同参返回原成功结果；同键异参返回 409；无记录返回 null。
     */
    private ClusterOutcome checkClusterReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new ClusterOutcome(entry.responseStatus(), readClusterBody(entry.responseBody()));
    }

    /**
     * 归并请求的占位写入，语义与其他写操作一致：并发同键冲突后读已提交记录，同参重放、异参 409。
     */
    private ClusterOutcome insertClusterPlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "CLUSTER_MERGE");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new ClusterOutcome(entry.responseStatus(), readClusterBody(entry.responseBody()));
        }
    }

    /**
     * 归并成功后回填去重记录响应，与 canonical 主记录、成员状态变更、不可变证据同事务提交。
     */
    private ClusterOutcome completeCluster(String requestId, HttpStatus status, ClusterResponse body) {
        try {
            requestLogRepository.complete(requestId, status.value(), objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize cluster response", e);
        }
        return new ClusterOutcome(status.value(), body);
    }

    private ClusterResponse readClusterBody(String json) {
        try {
            return objectMapper.readValue(json, ClusterResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored cluster response", e);
        }
    }

    /**
     * 普通字段三方合并：候选未改保留当前；当前未改接受候选；两边改为相同值接受；否则记录冲突。
     */
    private String mergeField(String field, String base, String current, String candidate,
                              List<String> conflictFields) {
        if (Objects.equals(candidate, base)) {
            return current;
        }
        if (Objects.equals(current, base) || Objects.equals(candidate, current)) {
            return candidate;
        }
        conflictFields.add(field);
        return current;
    }

    /**
     * 读数字段三方合并：按数值比较（BigDecimal），其余规则与普通字段一致。
     */
    private String mergeReadingField(String base, String current, String candidate,
                                     List<String> conflictFields) {
        if (readingEquals(candidate, base)) {
            return current;
        }
        if (readingEquals(current, base) || readingEquals(candidate, current)) {
            return candidate;
        }
        conflictFields.add("reading");
        return current;
    }

    /**
     * 判断合并结果与当前版本内容是否完全相同（读数按数值，其余按原文）。
     */
    private boolean sameContent(ObservationSnapshot merged, ObservationSnapshot current) {
        return Objects.equals(merged.location(), current.location())
                && Objects.equals(merged.note(), current.note())
                && readingEquals(merged.reading(), current.reading());
    }

    private boolean readingEquals(String left, String right) {
        if (left == null || right == null) {
            return Objects.equals(left, right);
        }
        return new BigDecimal(left).compareTo(new BigDecimal(right)) == 0;
    }

    /**
     * 解析请求中的观测发生时刻：接受 ISO-8601（含偏移，如 2026-09-23T10:00:00Z），统一为 UTC Instant。
     */
    private Instant parseObservedAt(String raw) {
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("observedAt must be an ISO-8601 date-time with offset: " + raw);
        }
    }

    /**
     * 幂等检查：同键同参返回原成功结果；同键异参返回 409；无记录返回 null 继续执行。
     */
    private WriteOutcome checkReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new WriteOutcome(entry.responseStatus(), readBody(entry.responseBody()));
    }

    /**
     * 占位写入去重记录；并发同键时主键冲突，等待对方事务结束后读取已提交结果：
     * 同参返回重放结果，异参抛 409；正常占位返回 null。业务失败时占位随事务回滚，不占键。
     */
    private WriteOutcome insertPlaceholder(String requestId, String fingerprint, String operation) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new WriteOutcome(entry.responseStatus(), readBody(entry.responseBody()));
        }
    }

    /**
     * 业务成功后回填去重记录响应，并构造本次写操作结果；与业务变更同事务提交。
     */
    private WriteOutcome complete(String requestId, HttpStatus status, ObservationResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeBody(body));
        return new WriteOutcome(status.value(), body);
    }

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

    private String writeBody(ObservationResponse body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }

    private ObservationResponse readBody(String json) {
        try {
            return objectMapper.readValue(json, ObservationResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored response", e);
        }
    }

    // ---------- 冲突解决辅助逻辑 ----------

    /**
     * 解析并规范化选择映射：键必须是三个可编辑字段之一，值仅允许 CURRENT/CANDIDATE（大小写不敏感）；
     * 重复字段（JSON 反序列化不会出现）或非法键值一律 400。
     */
    private Map<String, FieldSelection> parseSelections(Map<String, String> raw) {
        Map<String, FieldSelection> selections = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String field = entry.getKey() == null ? null : entry.getKey().trim();
            if (field == null || !FIELDS.contains(field)) {
                throw ApiException.badRequest("selection key must be one of location/reading/note: " + field);
            }
            FieldSelection selection = FieldSelection.fromValue(entry.getValue());
            if (selection == null) {
                throw ApiException.badRequest(
                        "selection for field '" + field + "' must be CURRENT or CANDIDATE (BASE is not allowed)");
            }
            if (selections.put(field, selection) != null) {
                throw ApiException.badRequest("duplicate selection for field: " + field);
            }
        }
        return selections;
    }

    /**
     * 将选择按固定字段顺序序列化为 JSON 原文，作为指纹与不可变记录的稳定表示。
     */
    private String writeSelectionsJson(Map<String, FieldSelection> selections) {
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String field : FIELDS) {
            FieldSelection selection = selections.get(field);
            if (selection != null) {
                ordered.put(field, selection.name());
            }
        }
        try {
            return objectMapper.writeValueAsString(ordered);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize selections", e);
        }
    }

    /**
     * 按原三方规则判断单字段是否冲突：候选未改或任一侧未改或两边改为相同值均不冲突，否则冲突。
     */
    private boolean fieldConflicts(String base, String current, String candidate, boolean numeric) {
        if (valueEquals(candidate, base, numeric)) {
            return false;
        }
        if (valueEquals(current, base, numeric) || valueEquals(candidate, current, numeric)) {
            return false;
        }
        return true;
    }

    private boolean valueEquals(String left, String right, boolean numeric) {
        return numeric ? readingEquals(left, right) : Objects.equals(left, right);
    }

    /**
     * 选择覆盖校验：选择键集合必须恰好等于本次重算出的冲突字段集合。
     * 遗漏冲突字段、多选非冲突字段均拒绝（BASE 已在解析阶段拒绝）。
     */
    private void validateSelectionCoverage(Set<String> selectedFields, List<String> conflictFields) {
        Set<String> conflictSet = Set.copyOf(conflictFields);
        if (!selectedFields.equals(conflictSet)) {
            List<String> missing = new ArrayList<>(conflictFields);
            missing.removeAll(selectedFields);
            List<String> extra = new ArrayList<>(selectedFields);
            extra.removeAll(conflictSet);
            List<String> problems = new ArrayList<>();
            if (!missing.isEmpty()) {
                problems.add("missing selections for conflict fields: " + String.join(", ", missing));
            }
            if (!extra.isEmpty()) {
                problems.add("selections for non-conflict fields are not allowed: " + String.join(", ", extra));
            }
            throw ApiException.badRequest(
                    "selections must cover exactly the recalculated conflict fields; " + String.join("; ", problems)
                            + "; current conflict fields: " + String.join(", ", conflictFields));
        }
    }

    /**
     * 计算单字段解决结果：冲突字段按人工选择取当前或候选；非冲突字段继续按原三方规则自动合并。
     */
    private String resolveFieldValue(boolean conflict, FieldSelection selection,
                                     String base, String current, String candidate, boolean numeric) {
        if (conflict) {
            return selection == FieldSelection.CANDIDATE ? candidate : current;
        }
        if (valueEquals(candidate, base, numeric)) {
            return current;
        }
        return candidate;
    }

    /**
     * 解决请求的 requestId 幂等检查：同键同参返回原成功结果；同键异参返回 409；无记录返回 null。
     */
    private ResolveOutcome checkResolveReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new ResolveOutcome(entry.responseStatus(), readResolutionBody(entry.responseBody()));
    }

    /**
     * 解决请求的占位写入，语义与普通写操作一致：冲突后读已提交记录，同参重放、异参 409。
     */
    private ResolveOutcome insertResolvePlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "RESOLVE");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new ResolveOutcome(entry.responseStatus(), readResolutionBody(entry.responseBody()));
        }
    }

    /**
     * 解决成功后回填去重记录响应，与业务变更、解决记录在同一事务提交。
     */
    private ResolveOutcome completeResolve(String requestId, HttpStatus status, ResolutionResponse body) {
        try {
            requestLogRepository.complete(requestId, status.value(), objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize resolution response", e);
        }
        return new ResolveOutcome(status.value(), body);
    }

    private ResolutionResponse readResolutionBody(String json) {
        try {
            return objectMapper.readValue(json, ResolutionResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored resolution response", e);
        }
    }

    /**
     * 判断本次请求参数与已存在解决记录是否完全一致（用于 resolutionId 同参重放）。
     * expectedCurrentVersion 必须等于记录中的解决前当前版本：任何参数（含乐观锁版本）变化均按改参 409 处理。
     */
    private boolean sameResolutionParams(ResolutionRecord record, String observationId,
                                         ResolveConflictRequest request, String normalizedSelections) {
        return Objects.equals(record.observationId(), observationId)
                && record.baseVersion() == request.baseVersion()
                && record.previousVersion() == request.expectedCurrentVersion()
                && Objects.equals(record.candidateLocation(), request.location())
                && Objects.equals(record.candidateReading(), request.reading())
                && Objects.equals(record.candidateNote(), request.note())
                && Objects.equals(record.fieldSelections(), normalizedSelections)
                && Objects.equals(record.operator(), request.operator());
    }

    /**
     * 依据已落库的不可变记录重新组装响应（重放路径；指向版本必然已存在）。
     */
    private ResolutionResponse resolutionResponseOf(ResolutionRecord record) {
        ObservationSnapshot pointed = observationRepository.findVersion(
                record.observationId(), record.newVersion())
                .orElseThrow(() -> new IllegalStateException(
                        "pointed version missing for resolution: " + record.resolutionId()));
        return ResolutionResponse.of(record, pointed, objectMapper);
    }

    /**
     * 写操作结果：HTTP 状态码与响应体。
     */
    public record WriteOutcome(int status, ObservationResponse body) {
    }

    /**
     * 冲突解决结果：HTTP 状态码与解决响应体。
     */
    public record ResolveOutcome(int status, ResolutionResponse body) {
    }
}
