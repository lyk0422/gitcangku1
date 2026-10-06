package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 分层样方密度估计成果服务。
 *
 * <p>每层密度为计数总和除以采样面积总和；总体数量为各层密度乘总体面积之和。
 * 全程使用精确有理数（BigInteger 最简分数）计算，展示值按 HALF_UP 保留三位小数，
 * 舍入后的密度或总量不参与任何进一步计算。
 *
 * <p>写路径与观测合并/删除/裁决共用同一套机制：先占位写入幂等去重记录，
 * 再按观测标识排序逐条加行锁（SELECT ... FOR UPDATE）核对所有源版本，
 * 任何失败整体回滚、不生成成果也不占键；成功后冻结层全集、面积、成员身份、
 * 版本与精确结果，后续观测变更不倒改成果。
 */
@Service
public class EstimateService {

    private static final String SEPARATOR = String.valueOf((char) 1);
    private static final int MAX_OBSERVATIONS = 20;

    private final EstimateRepository estimateRepository;
    private final ObservationRepository observationRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EstimateService(EstimateRepository estimateRepository,
                           ObservationRepository observationRepository,
                           RequestLogRepository requestLogRepository,
                           ObjectMapper objectMapper,
                           Clock clock) {
        this.estimateRepository = estimateRepository;
        this.observationRepository = observationRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 生成分层密度估计成果：结构校验 400、缺计数面积组/墓碑/采样面积超层总体面积 422、
     * 源版本不一致 409；任何失败整次不生成成果。
     */
    @Transactional
    public EstimateOutcome create(CreateEstimateRequest request) {
        validateStructure(request);
        String fingerprint = fingerprint(request);
        EstimateOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        EstimateOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        if (estimateRepository.findById(request.estimateId()).isPresent()) {
            throw ApiException.conflict("estimate already exists: " + request.estimateId(), null);
        }

        // 按观测标识排序逐条加行锁：与合并/删除/裁决串行化，且固定顺序避免死锁
        List<String> observationIds = request.layers().stream()
                .flatMap(layer -> layer.members().stream())
                .map(EstimateMemberRequest::observationId)
                .distinct().sorted().toList();
        Map<String, ObservationSnapshot> currents = new HashMap<>();
        for (String observationId : observationIds) {
            ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                    .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
            currents.put(observationId, current);
        }

        List<EstimateRecord.EstimateLayerRecord> layers = new ArrayList<>();
        BigInteger resultNumerator = BigInteger.ZERO;
        BigInteger resultDenominator = BigInteger.ONE;
        int observationCount = 0;
        int layerIndex = 0;
        for (EstimateLayerRequest layer : request.layers()) {
            long countSum = 0;
            long areaSum = 0;
            List<EstimateRecord.EstimateMemberRecord> members = new ArrayList<>();
            int memberIndex = 0;
            for (EstimateMemberRequest member : layer.members()) {
                ObservationSnapshot current = currents.get(member.observationId());
                if (current.deleted()) {
                    throw ApiException.unprocessable(
                            "observation is a tombstone: " + member.observationId());
                }
                if (current.individualCount() == null || current.sampledArea() == null) {
                    throw ApiException.unprocessable(
                            "observation lacks count/area group: " + member.observationId());
                }
                if (member.version() != current.version()) {
                    throw ApiException.conflict("observation version mismatch: " + member.observationId(),
                            current.version());
                }
                countSum += current.individualCount();
                areaSum += current.sampledArea();
                members.add(new EstimateRecord.EstimateMemberRecord(memberIndex++, member.observationId(),
                        current.version(), current.individualCount(), current.sampledArea()));
                observationCount++;
            }
            if (areaSum > layer.totalArea()) {
                throw ApiException.unprocessable(
                        "sampled area " + areaSum + " exceeds layer total area " + layer.totalArea());
            }
            BigInteger densityNumerator = BigInteger.valueOf(countSum);
            BigInteger densityDenominator = BigInteger.valueOf(areaSum);
            BigInteger densityGcd = densityNumerator.gcd(densityDenominator);
            densityNumerator = densityNumerator.divide(densityGcd);
            densityDenominator = densityDenominator.divide(densityGcd);
            layers.add(new EstimateRecord.EstimateLayerRecord(layerIndex++, layer.totalArea(),
                    densityNumerator.toString(), densityDenominator.toString(), members));
            // 总量累加：精确密度乘总体面积，全程有理数运算，不使用舍入后的密度
            BigInteger termNumerator = densityNumerator.multiply(BigInteger.valueOf(layer.totalArea()));
            resultNumerator = resultNumerator.multiply(densityDenominator)
                    .add(termNumerator.multiply(resultDenominator));
            resultDenominator = resultDenominator.multiply(densityDenominator);
            BigInteger gcd = resultNumerator.gcd(resultDenominator);
            resultNumerator = resultNumerator.divide(gcd);
            resultDenominator = resultDenominator.divide(gcd);
        }

        String resultDisplay = new BigDecimal(resultNumerator)
                .divide(new BigDecimal(resultDenominator), 3, RoundingMode.HALF_UP)
                .toPlainString();
        EstimateRecord record = new EstimateRecord(request.estimateId(), request.requestId(),
                request.operator(), layerIndex, observationCount,
                resultNumerator.toString(), resultDenominator.toString(), resultDisplay,
                Instant.now(clock), layers);
        try {
            estimateRepository.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发下 estimateId 已被其他事务占用：主键串行化，后到者按冲突处理
            throw ApiException.conflict("estimate already exists: " + request.estimateId(), null);
        }
        EstimateResponse body = EstimateResponse.of(record);
        requestLogRepository.complete(request.requestId(), HttpStatus.CREATED.value(), writeBody(body));
        return new EstimateOutcome(HttpStatus.CREATED.value(), body);
    }

    /**
     * 按成果标识查询冻结的完整成果；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public EstimateResponse get(String estimateId) {
        return EstimateResponse.of(estimateRepository.findById(estimateId)
                .orElseThrow(() -> ApiException.notFound("estimate not found: " + estimateId)));
    }

    /**
     * 结构校验：2 至 5 层、每层正整数总体面积与 1 至 5 个成员、观测不跨层重复、总计最多 20 个观测。
     */
    private void validateStructure(CreateEstimateRequest request) {
        if (request.layers() == null || request.layers().size() < 2 || request.layers().size() > 5) {
            throw ApiException.badRequest("layers must contain 2 to 5 layers");
        }
        Set<String> seen = new HashSet<>();
        int total = 0;
        for (EstimateLayerRequest layer : request.layers()) {
            if (layer == null || layer.totalArea() == null || layer.totalArea() < 1) {
                throw ApiException.badRequest("layer totalArea must be a positive integer (square meters)");
            }
            if (layer.members() == null || layer.members().isEmpty() || layer.members().size() > 5) {
                throw ApiException.badRequest("each layer must reference 1 to 5 observations");
            }
            for (EstimateMemberRequest member : layer.members()) {
                if (member == null || member.observationId() == null || member.observationId().isBlank()) {
                    throw ApiException.badRequest("member observationId must not be blank");
                }
                if (member.version() == null || member.version() < 1) {
                    throw ApiException.badRequest("member version must be a positive integer");
                }
                if (!seen.add(member.observationId())) {
                    throw ApiException.badRequest(
                            "observation must not repeat across layers: " + member.observationId());
                }
                total++;
            }
        }
        if (total > MAX_OBSERVATIONS) {
            throw ApiException.badRequest("at most " + MAX_OBSERVATIONS + " observations per estimate");
        }
    }

    /**
     * 请求指纹：操作、成果标识、操作者与全部层参数（含期望版本）。
     * 层与成员先排序再入指纹，换序视为同参；面积为整数按数值规范化，文本原样。
     */
    private String fingerprint(CreateEstimateRequest request) {
        List<String> layerKeys = new ArrayList<>();
        for (EstimateLayerRequest layer : request.layers()) {
            List<String> memberKeys = layer.members().stream()
                    .map(member -> member.observationId() + "@" + member.version())
                    .sorted()
                    .toList();
            layerKeys.add(layer.totalArea() + "[" + String.join(",", memberKeys) + "]");
        }
        Collections.sort(layerKeys);
        return fingerprint("ESTIMATE", request.estimateId(), request.operator(),
                String.join("|", layerKeys));
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

    /**
     * 幂等检查：同键同参返回原成功结果；同键异参返回 409；无记录返回 null 继续执行。
     */
    private EstimateOutcome checkReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new EstimateOutcome(entry.responseStatus(), readBody(entry.responseBody()));
    }

    /**
     * 占位写入去重记录；并发同键时主键冲突，等待对方事务结束后读取已提交结果：
     * 同参返回重放结果，异参抛 409；正常占位返回 null。业务失败时占位随事务回滚，不占键。
     */
    private EstimateOutcome insertPlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "ESTIMATE");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new EstimateOutcome(entry.responseStatus(), readBody(entry.responseBody()));
        }
    }

    private String writeBody(EstimateResponse body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }

    private EstimateResponse readBody(String json) {
        try {
            return objectMapper.readValue(json, EstimateResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored response", e);
        }
    }

    /**
     * 估计成果写操作结果：HTTP 状态码与响应体。
     */
    public record EstimateOutcome(int status, EstimateResponse body) {
    }
}
