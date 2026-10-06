package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.util.TreeSet;

/**
 * 闭合导线成果生成服务。
 *
 * <p>生成在单事务内完成：先占位写入幂等去重记录，再按观测标识排序逐行加锁
 * （SELECT ... FOR UPDATE）核对所有引用观测的当前版本，随后计算闭合差与逐边修正，
 * 最后原子写入成果、成果边与去重记录响应；任何失败整体回滚，去重记录不占键。
 *
 * <p>闭合差为所有边 dx、dy 各自求和，两个绝对值均不得超过 0.1 米，越限 422；
 * 引用缺位移组或墓碑版本 422；引用版本非当前版本 409。修正规则：每边 dx、dy 分别减去
 * 总闭合差乘本边 w 除以总 w，前 n-1 边 HALF_UP 保留 6 位小数，末边取前边之和的相反数，
 * 最终精确闭合。成果冻结输入顺序、版本、闭合差与修正边，后续观测变更不倒改成果。
 *
 * <p>幂等身份由操作、操作者、成果标识与全部边参数（含期望版本）经结构化 JSON 序列化后
 * 哈希得到，避免分隔符碰撞；同键同参重放首次完整响应，异参 409，失败不占键。
 */
@Service
public class TraverseService {

    /**
     * 闭合差绝对值上限（米）：dx、dy 两个方向的闭合差均不得超过该值。
     */
    private static final BigDecimal CLOSURE_LIMIT = new BigDecimal("0.1");

    /**
     * 修正值保留的小数位数：前 n-1 边 HALF_UP 保留 6 位，末边取前边之和的相反数。
     */
    private static final int CORRECTION_SCALE = 6;

    private final ObservationRepository observationRepository;
    private final TraverseRepository traverseRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TraverseService(ObservationRepository observationRepository,
                           TraverseRepository traverseRepository,
                           RequestLogRepository requestLogRepository,
                           ObjectMapper objectMapper,
                           Clock clock) {
        this.observationRepository = observationRepository;
        this.traverseRepository = traverseRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 生成闭合导线成果：核对全部引用观测当前版本后计算闭合差并逐边修正，原子冻结成果。
     */
    @Transactional
    public TraverseOutcome generate(GenerateTraverseRequest request) {
        validateDistinctObservations(request.edges());
        String fingerprint = fingerprint(request);
        TraverseOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        TraverseOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        Map<String, ObservationSnapshot> currents = lockReferencedCurrents(request.edges());
        List<TraverseEdge> edges = new ArrayList<>();
        BigDecimal closureX = BigDecimal.ZERO;
        BigDecimal closureY = BigDecimal.ZERO;
        BigDecimal totalW = BigDecimal.ZERO;
        List<BigDecimal> dxValues = new ArrayList<>();
        List<BigDecimal> dyValues = new ArrayList<>();
        List<BigDecimal> wValues = new ArrayList<>();
        for (TraverseEdgeRef ref : request.edges()) {
            ObservationSnapshot current = currents.get(ref.observationId());
            if (current.deleted()) {
                throw ApiException.unprocessable(
                        "edge references a tombstone observation: " + ref.observationId());
            }
            if (ref.version() != current.version()) {
                throw ApiException.conflict("observation version is not current: "
                        + ref.observationId() + "@" + ref.version(), current.version());
            }
            if (current.dx() == null || current.dy() == null || current.w() == null) {
                throw ApiException.unprocessable(
                        "edge references an observation without displacement: " + ref.observationId());
            }
            BigDecimal dx = new BigDecimal(current.dx());
            BigDecimal dy = new BigDecimal(current.dy());
            BigDecimal w = new BigDecimal(current.w());
            dxValues.add(dx);
            dyValues.add(dy);
            wValues.add(w);
            closureX = closureX.add(dx);
            closureY = closureY.add(dy);
            totalW = totalW.add(w);
        }
        if (closureX.abs().compareTo(CLOSURE_LIMIT) > 0
                || closureY.abs().compareTo(CLOSURE_LIMIT) > 0) {
            throw ApiException.unprocessable("traverse closure error exceeds 0.1m: closureDx="
                    + closureX.toPlainString() + ", closureDy=" + closureY.toPlainString());
        }

        int count = request.edges().size();
        BigDecimal[] correctedX = new BigDecimal[count];
        BigDecimal[] correctedY = new BigDecimal[count];
        BigDecimal sumX = BigDecimal.ZERO;
        BigDecimal sumY = BigDecimal.ZERO;
        for (int i = 0; i < count - 1; i++) {
            correctedX[i] = correct(dxValues.get(i), closureX, wValues.get(i), totalW);
            correctedY[i] = correct(dyValues.get(i), closureY, wValues.get(i), totalW);
            sumX = sumX.add(correctedX[i]);
            sumY = sumY.add(correctedY[i]);
        }
        correctedX[count - 1] = sumX.negate().setScale(CORRECTION_SCALE, RoundingMode.HALF_UP);
        correctedY[count - 1] = sumY.negate().setScale(CORRECTION_SCALE, RoundingMode.HALF_UP);

        TraverseResult result = new TraverseResult(request.resultId(), request.requestId(),
                request.operator(), count, closureX.toPlainString(), closureY.toPlainString(),
                totalW.toPlainString(), Instant.now(clock));
        try {
            traverseRepository.insertResult(result);
        } catch (DuplicateKeyException e) {
            // 并发生成同一 resultId：由主键串行化，后到者按冲突处理
            throw ApiException.conflict("traverse result already exists: " + request.resultId(), null);
        }
        for (int i = 0; i < count; i++) {
            TraverseEdgeRef ref = request.edges().get(i);
            TraverseEdge edge = new TraverseEdge(request.resultId(), i + 1, ref.observationId(),
                    ref.version(), dxValues.get(i).toPlainString(), dyValues.get(i).toPlainString(),
                    wValues.get(i).toPlainString(), correctedX[i].toPlainString(),
                    correctedY[i].toPlainString());
            traverseRepository.insertEdge(edge);
            edges.add(edge);
        }
        return complete(request.requestId(), HttpStatus.CREATED, TraverseResponse.of(result, edges));
    }

    /**
     * 按成果标识查询冻结成果；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public TraverseResponse getTraverse(String resultId) {
        TraverseResult result = traverseRepository.findResult(resultId)
                .orElseThrow(() -> ApiException.notFound("traverse result not found: " + resultId));
        return TraverseResponse.of(result, traverseRepository.findEdges(resultId));
    }

    /**
     * 单边修正：原始值减去总闭合差乘本边 w 除以总 w，HALF_UP 保留 6 位小数。
     */
    private BigDecimal correct(BigDecimal value, BigDecimal closure, BigDecimal w, BigDecimal totalW) {
        BigDecimal term = closure.multiply(w).divide(totalW, CORRECTION_SCALE + 4, RoundingMode.HALF_UP);
        return value.subtract(term).setScale(CORRECTION_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 导线边必须引用互不相同的观测记录；重复引用返回 400。
     */
    private void validateDistinctObservations(List<TraverseEdgeRef> edges) {
        Map<String, Integer> seen = new HashMap<>();
        for (TraverseEdgeRef ref : edges) {
            if (seen.put(ref.observationId(), ref.version()) != null) {
                throw ApiException.badRequest(
                        "traverse edges must reference distinct observations: " + ref.observationId());
            }
        }
    }

    /**
     * 按观测标识排序逐行加锁读取当前状态，避免并发死锁；观测不存在返回 404。
     */
    private Map<String, ObservationSnapshot> lockReferencedCurrents(List<TraverseEdgeRef> edges) {
        Map<String, ObservationSnapshot> currents = new HashMap<>();
        for (String observationId : new TreeSet<>(edges.stream().map(TraverseEdgeRef::observationId).toList())) {
            ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                    .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
            currents.put(observationId, current);
        }
        return currents;
    }

    /**
     * 幂等身份：操作、操作者、成果标识与全部边参数（含期望版本）经结构化 JSON 序列化后取 SHA-256，
     * 避免分隔符碰撞。
     */
    private String fingerprint(GenerateTraverseRequest request) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operation", "TRAVERSE");
        identity.put("operator", request.operator());
        identity.put("resultId", request.resultId());
        List<Map<String, Object>> edges = new ArrayList<>();
        for (TraverseEdgeRef ref : request.edges()) {
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("observationId", ref.observationId());
            edge.put("version", ref.version());
            edges.add(edge);
        }
        identity.put("edges", edges);
        try {
            String json = objectMapper.writeValueAsString(identity);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize traverse identity", e);
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
     * 成果生成成功后回填去重记录响应，与成果、成果边在同一事务提交。
     */
    private TraverseOutcome complete(String requestId, HttpStatus status, TraverseResponse body) {
        try {
            requestLogRepository.complete(requestId, status.value(), objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize traverse response", e);
        }
        return new TraverseOutcome(status.value(), body);
    }

    private TraverseResponse readBody(String json) {
        try {
            return objectMapper.readValue(json, TraverseResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored traverse response", e);
        }
    }

    /**
     * 成果生成结果：HTTP 状态码与响应体。
     */
    public record TraverseOutcome(int status, TraverseResponse body) {
    }
}
