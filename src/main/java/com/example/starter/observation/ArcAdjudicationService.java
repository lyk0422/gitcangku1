package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 圆弧裁定服务：引用 2 至 10 条圆弧观测记录的当前版本，求全部圆弧交集并冻结不可变裁定。
 *
 * <p>裁定与合并、删除、人工裁决共同核对全部版本：事务内对每条引用记录加行锁（按 observationId
 * 升序，避免死锁），引用版本不是当前版本返回 409；引用墓碑或存在未解决冲突的记录返回 422；
 * 交集为空返回 422 且不留裁定。
 *
 * <p>成功裁定原子写入：裁定头、全部引用源（字段身份与源版本）、冻结的原始圆弧分量与全部交集分量
 * （相接的跨零片段已合为一个圆弧，按起始角升序），后续修订不倒改旧结果。
 *
 * <p>幂等：全局 requestId 结构化指纹（操作、资源、全参数及期望版本）同参重放、改参 409、失败不占键；
 * adjudicationId 全局唯一，同参重放首次完整结果、改参 409。
 */
@Service
public class ArcAdjudicationService {

    private final ArcObservationRepository arcRepository;
    private final ArcAdjudicationRepository adjudicationRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ArcAdjudicationService(ArcObservationRepository arcRepository,
                                  ArcAdjudicationRepository adjudicationRepository,
                                  RequestLogRepository requestLogRepository,
                                  ObjectMapper objectMapper,
                                  Clock clock) {
        this.arcRepository = arcRepository;
        this.adjudicationRepository = adjudicationRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 生成裁定：核对全部引用版本后求交集，成功则冻结完整裁定结果（201）；
     * 同 adjudicationId 同参重放返回首次结果（200），改参 409。
     */
    @Transactional
    public AdjudicateOutcome adjudicate(AdjudicateArcRequest request) {
        List<ArcSourceRef> references = normalizeReferences(request.references());
        String fingerprint = Fingerprints.of("ARC_ADJUDICATE", request.adjudicationId(),
                request.operator(), referencesString(references));
        AdjudicateOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        AdjudicateOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        // adjudicationId 重放判定先于业务校验：同参重放始终返回首次完整结果，改参一律 409
        AdjudicationRecord existing = adjudicationRepository.findById(request.adjudicationId()).orElse(null);
        if (existing != null) {
            if (!sameParams(existing, request.operator(), references)) {
                throw ApiException.conflict(
                        "adjudicationId reused with different parameters: " + request.adjudicationId(), null);
            }
            return complete(request.requestId(), HttpStatus.OK, AdjudicationResponse.of(existing));
        }

        List<AdjudicationRecord.AdjudicationSource> sources = new ArrayList<>();
        List<List<ArcComponent>> arcSets = new ArrayList<>();
        for (ArcSourceRef reference : references) {
            ArcObservationSnapshot current = arcRepository.findCurrentForUpdate(reference.observationId())
                    .orElseThrow(() -> ApiException.notFound(
                            "arc observation not found: " + reference.observationId()));
            if (current.version() != reference.version()) {
                throw ApiException.conflict("referenced version is not current: "
                        + reference.observationId() + "@" + reference.version(), current.version());
            }
            if (current.deleted()) {
                throw ApiException.unprocessableEntity(
                        "referenced observation is a tombstone: " + reference.observationId());
            }
            if (current.conflictPending()) {
                throw ApiException.unprocessableEntity(
                        "referenced observation has unresolved conflict: " + reference.observationId());
            }
            sources.add(new AdjudicationRecord.AdjudicationSource(
                    reference.observationId(), reference.version(), current.arcs()));
            arcSets.add(current.arcs());
        }

        List<ArcComponent> components = ArcGeometry.intersectAll(arcSets);
        if (components.isEmpty()) {
            // 无交集：422 且不留裁定，占位随事务回滚不占键
            throw ApiException.unprocessableEntity("arc intersection is empty; adjudication discarded");
        }

        AdjudicationRecord record = new AdjudicationRecord(request.adjudicationId(), request.requestId(),
                request.operator(), Instant.now(clock), List.copyOf(sources), components);
        try {
            adjudicationRepository.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发下 adjudicationId 已被其他事务占用：重读后同参重放，否则 409
            AdjudicationRecord winner = adjudicationRepository.findById(request.adjudicationId())
                    .orElseThrow(() -> ApiException.conflict(
                            "adjudicationId conflict: " + request.adjudicationId(), null));
            if (!sameParams(winner, request.operator(), references)) {
                throw ApiException.conflict(
                        "adjudicationId reused with different parameters: " + request.adjudicationId(), null);
            }
            return complete(request.requestId(), HttpStatus.OK, AdjudicationResponse.of(winner));
        }
        return complete(request.requestId(), HttpStatus.CREATED, AdjudicationResponse.of(record));
    }

    /**
     * 按全局唯一 adjudicationId 查询不可变裁定记录；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public AdjudicationRecord getAdjudication(String adjudicationId) {
        return adjudicationRepository.findById(adjudicationId)
                .orElseThrow(() -> ApiException.notFound("adjudication not found: " + adjudicationId));
    }

    /**
     * 校验并规范化引用列表：逐条校验字段身份与源版本（缺失 400），按（observationId, version）升序排序、
     * 去除完全重复的引用；去重后不足 2 条返回 400。规范化后集合顺序不影响指纹与判定。
     */
    static List<ArcSourceRef> normalizeReferences(List<ArcSourceRef> raw) {
        if (raw == null || raw.isEmpty()) {
            throw ApiException.badRequest("references must contain at least 2 sources");
        }
        Map<String, ArcSourceRef> distinct = new LinkedHashMap<>();
        for (ArcSourceRef reference : raw) {
            if (reference == null || reference.observationId() == null || reference.observationId().isBlank()) {
                throw ApiException.badRequest("reference observationId must be non-blank");
            }
            if (reference.version() == null || reference.version() < 1) {
                throw ApiException.badRequest("reference version must be a positive integer: "
                        + reference.observationId());
            }
            ArcSourceRef normalized = new ArcSourceRef(reference.observationId(), reference.version());
            distinct.putIfAbsent(reference.observationId() + "@" + reference.version(), normalized);
        }
        List<ArcSourceRef> references = new ArrayList<>(distinct.values());
        references.sort(Comparator.comparing(ArcSourceRef::observationId)
                .thenComparingInt(ArcSourceRef::version));
        if (references.size() < 2) {
            throw ApiException.badRequest("references must contain at least 2 distinct sources");
        }
        return references;
    }

    /**
     * 判断本次请求参数与已存在裁定记录是否完全一致（操作者 + 引用源集合，顺序可变）。
     */
    private boolean sameParams(AdjudicationRecord record, String operator, List<ArcSourceRef> references) {
        if (!Objects.equals(record.operator(), operator)) {
            return false;
        }
        if (record.sources().size() != references.size()) {
            return false;
        }
        List<String> stored = record.sources().stream()
                .map(source -> source.observationId() + "@" + source.version())
                .sorted()
                .toList();
        List<String> requested = references.stream()
                .map(reference -> reference.observationId() + "@" + reference.version())
                .sorted()
                .toList();
        return stored.equals(requested);
    }

    /**
     * 引用列表的稳定字符串表示，用于幂等指纹（已排序，文本原样）。
     */
    static String referencesString(List<ArcSourceRef> references) {
        StringBuilder builder = new StringBuilder();
        for (ArcSourceRef reference : references) {
            builder.append('[').append(reference.observationId()).append('@').append(reference.version())
                    .append(']');
        }
        return builder.toString();
    }

    /**
     * 幂等检查：同键同参返回原成功结果；同键异参返回 409；无记录返回 null 继续执行。
     */
    private AdjudicateOutcome checkReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new AdjudicateOutcome(entry.responseStatus(), readBody(entry.responseBody()));
    }

    /**
     * 占位写入去重记录，语义与其他写操作一致：冲突后读已提交记录，同参重放、异参 409。
     */
    private AdjudicateOutcome insertPlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "ARC_ADJUDICATE");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new AdjudicateOutcome(entry.responseStatus(), readBody(entry.responseBody()));
        }
    }

    /**
     * 裁定成功后回填去重记录响应，与裁定冻结结果在同一事务提交。
     */
    private AdjudicateOutcome complete(String requestId, HttpStatus status, AdjudicationResponse body) {
        try {
            requestLogRepository.complete(requestId, status.value(), objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize adjudication response", e);
        }
        return new AdjudicateOutcome(status.value(), body);
    }

    private AdjudicationResponse readBody(String json) {
        try {
            return objectMapper.readValue(json, AdjudicationResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored adjudication response", e);
        }
    }

    /**
     * 裁定结果：HTTP 状态码与裁定响应体。
     */
    public record AdjudicateOutcome(int status, AdjudicationResponse body) {
    }
}
