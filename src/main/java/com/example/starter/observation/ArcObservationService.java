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
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 圆弧观测与圆周区间裁定业务服务。
 *
 * <p>圆弧观测的起始角与正向弧长是完整字段组：三方合并只整组接受或整组保留，
 * 不把一方提交的起始角与另一方提交的弧长拼组。创建/合并/裁定与普通观测共用
 * 同一套幂等去重机制：先占位写入 request_log，再在行锁内完成业务判定，
 * 成功回填响应并整体提交；任何业务失败都回滚，去重记录不占键。
 *
 * <p>裁定引用 2 至 10 条圆弧观测的当前版本：按观测标识排序逐条加行锁，
 * 与合并、删除、人工裁决互斥并共同核对全部版本；成功后原子写入不可变裁定记录，
 * 冻结源圆弧与全部交集分量，后续修订不倒改旧结果。无交集返回 422 且不留裁定。
 */
@Service
public class ArcObservationService {

    private static final String SEPARATOR = Character.toString((char) 1);

    private final ObservationRepository observationRepository;
    private final RequestLogRepository requestLogRepository;
    private final ArcAdjudicationRepository adjudicationRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ArcObservationService(ObservationRepository observationRepository,
                                 RequestLogRepository requestLogRepository,
                                 ArcAdjudicationRepository adjudicationRepository,
                                 ObjectMapper objectMapper,
                                 Clock clock) {
        this.observationRepository = observationRepository;
        this.requestLogRepository = requestLogRepository;
        this.adjudicationRepository = adjudicationRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 创建圆弧观测记录，初始版本为 1。记录已存在（任意类型）返回 409。
     */
    @Transactional
    public ObservationService.WriteOutcome create(CreateArcObservationRequest request) {
        CircularArc arc = CircularArc.ofInput(request.startAngle(), request.arcLength());
        String fingerprint = fingerprint("ARC_CREATE", request.observationId(),
                String.valueOf(arc.startAngle()), String.valueOf(arc.arcLength()), request.operator());
        ObservationService.WriteOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        ObservationService.WriteOutcome concurrent =
                insertPlaceholder(request.requestId(), fingerprint, "ARC_CREATE");
        if (concurrent != null) {
            return concurrent;
        }

        if (observationRepository.findCurrentForUpdate(request.observationId()).isPresent()) {
            throw ApiException.conflict("observation already exists: " + request.observationId(), null);
        }
        ObservationSnapshot snapshot = ObservationSnapshot.arc(request.observationId(), 1,
                arc.startAngle(), arc.arcLength(), false);
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
     * 圆弧离线提交：基于 baseVersion 的三方合并，圆弧作为完整字段组整组比较。
     * 合并结果与当前完全相同则不加版本，否则版本加一。
     */
    @Transactional
    public ObservationService.WriteOutcome merge(String observationId, MergeArcObservationRequest request) {
        CircularArc candidate = CircularArc.ofInput(request.startAngle(), request.arcLength());
        String fingerprint = fingerprint("ARC_MERGE", observationId, String.valueOf(request.baseVersion()),
                String.valueOf(candidate.startAngle()), String.valueOf(candidate.arcLength()), request.operator());
        ObservationService.WriteOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        ObservationService.WriteOutcome concurrent =
                insertPlaceholder(request.requestId(), fingerprint, "ARC_MERGE");
        if (concurrent != null) {
            return concurrent;
        }

        ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.gone("observation already deleted: " + observationId);
        }
        if (current.obsType() != ObservationType.ARC) {
            throw ApiException.unprocessable("observation is not an arc observation: " + observationId);
        }
        ObservationSnapshot base = observationRepository.findVersion(observationId, request.baseVersion())
                .orElseThrow(() -> ApiException.notFound(
                        "base version not found: " + observationId + "@" + request.baseVersion()));

        CircularArc baseArc = base.deleted() ? null : new CircularArc(base.startAngle(), base.arcLength());
        CircularArc currentArc = new CircularArc(current.startAngle(), current.arcLength());
        CircularArc merged;
        if (candidate.equals(baseArc)) {
            merged = currentArc;
        } else if (currentArc.equals(baseArc) || candidate.equals(currentArc)) {
            merged = candidate;
        } else {
            // 两边改为不同圆弧：整组冲突，不拼组起始角与弧长
            throw ApiException.mergeConflict(List.of("arc"), current.version());
        }
        if (merged.equals(currentArc)) {
            return complete(request.requestId(), HttpStatus.OK, ObservationResponse.of(current));
        }
        ObservationSnapshot next = ObservationSnapshot.arc(observationId, current.version() + 1,
                merged.startAngle(), merged.arcLength(), false);
        observationRepository.updateCurrent(next);
        observationRepository.insertVersion(next);
        return complete(request.requestId(), HttpStatus.OK, ObservationResponse.of(next));
    }

    // ---------- 幂等去重辅助（语义与 ObservationService 一致） ----------

    private ObservationService.WriteOutcome checkReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new ObservationService.WriteOutcome(entry.responseStatus(), readBody(entry.responseBody()));
    }

    private ObservationService.WriteOutcome insertPlaceholder(String requestId, String fingerprint,
                                                              String operation) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new ObservationService.WriteOutcome(entry.responseStatus(), readBody(entry.responseBody()));
        }
    }

    private ObservationService.WriteOutcome complete(String requestId, HttpStatus status,
                                                     ObservationResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeBody(body));
        return new ObservationService.WriteOutcome(status.value(), body);
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

    // ---------- 圆周区间裁定 ----------

    /**
     * 圆周区间裁定：引用 2 至 10 条圆弧观测的当前版本，求全部圆弧交集并原子写入不可变裁定记录。
     * 引用集合顺序可变（指纹按排序后的集合计算）；无交集返回 422 且不留裁定、不占 requestId。
     */
    @Transactional
    public AdjudicateOutcome adjudicate(AdjudicateArcsRequest request) {
        List<AdjudicateArcsRequest.ArcReference> references = normalizeReferences(request.references());
        String fingerprint = adjudicateFingerprint(request, references);
        AdjudicateOutcome replayed = checkAdjudicateReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        AdjudicateOutcome concurrent = insertAdjudicatePlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        // adjudicationId 重放判定先于版本与状态校验：同参重放始终返回原裁定（即使观测之后已修订或删除）
        ArcAdjudicationRecord existing = adjudicationRepository
                .findByAdjudicationId(request.adjudicationId()).orElse(null);
        if (existing != null) {
            if (!sameAdjudicationParams(existing, request, references)) {
                throw ApiException.conflict(
                        "adjudicationId reused with different parameters: " + request.adjudicationId(), null);
            }
            return completeAdjudicate(request.requestId(), HttpStatus.CREATED,
                    ArcAdjudicationResponse.of(existing));
        }

        // 按观测标识升序逐条加行锁（避免死锁），与合并、删除、人工裁决互斥，共同核对全部版本
        Map<String, ObservationSnapshot> currents = new LinkedHashMap<>();
        for (AdjudicateArcsRequest.ArcReference reference : references) {
            ObservationSnapshot current = observationRepository.findCurrentForUpdate(reference.observationId())
                    .orElseThrow(() -> ApiException.notFound(
                            "observation not found: " + reference.observationId()));
            currents.put(reference.observationId(), current);
        }
        List<ArcAdjudicationRecord.ArcSource> sources = new ArrayList<>();
        List<CircularArc> arcs = new ArrayList<>();
        for (AdjudicateArcsRequest.ArcReference reference : references) {
            ObservationSnapshot current = currents.get(reference.observationId());
            if (current.deleted()) {
                throw ApiException.unprocessable(
                        "observation already deleted: " + reference.observationId());
            }
            if (current.obsType() != ObservationType.ARC) {
                throw ApiException.unprocessable(
                        "observation is not an arc observation: " + reference.observationId());
            }
            if (current.version() != reference.version()) {
                throw ApiException.conflict(
                        "version is not current for observation: " + reference.observationId(),
                        current.version());
            }
            sources.add(new ArcAdjudicationRecord.ArcSource(reference.observationId(), current.version(),
                    current.startAngle(), current.arcLength()));
            arcs.add(new CircularArc(current.startAngle(), current.arcLength()));
        }

        List<CircularArc> components = CircularArcIntersection.intersectAll(arcs);
        if (components.isEmpty()) {
            // 无交集：422，不留裁定，占位随事务回滚不占键
            throw ApiException.unprocessable("arcs have no intersection");
        }

        ArcAdjudicationRecord record = new ArcAdjudicationRecord(
                request.adjudicationId(), request.requestId(), request.operator(),
                List.copyOf(sources), List.copyOf(components), Instant.now(clock));
        try {
            adjudicationRepository.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发下 adjudicationId 已被其他事务占用：重读后同参重放，否则 409
            ArcAdjudicationRecord winner = adjudicationRepository
                    .findByAdjudicationId(request.adjudicationId())
                    .orElseThrow(() -> ApiException.conflict(
                            "adjudicationId conflict: " + request.adjudicationId(), null));
            if (!sameAdjudicationParams(winner, request, references)) {
                throw ApiException.conflict(
                        "adjudicationId reused with different parameters: " + request.adjudicationId(), null);
            }
            return completeAdjudicate(request.requestId(), HttpStatus.CREATED,
                    ArcAdjudicationResponse.of(winner));
        }
        return completeAdjudicate(request.requestId(), HttpStatus.CREATED,
                ArcAdjudicationResponse.of(record));
    }

    /**
     * 按全局唯一 adjudicationId 查询不可变裁定记录；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public ArcAdjudicationRecord getAdjudication(String adjudicationId) {
        return adjudicationRepository.findByAdjudicationId(adjudicationId)
                .orElseThrow(() -> ApiException.notFound("adjudication not found: " + adjudicationId));
    }

    /**
     * 规范化引用列表：按（观测标识、版本）升序排序，保证同一引用集合任意顺序得到相同指纹；
     * 同一观测被重复引用（版本相同或不同）视为未解决冲突，返回 422。
     */
    private List<AdjudicateArcsRequest.ArcReference> normalizeReferences(
            List<AdjudicateArcsRequest.ArcReference> references) {
        List<AdjudicateArcsRequest.ArcReference> sorted = new ArrayList<>(references);
        sorted.sort(Comparator.comparing(AdjudicateArcsRequest.ArcReference::observationId)
                .thenComparingInt(AdjudicateArcsRequest.ArcReference::version));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).observationId().equals(sorted.get(i - 1).observationId())) {
                throw ApiException.unprocessable(
                        "conflicting duplicate references for observation: " + sorted.get(i).observationId());
            }
        }
        return sorted;
    }

    /**
     * 裁定指纹：操作、裁定标识、操作者与排序后的全部引用（观测标识与期望版本），结构化比较全参数。
     */
    private String adjudicateFingerprint(AdjudicateArcsRequest request,
                                         List<AdjudicateArcsRequest.ArcReference> references) {
        List<String> parts = new ArrayList<>();
        parts.add(request.adjudicationId());
        parts.add(request.operator());
        for (AdjudicateArcsRequest.ArcReference reference : references) {
            parts.add(reference.observationId());
            parts.add(String.valueOf(reference.version()));
        }
        return fingerprint("ADJUDICATE", parts.toArray(new String[0]));
    }

    /**
     * 判断本次请求参数与已存在裁定记录是否完全一致（用于 adjudicationId 同参重放）：
     * 操作者相同且源观测标识与版本集合一致；源圆弧内容随版本确定，无需另行比较。
     */
    private boolean sameAdjudicationParams(ArcAdjudicationRecord record, AdjudicateArcsRequest request,
                                           List<AdjudicateArcsRequest.ArcReference> references) {
        if (!Objects.equals(record.operator(), request.operator())
                || record.sources().size() != references.size()) {
            return false;
        }
        // 记录中的源列表按观测标识升序落库，与规范化后的引用顺序一致
        for (int i = 0; i < references.size(); i++) {
            ArcAdjudicationRecord.ArcSource source = record.sources().get(i);
            AdjudicateArcsRequest.ArcReference reference = references.get(i);
            if (!source.observationId().equals(reference.observationId())
                    || source.version() != reference.version()) {
                return false;
            }
        }
        return true;
    }

    private AdjudicateOutcome checkAdjudicateReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new AdjudicateOutcome(entry.responseStatus(), readAdjudicateBody(entry.responseBody()));
    }

    private AdjudicateOutcome insertAdjudicatePlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "ADJUDICATE");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new AdjudicateOutcome(entry.responseStatus(), readAdjudicateBody(entry.responseBody()));
        }
    }

    private AdjudicateOutcome completeAdjudicate(String requestId, HttpStatus status,
                                                 ArcAdjudicationResponse body) {
        try {
            requestLogRepository.complete(requestId, status.value(), objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize adjudication response", e);
        }
        return new AdjudicateOutcome(status.value(), body);
    }

    private ArcAdjudicationResponse readAdjudicateBody(String json) {
        try {
            return objectMapper.readValue(json, ArcAdjudicationResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored adjudication response", e);
        }
    }

    /**
     * 裁定结果：HTTP 状态码与裁定响应体。
     */
    public record AdjudicateOutcome(int status, ArcAdjudicationResponse body) {
    }
}
