package com.example.starter.crosssection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 断面观测与水域面积成果业务服务。
 *
 * <p>断面号/横向毫米/高程毫米永远作为完整版本组一起版本化；写操作沿用占位幂等与行锁串行化：
 * request_log 先占位、业务失败随事务回滚（失败可复用键），成功后回填响应并与业务原子提交。
 * 整组合并冲突是“失败但需留痕”的唯一例外：未决标记由独立事务提交，随后外层事务回滚，不占 requestId。
 *
 * <p>成果创建在同一机制内核验 2 至 10 个同断面当前版本点（墓碑/未决/缺字段 422，旧版 409），
 * 严格按提交顺序核验横向严格递增，冻结完整点列、H 与精确分数面积；历史永不重算。
 */
@Service
public class CrossSectionService {

    private static final String SEPARATOR = "";

    /**
     * 完整版本组的固定字段顺序：冲突重算、选择校验与指纹均以此顺序处理。
     */
    private static final List<String> FIELDS = List.of("sectionNo", "xMm", "zMm");

    private final CrossSectionRepository repository;
    private final CrossSectionResolutionRepository resolutionRepository;
    private final CrossSectionResultRepository resultRepository;
    private final PendingConflictStore pendingConflictStore;
    private final com.example.starter.observation.RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CrossSectionService(CrossSectionRepository repository,
                               CrossSectionResolutionRepository resolutionRepository,
                               CrossSectionResultRepository resultRepository,
                               PendingConflictStore pendingConflictStore,
                               com.example.starter.observation.RequestLogRepository requestLogRepository,
                               ObjectMapper objectMapper,
                               Clock clock) {
        this.repository = repository;
        this.resolutionRepository = resolutionRepository;
        this.resultRepository = resultRepository;
        this.pendingConflictStore = pendingConflictStore;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 创建断面观测点，完整版本组初始版本为 1；标识已存在返回 409。
     */
    @Transactional
    public WriteOutcome create(CreateCrossSectionRequest request) {
        String fingerprint = fingerprint("XCREATE", request.observationId(), request.sectionNo(),
                String.valueOf(request.xMm()), String.valueOf(request.zMm()), request.operator());
        WriteOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        WriteOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "XCREATE");
        if (concurrent != null) {
            return concurrent;
        }

        if (repository.findCurrentForUpdate(request.observationId()).isPresent()) {
            throw CrossSectionApiException.conflict(
                    "cross section point already exists: " + request.observationId(), null);
        }
        CrossSectionSnapshot snapshot = new CrossSectionSnapshot(request.observationId(), 1,
                request.sectionNo(), request.xMm(), request.zMm(), false);
        try {
            repository.insertCurrent(snapshot);
        } catch (DuplicateKeyException e) {
            throw CrossSectionApiException.conflict(
                    "cross section point already exists: " + request.observationId(), null);
        }
        repository.insertVersion(snapshot);
        return complete(request.requestId(), HttpStatus.CREATED, CrossSectionResponse.of(snapshot));
    }

    /**
     * 离线提交：完整版本组的三方合并。结果与当前一致不加版本；任一字段冲突则写未决标记并整体 409。
     */
    @Transactional
    public WriteOutcome merge(String observationId, MergeCrossSectionRequest request) {
        String fingerprint = fingerprint("XMERGE", observationId, String.valueOf(request.baseVersion()),
                request.sectionNo(), String.valueOf(request.xMm()), String.valueOf(request.zMm()),
                request.operator());
        WriteOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        WriteOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "XMERGE");
        if (concurrent != null) {
            return concurrent;
        }

        CrossSectionSnapshot current = repository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> CrossSectionApiException.notFound(
                        "cross section point not found: " + observationId));
        if (current.deleted()) {
            throw CrossSectionApiException.unprocessable(
                    "cross section point already deleted: " + observationId);
        }
        CrossSectionSnapshot base = repository.findVersion(observationId, request.baseVersion())
                .orElseThrow(() -> CrossSectionApiException.notFound(
                        "base version not found: " + observationId + "@" + request.baseVersion()));

        List<String> conflictFields = new java.util.ArrayList<>();
        String mergedSectionNo = mergeField("sectionNo", base.sectionNo(), current.sectionNo(),
                request.sectionNo(), conflictFields);
        Integer mergedX = mergeIntField("xMm", base.xMm(), current.xMm(), request.xMm(), conflictFields);
        Integer mergedZ = mergeIntField("zMm", base.zMm(), current.zMm(), request.zMm(), conflictFields);
        if (!conflictFields.isEmpty()) {
            // 失败也要留未决痕跡：独立事务持久化标记后，外层事务回滚（占位不占键、不写业务版本）。
            pendingConflictStore.save(new PendingConflict(observationId, request.baseVersion(),
                    current.version(), request.sectionNo(), request.xMm(), request.zMm(),
                    List.copyOf(conflictFields), request.requestId()));
            throw CrossSectionApiException.mergeConflict(conflictFields, current.version());
        }

        CrossSectionSnapshot merged = new CrossSectionSnapshot(observationId, current.version(),
                mergedSectionNo, mergedX, mergedZ, false);
        if (sameContent(merged, current)) {
            return complete(request.requestId(), HttpStatus.OK, CrossSectionResponse.of(current));
        }
        CrossSectionSnapshot next = new CrossSectionSnapshot(observationId, current.version() + 1,
                mergedSectionNo, mergedX, mergedZ, false);
        repository.updateCurrent(next);
        repository.insertVersion(next);
        // 基于最新数据成功提交新版本后，旧基线遗留的未决标记随之失效。
        if (repository.findPendingConflict(observationId).isPresent()) {
            pendingConflictStore.remove(observationId);
        }
        return complete(request.requestId(), HttpStatus.OK, CrossSectionResponse.of(next));
    }

    /**
     * 删除断面观测点：expectedVersion 匹配当前版本时生成新版本墓碑，并清除未决冲突标记。
     */
    @Transactional
    public WriteOutcome delete(String observationId, DeleteCrossSectionRequest request) {
        String fingerprint = fingerprint("XDELETE", observationId,
                String.valueOf(request.expectedVersion()), request.operator());
        WriteOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        WriteOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "XDELETE");
        if (concurrent != null) {
            return concurrent;
        }

        CrossSectionSnapshot current = repository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> CrossSectionApiException.notFound(
                        "cross section point not found: " + observationId));
        if (current.deleted()) {
            throw CrossSectionApiException.unprocessable(
                    "cross section point already deleted: " + observationId);
        }
        if (request.expectedVersion() != current.version()) {
            throw CrossSectionApiException.conflict("expectedVersion mismatch", current.version());
        }
        CrossSectionSnapshot tombstone = new CrossSectionSnapshot(observationId, current.version() + 1,
                null, null, null, true);
        repository.markDeleted(observationId, tombstone.version());
        repository.insertVersion(tombstone);
        pendingConflictStore.remove(observationId);
        return complete(request.requestId(), HttpStatus.OK, CrossSectionResponse.of(tombstone));
    }

    /**
     * 查询当前内容；墓碑只返回删除状态和版本。
     */
    @Transactional(readOnly = true)
    public CrossSectionResponse getCurrent(String observationId) {
        CrossSectionSnapshot current = repository.findCurrent(observationId)
                .orElseThrow(() -> CrossSectionApiException.notFound(
                        "cross section point not found: " + observationId));
        return CrossSectionResponse.of(current);
    }

    /**
     * 查询指定历史版本；墓碑版本只返回删除状态和版本。
     */
    @Transactional(readOnly = true)
    public CrossSectionResponse getVersion(String observationId, int version) {
        CrossSectionSnapshot snapshot = repository.findVersion(observationId, version)
                .orElseThrow(() -> CrossSectionApiException.notFound(
                        "version not found: " + observationId + "@" + version));
        return CrossSectionResponse.of(snapshot);
    }

    /**
     * 查询未解决冲突标记；无标记返回 null。
     */
    @Transactional(readOnly = true)
    public PendingConflict getPendingConflict(String observationId) {
        return repository.findPendingConflict(observationId).orElse(null);
    }

    /**
     * 显式冲突裁决：事务内重读基线与最新当前版本重算冲突，选择恰好覆盖冲突字段时生成新版本（或无变化）、
     * 原子写入不可变裁决记录并清除未决标记。resolutionId 全局唯一，同参重放、改参 409。
     */
    @Transactional
    public ResolveOutcome resolveConflict(String observationId, ResolveCrossSectionRequest request) {
        java.util.Map<String, com.example.starter.observation.FieldSelection> selections =
                parseSelections(request.selections());
        String normalizedSelections = writeSelectionsJson(selections);
        String fingerprint = fingerprint("XRESOLVE", observationId, request.resolutionId(),
                String.valueOf(request.baseVersion()), String.valueOf(request.expectedCurrentVersion()),
                request.sectionNo(), String.valueOf(request.xMm()), String.valueOf(request.zMm()),
                normalizedSelections, request.operator());
        ResolveOutcome replayed = checkResolveReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        ResolveOutcome concurrent = insertResolvePlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        CrossSectionSnapshot current = repository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> CrossSectionApiException.notFound(
                        "cross section point not found: " + observationId));

        // resolutionId 重放判定先于墓碑与版本校验：同参重放始终返回原结果；改参或跨点复用一律 409。
        CrossSectionResolutionRecord existing =
                resolutionRepository.findByResolutionId(request.resolutionId()).orElse(null);
        if (existing != null) {
            if (!sameResolutionParams(existing, observationId, request, normalizedSelections)) {
                throw CrossSectionApiException.conflict(
                        "resolutionId reused with different parameters: " + request.resolutionId(),
                        current.version());
            }
            return completeResolve(request.requestId(), HttpStatus.OK, resolutionResponseOf(existing));
        }

        if (current.deleted()) {
            throw CrossSectionApiException.unprocessable(
                    "cross section point already deleted: " + observationId);
        }
        if (request.expectedCurrentVersion() != current.version()) {
            throw CrossSectionApiException.conflict("expectedCurrentVersion mismatch", current.version());
        }
        CrossSectionSnapshot base = repository.findVersion(observationId, request.baseVersion())
                .orElseThrow(() -> CrossSectionApiException.notFound(
                        "base version not found: " + observationId + "@" + request.baseVersion()));

        List<String> conflictFields = new java.util.ArrayList<>();
        boolean sectionConflict = fieldConflicts(base.sectionNo(), current.sectionNo(), request.sectionNo());
        if (sectionConflict) {
            conflictFields.add("sectionNo");
        }
        boolean xConflict = intFieldConflicts(base.xMm(), current.xMm(), request.xMm());
        if (xConflict) {
            conflictFields.add("xMm");
        }
        boolean zConflict = intFieldConflicts(base.zMm(), current.zMm(), request.zMm());
        if (zConflict) {
            conflictFields.add("zMm");
        }
        validateSelectionCoverage(selections.keySet(), conflictFields);

        String resolvedSectionNo = resolveValue(sectionConflict, selections.get("sectionNo"),
                base.sectionNo(), current.sectionNo(), request.sectionNo());
        Integer resolvedX = resolveIntValue(xConflict, selections.get("xMm"),
                base.xMm(), current.xMm(), request.xMm());
        Integer resolvedZ = resolveIntValue(zConflict, selections.get("zMm"),
                base.zMm(), current.zMm(), request.zMm());

        CrossSectionSnapshot merged = new CrossSectionSnapshot(observationId, current.version(),
                resolvedSectionNo, resolvedX, resolvedZ, false);
        boolean contentChanged = !sameContent(merged, current);
        int newVersion = contentChanged ? current.version() + 1 : current.version();
        if (contentChanged) {
            CrossSectionSnapshot next = new CrossSectionSnapshot(observationId, newVersion,
                    resolvedSectionNo, resolvedX, resolvedZ, false);
            repository.updateCurrent(next);
            repository.insertVersion(next);
        }

        CrossSectionResolutionRecord record = new CrossSectionResolutionRecord(
                request.resolutionId(), observationId, request.requestId(),
                request.baseVersion(), current.version(), newVersion,
                request.sectionNo(), request.xMm(), request.zMm(),
                List.copyOf(conflictFields), normalizedSelections, request.operator(),
                java.time.Instant.now(clock), contentChanged);
        try {
            resolutionRepository.insert(record);
        } catch (DuplicateKeyException e) {
            CrossSectionResolutionRecord winner =
                    resolutionRepository.findByResolutionId(request.resolutionId())
                            .orElseThrow(() -> CrossSectionApiException.conflict(
                                    "resolutionId conflict: " + request.resolutionId(), current.version()));
            if (!sameResolutionParams(winner, observationId, request, normalizedSelections)) {
                throw CrossSectionApiException.conflict(
                        "resolutionId reused with different parameters: " + request.resolutionId(),
                        current.version());
            }
            return completeResolve(request.requestId(), HttpStatus.OK, resolutionResponseOf(winner));
        }
        // 裁决落库成功后清除未决标记（独立事务，确保不被外层回滚恢复）。
        pendingConflictStore.remove(observationId);

        CrossSectionSnapshot pointed = contentChanged
                ? new CrossSectionSnapshot(observationId, newVersion,
                        resolvedSectionNo, resolvedX, resolvedZ, false)
                : current;
        return completeResolve(request.requestId(), HttpStatus.OK,
                CrossSectionResolutionResponse.of(record, pointed, objectMapper));
    }

    /**
     * 按 resolutionId 查询不可变裁决记录；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public CrossSectionResolutionRecord getResolution(String resolutionId) {
        return resolutionRepository.findByResolutionId(resolutionId)
                .orElseThrow(() -> CrossSectionApiException.notFound(
                        "resolution not found: " + resolutionId));
    }

    /**
     * 读取裁决记录指向的历史版本快照（供控制器组装响应）；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public CrossSectionSnapshot getVersionSnapshotForResolution(String observationId, int version) {
        return repository.findVersion(observationId, version)
                .orElseThrow(() -> CrossSectionApiException.notFound(
                        "version not found: " + observationId + "@" + version));
    }

    /**
     * 创建断面水域面积成果：行锁核验 2 至 10 个同断面当前版本点（墓碑/未决/缺字段 422，旧版 409），
     * 严格按提交顺序核验横向严格递增后，冻结完整点列、H 与精确分数面积并与去重记录原子提交。
     */
    @Transactional
    public ResultOutcome createResult(CreateCrossSectionResultRequest request) {
        String fingerprint = resultFingerprint(request);
        ResultOutcome replayed = checkResultReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        ResultOutcome concurrent = insertResultPlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        List<CreateCrossSectionResultRequest.SourcePointRef> refs = request.points();
        // 引用点重复或缺字段属于语义不可处理：422，且不排序、不接受原非法顺序。
        java.util.Set<String> distinctIds = new java.util.HashSet<>();
        for (CreateCrossSectionResultRequest.SourcePointRef ref : refs) {
            if (!distinctIds.add(ref.observationId())) {
                throw CrossSectionApiException.unprocessable(
                        "duplicate source point in result points: " + ref.observationId());
            }
        }

        // 按标识排序加行锁，避免不同请求以不同顺序引用同一组点时互相死锁；核验仍按提交顺序进行。
        List<String> lockOrder = refs.stream()
                .map(CreateCrossSectionResultRequest.SourcePointRef::observationId)
                .sorted().toList();
        java.util.Map<String, CrossSectionSnapshot> currents = new java.util.LinkedHashMap<>();
        for (String id : lockOrder) {
            currents.put(id, repository.findCurrentForUpdate(id).orElseThrow(() ->
                    CrossSectionApiException.notFound("cross section point not found: " + id)));
        }

        String sectionNo = null;
        List<CrossSectionSnapshot> ordered = new java.util.ArrayList<>();
        Integer previousX = null;
        for (CreateCrossSectionResultRequest.SourcePointRef ref : refs) {
            CrossSectionSnapshot current = currents.get(ref.observationId());
            if (current.deleted()) {
                throw CrossSectionApiException.unprocessable(
                        "source point is a tombstone: " + ref.observationId());
            }
            if (repository.findPendingConflict(ref.observationId()).isPresent()) {
                throw CrossSectionApiException.unprocessable(
                        "source point has unresolved conflict: " + ref.observationId());
            }
            if (ref.expectedVersion() != current.version()) {
                throw CrossSectionApiException.conflict(
                        "source point expectedVersion mismatch: " + ref.observationId(),
                        current.version());
            }
            if (sectionNo == null) {
                sectionNo = current.sectionNo();
            } else if (!sectionNo.equals(current.sectionNo())) {
                throw CrossSectionApiException.unprocessable(
                        "source points must belong to the same section; expected " + sectionNo
                                + " but point " + ref.observationId() + " belongs to " + current.sectionNo());
            }
            if (previousX != null && current.xMm() <= previousX) {
                // 必须按提交顺序严格递增；服务端不会排序后接受原非法顺序。
                throw CrossSectionApiException.unprocessable(
                        "x coordinates must be strictly increasing in submitted order; violation at point "
                                + ref.observationId() + " with x=" + current.xMm());
            }
            previousX = current.xMm();
            ordered.add(current);
        }

        List<CrossSectionPoint> geometryPoints = ordered.stream()
                .map(snapshot -> new CrossSectionPoint(snapshot.xMm(), snapshot.zMm()))
                .toList();
        Fraction area = CrossSectionGeometry.positiveArea(geometryPoints, request.waterLevelMm());

        CrossSectionResultRecord resultRecord = new CrossSectionResultRecord(
                request.resultId(), request.requestId(), fingerprint, sectionNo,
                request.waterLevelMm(), area.numerator().toString(), area.denominator().toString(),
                area.toScaledPlainString(3), ordered.size(), request.operator(),
                java.time.Instant.now(clock));
        try {
            resultRepository.insertResult(resultRecord);
        } catch (DuplicateKeyException e) {
            // resultId 已被其他事务占用：同参回放原成果，异参 409（唯一键竞争给业务结果）。
            CrossSectionResultRecord winner = resultRepository.findResult(request.resultId())
                    .orElseThrow(() -> CrossSectionApiException.conflict(
                            "resultId conflict: " + request.resultId(), null));
            if (!winner.fingerprint().equals(fingerprint)) {
                throw CrossSectionApiException.conflict(
                        "resultId reused with different parameters: " + request.resultId(), null);
            }
            return completeResult(request.requestId(), HttpStatus.OK,
                    loadResultResponse(winner));
        }
        for (int i = 0; i < ordered.size(); i++) {
            CrossSectionSnapshot snapshot = ordered.get(i);
            resultRepository.insertPoint(request.resultId(), new CrossSectionResultPointRecord(
                    i + 1, snapshot.observationId(), snapshot.version(), snapshot.xMm(), snapshot.zMm()));
        }
        return completeResult(request.requestId(), HttpStatus.CREATED,
                CrossSectionResultResponse.of(resultRecord,
                        resultRepository.findPoints(request.resultId()), false));
    }

    /**
     * 查询成果：数值始终取冻结点列与冻结面积；源点当前版本变化时仅置 sourceUpdated 提示，不重算。
     */
    @Transactional(readOnly = true)
    public CrossSectionResultResponse getResult(String resultId) {
        CrossSectionResultRecord record = resultRepository.findResult(resultId)
                .orElseThrow(() -> CrossSectionApiException.notFound("result not found: " + resultId));
        return loadResultResponse(record);
    }

    /**
     * 组装成果响应，并核对各冻结点的源当前版本是否已更新（墓碑/消失也视为已更新）。
     */
    private CrossSectionResultResponse loadResultResponse(CrossSectionResultRecord record) {
        List<CrossSectionResultPointRecord> points = resultRepository.findPoints(record.resultId());
        boolean sourceUpdated = false;
        for (CrossSectionResultPointRecord point : points) {
            CrossSectionSnapshot current = repository.findCurrent(point.sourceObservationId()).orElse(null);
            if (current == null || current.deleted() || current.version() != point.sourceVersion()) {
                sourceUpdated = true;
            }
        }
        return CrossSectionResultResponse.of(record, points, sourceUpdated);
    }

    // ---------- 整组三方合并辅助 ----------

    /**
     * 文本字段（断面号）三方合并：候选未改保留当前；当前未改接受候选；两边改为相同值接受；否则记录冲突。
     */
    private String mergeField(String field, String base, String current, String candidate,
                              List<String> conflictFields) {
        if (java.util.Objects.equals(candidate, base)) {
            return current;
        }
        if (java.util.Objects.equals(current, base) || java.util.Objects.equals(candidate, current)) {
            return candidate;
        }
        conflictFields.add(field);
        return current;
    }

    /**
     * 整数字段（横向/高程毫米）三方合并，按整数值比较。
     */
    private Integer mergeIntField(String field, Integer base, Integer current, Integer candidate,
                                  List<String> conflictFields) {
        if (java.util.Objects.equals(candidate, base)) {
            return current;
        }
        if (java.util.Objects.equals(current, base) || java.util.Objects.equals(candidate, current)) {
            return candidate;
        }
        conflictFields.add(field);
        return current;
    }

    /**
     * 判断完整版本组内容是否完全相同（断面号按原文，横向/高程按整数）。
     */
    private boolean sameContent(CrossSectionSnapshot merged, CrossSectionSnapshot current) {
        return java.util.Objects.equals(merged.sectionNo(), current.sectionNo())
                && java.util.Objects.equals(merged.xMm(), current.xMm())
                && java.util.Objects.equals(merged.zMm(), current.zMm());
    }

    private boolean fieldConflicts(String base, String current, String candidate) {
        if (java.util.Objects.equals(candidate, base)) {
            return false;
        }
        return !java.util.Objects.equals(current, base) && !java.util.Objects.equals(candidate, current);
    }

    private boolean intFieldConflicts(Integer base, Integer current, Integer candidate) {
        if (java.util.Objects.equals(candidate, base)) {
            return false;
        }
        return !java.util.Objects.equals(current, base) && !java.util.Objects.equals(candidate, current);
    }

    /**
     * 冲突字段按人工选择取当前或候选；非冲突字段继续按三方规则自动合并。
     */
    private String resolveValue(boolean conflict, com.example.starter.observation.FieldSelection selection,
                                String base, String current, String candidate) {
        if (conflict) {
            return selection == com.example.starter.observation.FieldSelection.CANDIDATE ? candidate : current;
        }
        return java.util.Objects.equals(candidate, base) ? current : candidate;
    }

    private Integer resolveIntValue(boolean conflict,
                                    com.example.starter.observation.FieldSelection selection,
                                    Integer base, Integer current, Integer candidate) {
        if (conflict) {
            return selection == com.example.starter.observation.FieldSelection.CANDIDATE ? candidate : current;
        }
        return java.util.Objects.equals(candidate, base) ? current : candidate;
    }

    /**
     * 解析选择映射：键必须是三个版本组字段之一，值仅允许 CURRENT/CANDIDATE；否则 400。
     */
    private java.util.Map<String, com.example.starter.observation.FieldSelection> parseSelections(
            java.util.Map<String, String> raw) {
        java.util.Map<String, com.example.starter.observation.FieldSelection> selections =
                new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, String> entry : raw.entrySet()) {
            String field = entry.getKey() == null ? null : entry.getKey().trim();
            if (field == null || !FIELDS.contains(field)) {
                throw CrossSectionApiException.badRequest(
                        "selection key must be one of sectionNo/xMm/zMm: " + field);
            }
            com.example.starter.observation.FieldSelection selection =
                    com.example.starter.observation.FieldSelection.fromValue(entry.getValue());
            if (selection == null) {
                throw CrossSectionApiException.badRequest(
                        "selection for field '" + field + "' must be CURRENT or CANDIDATE");
            }
            if (selections.put(field, selection) != null) {
                throw CrossSectionApiException.badRequest("duplicate selection for field: " + field);
            }
        }
        return selections;
    }

    /**
     * 将选择按固定字段顺序序列化为 JSON 原文，作为指纹与不可变记录的稳定表示。
     */
    private String writeSelectionsJson(
            java.util.Map<String, com.example.starter.observation.FieldSelection> selections) {
        java.util.Map<String, String> ordered = new java.util.LinkedHashMap<>();
        for (String field : FIELDS) {
            com.example.starter.observation.FieldSelection selection = selections.get(field);
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
     * 选择覆盖校验：选择键集合必须恰好等于本次重算出的冲突字段集合，遗漏或多选均拒绝。
     */
    private void validateSelectionCoverage(java.util.Set<String> selectedFields, List<String> conflictFields) {
        java.util.Set<String> conflictSet = java.util.Set.copyOf(conflictFields);
        if (!selectedFields.equals(conflictSet)) {
            throw CrossSectionApiException.badRequest(
                    "selections must cover exactly the recalculated conflict fields; current conflict fields: "
                            + String.join(", ", conflictFields));
        }
    }

    /**
     * 判断裁决请求参数与已存在裁决记录是否完全一致（含乐观锁版本，任何变化均按改参 409）。
     */
    private boolean sameResolutionParams(CrossSectionResolutionRecord record, String observationId,
                                         ResolveCrossSectionRequest request, String normalizedSelections) {
        return java.util.Objects.equals(record.observationId(), observationId)
                && record.baseVersion() == request.baseVersion()
                && record.previousVersion() == request.expectedCurrentVersion()
                && java.util.Objects.equals(record.candidateSectionNo(), request.sectionNo())
                && record.candidateXMm() == request.xMm()
                && record.candidateZMm() == request.zMm()
                && java.util.Objects.equals(record.fieldSelections(), normalizedSelections)
                && java.util.Objects.equals(record.operator(), request.operator());
    }

    /**
     * 依据已落库的不可变裁决记录重新组装响应（重放路径；指向版本必然已存在）。
     */
    private CrossSectionResolutionResponse resolutionResponseOf(CrossSectionResolutionRecord record) {
        CrossSectionSnapshot pointed = repository.findVersion(record.observationId(), record.newVersion())
                .orElseThrow(() -> new IllegalStateException(
                        "pointed version missing for resolution: " + record.resolutionId()));
        return CrossSectionResolutionResponse.of(record, pointed, objectMapper);
    }

    // ---------- 指纹与幂等 ----------

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

    /**
     * 成果请求结构化指纹：操作者、操作、资源（resultId）、全参数（H、按提交顺序的每点标识与期望版本）。
     * 文本（断面号不进入指纹，因其由同源点核验得出）；整数值以规范十进制形式参与。
     */
    private String resultFingerprint(CreateCrossSectionResultRequest request) {
        List<String> parts = new java.util.ArrayList<>();
        parts.add("XRESULT");
        parts.add(request.resultId());
        parts.add(request.operator());
        parts.add(String.valueOf(request.waterLevelMm()));
        parts.add(String.valueOf(request.points().size()));
        for (CreateCrossSectionResultRequest.SourcePointRef ref : request.points()) {
            parts.add(ref.observationId());
            parts.add(String.valueOf(ref.expectedVersion()));
        }
        return fingerprint(parts.get(0), parts.subList(1, parts.size()).toArray(String[]::new));
    }

    private WriteOutcome checkReplay(String requestId, String fingerprint) {
        Optional<com.example.starter.observation.RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        com.example.starter.observation.RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw CrossSectionApiException.conflict(
                    "requestId reused with different parameters: " + requestId, null);
        }
        return new WriteOutcome(entry.responseStatus(), readBody(entry.responseBody(), CrossSectionResponse.class));
    }

    /**
     * 占位写入去重记录；并发同键主键冲突后读已提交结果：同参重放、异参 409。业务失败随事务回滚不占键。
     */
    private WriteOutcome insertPlaceholder(String requestId, String fingerprint, String operation) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            com.example.starter.observation.RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> CrossSectionApiException.conflict(
                            "requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw CrossSectionApiException.conflict(
                        "requestId reused with different parameters: " + requestId, null);
            }
            return new WriteOutcome(entry.responseStatus(),
                    readBody(entry.responseBody(), CrossSectionResponse.class));
        }
    }

    private WriteOutcome complete(String requestId, HttpStatus status, CrossSectionResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeJson(body));
        return new WriteOutcome(status.value(), body);
    }

    private ResolveOutcome checkResolveReplay(String requestId, String fingerprint) {
        Optional<com.example.starter.observation.RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        com.example.starter.observation.RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw CrossSectionApiException.conflict(
                    "requestId reused with different parameters: " + requestId, null);
        }
        return new ResolveOutcome(entry.responseStatus(),
                readBody(entry.responseBody(), CrossSectionResolutionResponse.class));
    }

    private ResolveOutcome insertResolvePlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "XRESOLVE");
            return null;
        } catch (DuplicateKeyException e) {
            com.example.starter.observation.RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> CrossSectionApiException.conflict(
                            "requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw CrossSectionApiException.conflict(
                        "requestId reused with different parameters: " + requestId, null);
            }
            return new ResolveOutcome(entry.responseStatus(),
                    readBody(entry.responseBody(), CrossSectionResolutionResponse.class));
        }
    }

    private ResolveOutcome completeResolve(String requestId, HttpStatus status,
                                           CrossSectionResolutionResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeJson(body));
        return new ResolveOutcome(status.value(), body);
    }

    private ResultOutcome checkResultReplay(String requestId, String fingerprint) {
        Optional<com.example.starter.observation.RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        com.example.starter.observation.RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw CrossSectionApiException.conflict(
                    "requestId reused with different parameters: " + requestId, null);
        }
        return new ResultOutcome(entry.responseStatus(),
                readBody(entry.responseBody(), CrossSectionResultResponse.class));
    }

    private ResultOutcome insertResultPlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "XRESULT");
            return null;
        } catch (DuplicateKeyException e) {
            com.example.starter.observation.RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> CrossSectionApiException.conflict(
                            "requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw CrossSectionApiException.conflict(
                        "requestId reused with different parameters: " + requestId, null);
            }
            return new ResultOutcome(entry.responseStatus(),
                    readBody(entry.responseBody(), CrossSectionResultResponse.class));
        }
    }

    private ResultOutcome completeResult(String requestId, HttpStatus status,
                                         CrossSectionResultResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeJson(body));
        return new ResultOutcome(status.value(), body);
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }

    private <T> T readBody(String json, Class<T> bodyType) {
        try {
            return objectMapper.readValue(json, bodyType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored response", e);
        }
    }

    /**
     * 观测点写操作结果：HTTP 状态码与响应体。
     */
    public record WriteOutcome(int status, CrossSectionResponse body) {
    }

    /**
     * 冲突裁决结果：HTTP 状态码与裁决响应体。
     */
    public record ResolveOutcome(int status, CrossSectionResolutionResponse body) {
    }

    /**
     * 断面成果结果：HTTP 状态码与成果响应体。
     */
    public record ResultOutcome(int status, CrossSectionResultResponse body) {
    }
}
