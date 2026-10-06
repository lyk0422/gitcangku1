package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 闭合导线成果生成服务。
 *
 * <p>生成在单事务内完成：先占位写入幂等去重记录，再按观测标识排序逐行加锁（SELECT ... FOR UPDATE）
 * 核对全部引用观测的当前版本，然后计算闭合差并逐边修正，最后原子写入成果表与去重记录响应；
 * 任何业务失败都整体回滚，去重记录不占键，也不生成成果。
 *
 * <p>指纹采用结构化 JSON（操作、操作者、按输入顺序的观测标识与期望版本列表）的 SHA-256，
 * 避免分隔符拼接碰撞；同键同参重放首次完整响应，异参 409。
 *
 * <p>成果冻结输入顺序、引用版本、闭合差与修正边；后续观测变更只改变当前记录，不回改成果。
 */
@Service
public class TraverseService {

    private final ObservationRepository observationRepository;
    private final RequestLogRepository requestLogRepository;
    private final TraverseRepository traverseRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TraverseService(ObservationRepository observationRepository,
                           RequestLogRepository requestLogRepository,
                           TraverseRepository traverseRepository,
                           ObjectMapper objectMapper,
                           Clock clock) {
        this.observationRepository = observationRepository;
        this.requestLogRepository = requestLogRepository;
        this.traverseRepository = traverseRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 生成闭合导线成果：核对全部引用当前版本后计算闭合差并逐边修正，原子落库成果与幂等记录。
     */
    @Transactional
    public TraverseOutcome generate(GenerateTraverseRequest request) {
        validateDistinctObservations(request.legs());
        String fingerprint = structuredFingerprint(request);
        TraverseOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        TraverseOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        Map<String, ObservationSnapshot> currents = lockReferencedCurrents(request.legs());
        List<TraverseAdjustment.LegInput> inputs = new ArrayList<>(request.legs().size());
        for (TraverseLegRef leg : request.legs()) {
            ObservationSnapshot current = currents.get(leg.observationId());
            if (current.deleted()) {
                throw ApiException.unprocessable(
                        "referenced observation is a tombstone: " + leg.observationId());
            }
            if (leg.version() != current.version()) {
                throw ApiException.conflict(
                        "version not current for observation: " + leg.observationId(), current.version());
            }
            if (!current.hasDisplacement()) {
                throw ApiException.unprocessable(
                        "referenced observation has no displacement: " + leg.observationId());
            }
            inputs.add(new TraverseAdjustment.LegInput(current.dx(), current.dy(), current.w()));
        }

        TraverseAdjustment.Adjustment adjustment = TraverseAdjustment.compute(inputs);

        String traverseId = UUID.randomUUID().toString();
        List<TraverseRecord.Leg> legs = new ArrayList<>(request.legs().size());
        for (int i = 0; i < request.legs().size(); i++) {
            TraverseLegRef ref = request.legs().get(i);
            ObservationSnapshot current = currents.get(ref.observationId());
            TraverseAdjustment.AdjustedLeg adjusted = adjustment.legs().get(i);
            legs.add(new TraverseRecord.Leg(i + 1, ref.observationId(), current.version(),
                    current.dx(), current.dy(), current.w(), adjusted.adjustedDx(), adjusted.adjustedDy()));
        }
        TraverseRecord record = new TraverseRecord(traverseId, request.requestId(), request.operator(),
                legs.size(), adjustment.closureDx().toPlainString(), adjustment.closureDy().toPlainString(),
                adjustment.totalWeight().toPlainString(), Instant.now(clock), legs);
        traverseRepository.insertResult(record);
        for (TraverseRecord.Leg leg : legs) {
            traverseRepository.insertLeg(traverseId, leg);
        }
        return complete(request.requestId(), HttpStatus.CREATED, TraverseResponse.of(record));
    }

    /**
     * 按成果标识查询已冻结成果；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public TraverseRecord getTraverse(String traverseId) {
        return traverseRepository.findById(traverseId)
                .orElseThrow(() -> ApiException.notFound("traverse not found: " + traverseId));
    }

    /**
     * 校验导线引用互不重复：同一观测在一条导线中至多出现一次。
     */
    private void validateDistinctObservations(List<TraverseLegRef> legs) {
        Set<String> seen = new HashSet<>();
        for (TraverseLegRef leg : legs) {
            if (!seen.add(leg.observationId())) {
                throw ApiException.badRequest(
                        "legs must reference distinct observations, duplicated: " + leg.observationId());
            }
        }
    }

    /**
     * 结构化指纹：操作、操作者与按输入顺序的（观测标识、期望版本）列表序列化为 JSON 后取 SHA-256，
     * 避免分隔符拼接造成的碰撞。
     */
    private String structuredFingerprint(GenerateTraverseRequest request) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("operation", "TRAVERSE");
        material.put("operator", request.operator());
        material.put("legs", request.legs().stream()
                .map(leg -> List.of(leg.observationId(), leg.version()))
                .toList());
        try {
            byte[] json = objectMapper.writeValueAsBytes(material);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize traverse fingerprint", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 幂等检查：同键同参返回原成功结果；同键异参返回 409；无记录返回 null 继续执行。
     */
    private TraverseOutcome checkReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new TraverseOutcome(entry.responseStatus(), readBody(entry.responseBody()));
    }

    /**
     * 占位写入去重记录；并发同键时主键冲突，等待对方事务结束后读取已提交结果：
     * 同参返回重放结果，异参抛 409；正常占位返回 null。业务失败时占位随事务回滚，不占键。
     */
    private TraverseOutcome insertPlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "TRAVERSE");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new TraverseOutcome(entry.responseStatus(), readBody(entry.responseBody()));
        }
    }

    /**
     * 成果生成成功后回填去重记录响应，与成果变更在同一事务内提交。
     */
    private TraverseOutcome complete(String requestId, HttpStatus status, TraverseResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeBody(body));
        return new TraverseOutcome(status.value(), body);
    }

    private String writeBody(TraverseResponse body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize traverse response", e);
        }
    }

    private TraverseResponse readBody(String json) {
        try {
            return objectMapper.readValue(json, TraverseResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored traverse response", e);
        }
    }

    /**
     * 按观测标识排序逐行加锁读取当前状态，避免并发生成相互死锁；不存在的引用抛 404。
     */
    private Map<String, ObservationSnapshot> lockReferencedCurrents(List<TraverseLegRef> legs) {
        List<String> sortedIds = legs.stream().map(TraverseLegRef::observationId).sorted().toList();
        Map<String, ObservationSnapshot> currents = new HashMap<>();
        for (String observationId : sortedIds) {
            ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                    .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
            currents.put(observationId, current);
        }
        return currents;
    }

    /**
     * 成果生成结果：HTTP 状态码与成果响应体。
     */
    public record TraverseOutcome(int status, TraverseResponse body) {
    }
}
