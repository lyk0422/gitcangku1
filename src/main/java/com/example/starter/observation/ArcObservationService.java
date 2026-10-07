package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * 圆弧观测业务服务：方位误差区间（圆周区间）类型观测的创建、三方合并、删除与人工裁决。
 *
 * <p>圆弧以整数千分之一度保存（起始角 0 至 359999，正向弧长 1 至 180000，区间含两端、按模 360000 绕回）。
 * 每条圆弧是完整字段组：三方合并按圆弧集合整体比较（集合顺序可变），
 * 绝不把一次提交的起始角与另一次提交的弧长拼组。
 *
 * <p>合并冲突返回 409，并在同一提交内落库“未解决冲突”标记（conflict_pending，失败不占 requestId 键）；
 * 人工裁决（resolve）写入候选圆弧形集合并清除该标记；标记存在期间裁定拒绝引用（422）。
 *
 * <p>写操作均在单事务内完成：先占位写入幂等去重记录，再对当前行加锁（SELECT ... FOR UPDATE）
 * 完成业务判定与变更，最后回填去重记录响应并整体提交；业务失败回滚，去重记录不占键。
 */
@Service
public class ArcObservationService {

    private final ArcObservationRepository arcRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;

    public ArcObservationService(ArcObservationRepository arcRepository,
                                 RequestLogRepository requestLogRepository,
                                 ObjectMapper objectMapper) {
        this.arcRepository = arcRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建圆弧观测记录，初始版本为 1。记录已存在返回 409。
     */
    @Transactional
    public ArcWriteOutcome create(CreateArcObservationRequest request) {
        List<ArcComponent> arcs = normalizeArcs(request.arcs());
        String fingerprint = Fingerprints.of("ARC_CREATE", request.observationId(),
                request.operator(), arcsString(arcs));
        ArcWriteOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        ArcWriteOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "ARC_CREATE");
        if (concurrent != null) {
            return concurrent;
        }
        if (arcRepository.findCurrentForUpdate(request.observationId()).isPresent()) {
            throw ApiException.conflict("arc observation already exists: " + request.observationId(), null);
        }
        ArcObservationSnapshot snapshot = new ArcObservationSnapshot(
                request.observationId(), 1, false, false, arcs);
        try {
            arcRepository.insertCurrent(snapshot);
        } catch (DuplicateKeyException e) {
            // 并发创建同一 observationId：由主键串行化，后到者按冲突处理
            throw ApiException.conflict("arc observation already exists: " + request.observationId(), null);
        }
        arcRepository.insertVersionHeader(snapshot.observationId(), 1, false);
        arcRepository.insertArcs(snapshot.observationId(), 1, arcs);
        return complete(request.requestId(), HttpStatus.CREATED, ArcObservationResponse.of(snapshot));
    }

    /**
     * 离线提交：基于 baseVersion 的三方合并，圆弧集合整体作为完整字段组。
     * 冲突时抛出 ArcMergeConflictException（事务不回滚）：落库未解决冲突标记并删除占位，失败不占键。
     */
    @Transactional(noRollbackFor = ArcMergeConflictException.class)
    public ArcWriteOutcome merge(String observationId, MergeArcObservationRequest request) {
        List<ArcComponent> candidate = normalizeArcs(request.arcs());
        String fingerprint = Fingerprints.of("ARC_MERGE", observationId,
                String.valueOf(request.baseVersion()), request.operator(), arcsString(candidate));
        ArcWriteOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        ArcWriteOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "ARC_MERGE");
        if (concurrent != null) {
            return concurrent;
        }
        ArcObservationSnapshot current = arcRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("arc observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.gone("arc observation already deleted: " + observationId);
        }
        ArcObservationSnapshot base = arcRepository.findVersion(observationId, request.baseVersion())
                .orElseThrow(() -> ApiException.notFound(
                        "base version not found: " + observationId + "@" + request.baseVersion()));
        if (base.deleted()) {
            throw ApiException.unprocessableEntity(
                    "base version is a tombstone: " + observationId + "@" + request.baseVersion());
        }

        List<ArcComponent> merged;
        if (arcsEqual(candidate, base.arcs())) {
            merged = current.arcs();
        } else if (arcsEqual(current.arcs(), base.arcs()) || arcsEqual(candidate, current.arcs())) {
            merged = candidate;
        } else {
            // 冲突：整组报 409；提交未解决冲突标记并删除占位（失败不占键），不写入任何部分合并结果
            arcRepository.setConflictPending(observationId, true);
            requestLogRepository.delete(request.requestId());
            throw new ArcMergeConflictException(
                    "merge conflict on arc field group: " + observationId, current.version());
        }
        if (arcsEqual(merged, current.arcs())) {
            return complete(request.requestId(), HttpStatus.OK, ArcObservationResponse.of(current));
        }
        ArcObservationSnapshot next = new ArcObservationSnapshot(observationId, current.version() + 1,
                false, current.conflictPending(), merged);
        arcRepository.updateCurrent(next);
        arcRepository.insertVersionHeader(observationId, next.version(), false);
        arcRepository.insertArcs(observationId, next.version(), merged);
        return complete(request.requestId(), HttpStatus.OK, ArcObservationResponse.of(next));
    }

    /**
     * 删除圆弧观测记录：expectedVersion 匹配当前版本时生成新版本墓碑；已删除记录返回 410。
     */
    @Transactional
    public ArcWriteOutcome delete(String observationId, DeleteArcObservationRequest request) {
        String fingerprint = Fingerprints.of("ARC_DELETE", observationId,
                String.valueOf(request.expectedVersion()), request.operator());
        ArcWriteOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        ArcWriteOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "ARC_DELETE");
        if (concurrent != null) {
            return concurrent;
        }
        ArcObservationSnapshot current = arcRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("arc observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.gone("arc observation already deleted: " + observationId);
        }
        if (request.expectedVersion() != current.version()) {
            throw ApiException.conflict("expectedVersion mismatch", current.version());
        }
        int newVersion = current.version() + 1;
        arcRepository.markDeleted(observationId, newVersion);
        arcRepository.insertVersionHeader(observationId, newVersion, true);
        return complete(request.requestId(), HttpStatus.OK, ArcObservationResponse.of(
                new ArcObservationSnapshot(observationId, newVersion, true, false, List.of())));
    }

    /**
     * 人工裁决：强制写入候选圆弧集合并清除未解决冲突标记；内容无变化时仅清除标记、不加版本。
     */
    @Transactional
    public ArcWriteOutcome resolve(String observationId, ResolveArcObservationRequest request) {
        List<ArcComponent> candidate = normalizeArcs(request.arcs());
        String fingerprint = Fingerprints.of("ARC_RESOLVE", observationId,
                String.valueOf(request.expectedCurrentVersion()), request.operator(), arcsString(candidate));
        ArcWriteOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        ArcWriteOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "ARC_RESOLVE");
        if (concurrent != null) {
            return concurrent;
        }
        ArcObservationSnapshot current = arcRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("arc observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.gone("arc observation already deleted: " + observationId);
        }
        if (request.expectedCurrentVersion() != current.version()) {
            throw ApiException.conflict("expectedCurrentVersion mismatch", current.version());
        }
        if (arcsEqual(candidate, current.arcs())) {
            ArcObservationSnapshot settled = current;
            if (current.conflictPending()) {
                arcRepository.setConflictPending(observationId, false);
                settled = new ArcObservationSnapshot(observationId, current.version(),
                        false, false, current.arcs());
            }
            return complete(request.requestId(), HttpStatus.OK, ArcObservationResponse.of(settled));
        }
        ArcObservationSnapshot next = new ArcObservationSnapshot(observationId, current.version() + 1,
                false, false, candidate);
        arcRepository.updateCurrent(next);
        arcRepository.insertVersionHeader(observationId, next.version(), false);
        arcRepository.insertArcs(observationId, next.version(), candidate);
        return complete(request.requestId(), HttpStatus.OK, ArcObservationResponse.of(next));
    }

    /**
     * 查询当前内容；墓碑只返回删除状态和版本。
     */
    @Transactional(readOnly = true)
    public ArcObservationResponse getCurrent(String observationId) {
        ArcObservationSnapshot current = arcRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("arc observation not found: " + observationId));
        return ArcObservationResponse.of(current);
    }

    /**
     * 查询指定历史版本；墓碑版本只返回删除状态和版本。
     */
    @Transactional(readOnly = true)
    public ArcObservationResponse getVersion(String observationId, int version) {
        ArcObservationSnapshot snapshot = arcRepository.findVersion(observationId, version)
                .orElseThrow(() -> ApiException.notFound(
                        "version not found: " + observationId + "@" + version));
        return ArcObservationResponse.of(snapshot);
    }

    /**
     * 校验并规范化圆弧集合：逐条校验角度范围（越界 400），按起始角升序排序并去除完全重复的分量。
     * 规范化后集合顺序不影响相等性比较与指纹，满足“区间全集顺序可变”。
     */
    static List<ArcComponent> normalizeArcs(List<ArcComponent> raw) {
        if (raw == null || raw.isEmpty()) {
            throw ApiException.badRequest("arcs must contain at least one component");
        }
        List<ArcComponent> arcs = new ArrayList<>(raw.size());
        for (ArcComponent arc : raw) {
            if (arc == null || arc.startAngle() == null || arc.arcLength() == null) {
                throw ApiException.badRequest("arc component must carry both startAngle and arcLength");
            }
            if (arc.startAngle() < 0 || arc.startAngle() > ArcGeometry.MAX_START_ANGLE) {
                throw ApiException.badRequest("startAngle out of range [0, 359999] millidegrees: "
                        + arc.startAngle());
            }
            if (arc.arcLength() < 1 || arc.arcLength() > ArcGeometry.MAX_ARC_LENGTH) {
                throw ApiException.badRequest("arcLength out of range [1, 180000] millidegrees: "
                        + arc.arcLength());
            }
            arcs.add(new ArcComponent(arc.startAngle(), arc.arcLength()));
        }
        arcs.sort(Comparator.comparingInt(ArcComponent::startAngle)
                .thenComparing(ArcComponent::arcLength));
        return new ArrayList<>(new LinkedHashSet<>(arcs));
    }

    /**
     * 圆弧集合相等性：双方均已规范化（排序去重）时按列表逐项比较，集合顺序可变。
     */
    static boolean arcsEqual(List<ArcComponent> left, List<ArcComponent> right) {
        return left.equals(right);
    }

    /**
     * 圆弧集合的稳定字符串表示，用于幂等指纹（整数按值，文本原样）。
     */
    static String arcsString(List<ArcComponent> arcs) {
        StringBuilder builder = new StringBuilder();
        for (ArcComponent arc : arcs) {
            builder.append('[').append(arc.startAngle()).append(':').append(arc.arcLength()).append(']');
        }
        return builder.toString();
    }

    /**
     * 幂等检查：同键同参返回原成功结果；同键异参返回 409；无记录返回 null 继续执行。
     */
    private ArcWriteOutcome checkReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new ArcWriteOutcome(entry.responseStatus(), readBody(entry.responseBody()));
    }

    /**
     * 占位写入去重记录；并发同键时主键冲突，等待对方事务结束后读取已提交结果：
     * 同参返回重放结果，异参抛 409；正常占位返回 null。业务失败时占位随事务回滚，不占键。
     */
    private ArcWriteOutcome insertPlaceholder(String requestId, String fingerprint, String operation) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new ArcWriteOutcome(entry.responseStatus(), readBody(entry.responseBody()));
        }
    }

    /**
     * 业务成功后回填去重记录响应，并构造本次写操作结果；与业务变更同事务提交。
     */
    private ArcWriteOutcome complete(String requestId, HttpStatus status, ArcObservationResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeBody(body));
        return new ArcWriteOutcome(status.value(), body);
    }

    private String writeBody(ArcObservationResponse body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }

    private ArcObservationResponse readBody(String json) {
        try {
            return objectMapper.readValue(json, ArcObservationResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored response", e);
        }
    }

    /**
     * 圆弧观测写操作结果：HTTP 状态码与响应体。
     */
    public record ArcWriteOutcome(int status, ArcObservationResponse body) {
    }
}
