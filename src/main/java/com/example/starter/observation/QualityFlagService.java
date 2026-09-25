package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 观测质量标记业务服务：标记创建、复核与置信度调整。
 *
 * <p>写操作（标记创建/复核）均在单事务内完成：先占位写入幂等去重记录，
 * 再对 observation_current 行加锁（SELECT ... FOR UPDATE）完成业务判定与变更，
 * 与观测合并/删除按事务提交顺序串行化；任何业务失败都会回滚，去重记录不占键。
 *
 * <p>复核前置校验：标记须仍绑定观测当前版本；若观测已产生新版本，标记转 STALE 并返回 410，
 * 该状态迁移会提交并占用 requestId（同键同参重放同一 410 结果）。
 *
 * <p>置信度规则：初始 100；CONFIRMED 且该类别在当前版本上尚未扣减过时扣减 20，最低 0；
 * DISMISSED 不影响置信度；置信度回写当前版本快照，历史版本快照不被后续版本改写。
 */
@Service
public class QualityFlagService {

    private static final int CONFIDENCE_DEDUCTION = 20;

    private final ObservationRepository observationRepository;
    private final QualityFlagRepository qualityFlagRepository;
    private final IdempotencyStore idempotencyStore;
    private final ObjectMapper objectMapper;

    public QualityFlagService(ObservationRepository observationRepository,
                              QualityFlagRepository qualityFlagRepository,
                              IdempotencyStore idempotencyStore,
                              ObjectMapper objectMapper) {
        this.observationRepository = observationRepository;
        this.qualityFlagRepository = qualityFlagRepository;
        this.idempotencyStore = idempotencyStore;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建待复核质量标记：附加于观测当前版本，不改变观测内容与版本。
     * 同一观测同一类别同时只能有一条待复核标记，重复提交 409。
     */
    @Transactional
    public FlagOutcome createFlag(String observationId, CreateQualityFlagRequest request) {
        String fingerprint = IdempotencyStore.fingerprint("FLAG_CREATE", observationId, request.flagKey(),
                request.category().name(), request.description(), request.role());
        FlagOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        FlagOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "FLAG_CREATE");
        if (concurrent != null) {
            return concurrent;
        }

        ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.gone("observation already deleted: " + observationId);
        }
        if (qualityFlagRepository.findPendingByCategory(observationId, request.category()).isPresent()) {
            throw ApiException.conflict(
                    "pending flag already exists for category: " + request.category(), current.version());
        }
        QualityFlag flag = new QualityFlag(observationId, request.flagKey(), request.category(),
                request.description(), request.role(), current.version(), FlagStatus.PENDING_REVIEW, null);
        try {
            qualityFlagRepository.insertFlag(flag);
        } catch (DuplicateKeyException e) {
            // 同一观测内 flagKey 已存在（含并发同键）：由主键串行化，按冲突处理
            throw ApiException.conflict("flagKey already exists: " + request.flagKey(), null);
        }
        QualityFlag stored = qualityFlagRepository.findFlag(observationId, request.flagKey()).orElseThrow();
        return complete(request.requestId(), HttpStatus.CREATED, QualityFlagResponse.of(stored));
    }

    /**
     * 复核质量标记：复核人角色须与提交人不同；复核前核对标记仍绑定观测当前版本，
     * 版本已变化时标记转 STALE 并返回 410。复核成功写入不可变复核记录；
     * CONFIRMED 且该类别在当前版本尚未扣减过时扣减置信度 20（最低 0）。
     */
    @Transactional
    public FlagOutcome review(String observationId, ReviewQualityFlagRequest request) {
        String fingerprint = IdempotencyStore.fingerprint("FLAG_REVIEW", observationId, request.flagKey(),
                request.conclusion().name(), request.reason(), request.role());
        FlagOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        FlagOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "FLAG_REVIEW");
        if (concurrent != null) {
            return concurrent;
        }

        ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        QualityFlag flag = qualityFlagRepository.findFlag(observationId, request.flagKey())
                .orElseThrow(() -> ApiException.notFound("flag not found: " + request.flagKey()));
        if (flag.status() == FlagStatus.STALE) {
            throw ApiException.gone("flag is stale and no longer reviewable: " + request.flagKey());
        }
        if (flag.status() != FlagStatus.PENDING_REVIEW) {
            throw ApiException.conflict("flag already reviewed: " + request.flagKey(), null);
        }
        if (flag.submittedRole().equals(request.role())) {
            throw ApiException.conflict("reviewer role must differ from flag submitter role", null);
        }
        if (flag.boundVersion() != current.version()) {
            // 观测已产生新版本（含删除墓碑）：标记转 STALE，不再可复核也不影响新版本；
            // 该状态迁移与 410 结果一并提交并占用 requestId
            qualityFlagRepository.updateStatus(observationId, request.flagKey(), FlagStatus.STALE);
            ErrorResponse body = new ErrorResponse(HttpStatus.GONE.value(), HttpStatus.GONE.getReasonPhrase(),
                    "observation version changed; flag turned STALE: " + request.flagKey(), null, current.version());
            idempotencyStore.complete(request.requestId(), HttpStatus.GONE.value(), writeBody(body));
            return new FlagOutcome(HttpStatus.GONE.value(), body);
        }

        QualityFlagReview review = new QualityFlagReview(observationId, request.flagKey(),
                flag.boundVersion(), current.version(), true,
                request.conclusion(), request.reason(), request.role(), null);
        qualityFlagRepository.insertReview(review);
        FlagStatus newStatus = request.conclusion() == ReviewConclusion.CONFIRMED
                ? FlagStatus.CONFIRMED : FlagStatus.DISMISSED;
        qualityFlagRepository.updateStatus(observationId, request.flagKey(), newStatus);
        if (request.conclusion() == ReviewConclusion.CONFIRMED
                && !qualityFlagRepository.deductionExists(observationId, flag.category(), current.version())) {
            // 同一类别在同一版本上只扣减一次；新版本产生后该类别可再次生效扣减
            qualityFlagRepository.insertDeduction(observationId, flag.category(), current.version());
            int newConfidence = Math.max(0, current.confidence() - CONFIDENCE_DEDUCTION);
            observationRepository.updateConfidence(observationId, current.version(), newConfidence);
        }

        QualityFlag storedFlag = qualityFlagRepository.findFlag(observationId, request.flagKey()).orElseThrow();
        QualityFlagReview storedReview = qualityFlagRepository.findReview(observationId, request.flagKey())
                .orElseThrow();
        return complete(request.requestId(), HttpStatus.OK, QualityFlagResponse.of(storedFlag, storedReview));
    }

    /**
     * 查询某观测的标记历史（含已复核标记的复核记录），按创建时间升序。
     */
    @Transactional(readOnly = true)
    public List<QualityFlagResponse> listFlags(String observationId) {
        requireObservation(observationId);
        return qualityFlagRepository.listFlags(observationId).stream().map(this::toResponse).toList();
    }

    /**
     * 查询某观测的待复核标记清单，按创建时间升序。
     */
    @Transactional(readOnly = true)
    public List<QualityFlagResponse> listPendingFlags(String observationId) {
        requireObservation(observationId);
        return qualityFlagRepository.listPending(observationId).stream().map(this::toResponse).toList();
    }

    /**
     * 查询某观测的置信度轨迹：各版本快照对应的置信度，按版本升序。
     */
    @Transactional(readOnly = true)
    public List<ConfidencePoint> confidenceTrajectory(String observationId) {
        requireObservation(observationId);
        return observationRepository.findConfidenceTrajectory(observationId);
    }

    private void requireObservation(String observationId) {
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
    }

    private QualityFlagResponse toResponse(QualityFlag flag) {
        return qualityFlagRepository.findReview(flag.observationId(), flag.flagKey())
                .map(review -> QualityFlagResponse.of(flag, review))
                .orElseGet(() -> QualityFlagResponse.of(flag));
    }

    /**
     * 幂等检查：同键同参返回原结果；同键异参抛 409；无记录返回 null 继续执行。
     */
    private FlagOutcome checkReplay(String requestId, String fingerprint) {
        IdempotencyStore.StoredResult stored = idempotencyStore.findReplay(requestId, fingerprint);
        if (stored == null) {
            return null;
        }
        return new FlagOutcome(stored.status(), readBody(stored.bodyJson()));
    }

    /**
     * 占位写入去重记录；并发同键时返回对方已提交的重放结果，正常占位返回 null。
     */
    private FlagOutcome insertPlaceholder(String requestId, String fingerprint, String operation) {
        IdempotencyStore.StoredResult stored = idempotencyStore.insertPlaceholder(requestId, fingerprint, operation);
        if (stored == null) {
            return null;
        }
        return new FlagOutcome(stored.status(), readBody(stored.bodyJson()));
    }

    /**
     * 业务成功后回填去重记录响应，并构造本次写操作结果；与业务变更同事务提交。
     */
    private FlagOutcome complete(String requestId, HttpStatus status, Object body) {
        idempotencyStore.complete(requestId, status.value(), writeBody(body));
        return new FlagOutcome(status.value(), body);
    }

    private String writeBody(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }

    private Object readBody(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored response", e);
        }
    }

    /**
     * 写操作结果：HTTP 状态码与响应体（成功为 QualityFlagResponse，410 过期为 ErrorResponse，
     * 重放时为反序列化的 JSON 树）。
     */
    public record FlagOutcome(int status, Object body) {
    }
}
