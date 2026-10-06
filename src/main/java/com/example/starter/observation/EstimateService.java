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
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 分层样方密度估计业务服务：由观测当前版本生成有来源约束的计算成果。
 *
 * <p>估计方案含 2 至 5 层，每层正整数总体面积与 1 至 5 个不同观测的当前版本，
 * 观测不得跨层重复，单次总计最多 20 个观测。每层密度为计数总和除采样面积总和，
 * 总体数量为各层密度乘总体面积之和；全程以精确有理数计算并保存，
 * 展示值（HALF_UP 三位小数）仅用于展示，不以舍入密度回算总量。
 *
 * <p>源约束（整次失败、不生成成果）：采样面积总和大于该层总体面积 422；
 * 观测缺计数面积组、为墓碑或存在未解决冲突 422；期望版本落后于当前版本 409。
 * 生成时对全部源观测按标识排序后逐行加锁（SELECT ... FOR UPDATE），
 * 与合并、删除及裁决共用同一套行锁，共同核对所有源版本。
 *
 * <p>成功成果原子冻结层全集、总体面积、成员身份、版本及精确结果，后续观测变更不回改。
 * requestId 全局幂等（同参重放首次完整响应、异参 409、失败不占键、去重与业务同事务）；
 * estimateId 全局唯一，同参重放、异参 409；层与成员换序视为同参。
 */
@Service
public class EstimateService {

    private static final String SEPARATOR = "";
    private static final int MAX_OBSERVATIONS = 20;
    private static final int DISPLAY_SCALE = 3;

    private final ObservationRepository observationRepository;
    private final EstimateRepository estimateRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EstimateService(ObservationRepository observationRepository,
                           EstimateRepository estimateRepository,
                           RequestLogRepository requestLogRepository,
                           ObjectMapper objectMapper,
                           Clock clock) {
        this.observationRepository = observationRepository;
        this.estimateRepository = estimateRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 生成分层密度估计成果：单事务内完成幂等占位、源版本核对、精确计算与冻结写入。
     */
    @Transactional
    public EstimateOutcome create(EstimateRequest request) {
        List<CanonicalLayer> layers = canonicalize(request.layers());
        String fingerprint = fingerprint(request.estimateId(), request.operator(), layers);

        EstimateOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        EstimateOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        EstimateRepository.EstimateRow existing = estimateRepository.findEstimate(request.estimateId())
                .orElse(null);
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict(
                        "estimateId reused with different parameters: " + request.estimateId(), null);
            }
            return complete(request.requestId(), HttpStatus.OK, assemble(existing));
        }

        Map<String, ObservationSnapshot> sources = lockSources(layers);
        validateSources(layers, sources);

        Rational total = Rational.ZERO;
        List<LayerComputation> computations = new ArrayList<>();
        for (int i = 0; i < layers.size(); i++) {
            CanonicalLayer layer = layers.get(i);
            long countSum = 0;
            long areaSum = 0;
            for (CanonicalMember member : layer.members()) {
                ObservationSnapshot snapshot = sources.get(member.observationId());
                countSum += snapshot.individualCount();
                areaSum += snapshot.sampleArea();
            }
            if (areaSum > layer.totalArea()) {
                throw ApiException.unprocessable("sample area sum " + areaSum
                        + " exceeds layer total area " + layer.totalArea()
                        + " for layer " + (i + 1));
            }
            Rational density = Rational.of(countSum, areaSum);
            total = total.add(density.multiply(layer.totalArea()));
            computations.add(new LayerComputation(i + 1, layer, countSum, areaSum, density));
        }

        EstimateRepository.EstimateRow row = new EstimateRepository.EstimateRow(
                request.estimateId(), request.requestId(), fingerprint, request.operator(), layers.size(),
                total.numerator().toString(), total.denominator().toString(),
                total.toDisplay(DISPLAY_SCALE), Instant.now(clock));
        try {
            estimateRepository.insertEstimate(row);
        } catch (DuplicateKeyException e) {
            // 并发下 estimateId 已被其他事务占用：重读后同参重放，否则 409。
            EstimateRepository.EstimateRow winner = estimateRepository.findEstimate(request.estimateId())
                    .orElseThrow(() -> ApiException.conflict(
                            "estimateId conflict: " + request.estimateId(), null));
            if (!winner.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict(
                        "estimateId reused with different parameters: " + request.estimateId(), null);
            }
            return complete(request.requestId(), HttpStatus.OK, assemble(winner));
        }
        for (LayerComputation computation : computations) {
            estimateRepository.insertLayer(request.estimateId(), computation.toRow());
            for (CanonicalMember member : computation.layer().members()) {
                ObservationSnapshot snapshot = sources.get(member.observationId());
                estimateRepository.insertMember(request.estimateId(), new EstimateRepository.MemberRow(
                        computation.layerIndex(), member.observationId(), snapshot.version(),
                        snapshot.individualCount(), snapshot.sampleArea()));
            }
        }
        return complete(request.requestId(), HttpStatus.CREATED, assemble(row));
    }

    /**
     * 按 estimateId 查询冻结成果；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public EstimateResponse getEstimate(String estimateId) {
        EstimateRepository.EstimateRow row = estimateRepository.findEstimate(estimateId)
                .orElseThrow(() -> ApiException.notFound("estimate not found: " + estimateId));
        return assemble(row);
    }

    /**
     * 结构校验与规范化：层内成员不重复、观测不跨层重复、总计不超过 20 个观测（违反均 400）；
     * 成员按观测标识排序、层按规范键排序，使层与成员换序视为同参。
     */
    private List<CanonicalLayer> canonicalize(List<EstimateRequest.LayerRequest> layerRequests) {
        Set<String> usedObservations = new HashSet<>();
        int totalMembers = 0;
        List<CanonicalLayer> layers = new ArrayList<>();
        for (int i = 0; i < layerRequests.size(); i++) {
            EstimateRequest.LayerRequest layerRequest = layerRequests.get(i);
            Set<String> layerObservations = new HashSet<>();
            List<CanonicalMember> members = new ArrayList<>();
            for (EstimateRequest.MemberRequest member : layerRequest.members()) {
                if (!layerObservations.add(member.observationId())) {
                    throw ApiException.badRequest("duplicate observation in layer " + (i + 1) + ": "
                            + member.observationId());
                }
                if (!usedObservations.add(member.observationId())) {
                    throw ApiException.badRequest("observation repeated across layers: "
                            + member.observationId());
                }
                members.add(new CanonicalMember(member.observationId(), member.expectedVersion()));
            }
            totalMembers += members.size();
            members.sort(Comparator.comparing(CanonicalMember::observationId));
            layers.add(new CanonicalLayer(layerRequest.totalArea(), members));
        }
        if (totalMembers > MAX_OBSERVATIONS) {
            throw ApiException.badRequest("too many observations: max " + MAX_OBSERVATIONS
                    + ", got " + totalMembers);
        }
        layers.sort(Comparator.comparing(CanonicalLayer::canonicalKey));
        return layers;
    }

    /**
     * 对全部源观测按标识排序后逐行加锁读取当前状态，与合并/删除/裁决共用行锁核对源版本。
     */
    private Map<String, ObservationSnapshot> lockSources(List<CanonicalLayer> layers) {
        List<String> observationIds = layers.stream()
                .flatMap(layer -> layer.members().stream())
                .map(CanonicalMember::observationId)
                .distinct()
                .sorted()
                .toList();
        Map<String, ObservationSnapshot> sources = new LinkedHashMap<>();
        for (String observationId : observationIds) {
            ObservationSnapshot snapshot = observationRepository.findCurrentForUpdate(observationId)
                    .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
            sources.put(observationId, snapshot);
        }
        return sources;
    }

    /**
     * 源约束核对：墓碑、未解决冲突、缺计数面积组均 422；期望版本落后于当前版本 409。
     */
    private void validateSources(List<CanonicalLayer> layers, Map<String, ObservationSnapshot> sources) {
        for (CanonicalLayer layer : layers) {
            for (CanonicalMember member : layer.members()) {
                ObservationSnapshot snapshot = sources.get(member.observationId());
                if (snapshot.deleted()) {
                    throw ApiException.unprocessable(
                            "observation is deleted (tombstone): " + member.observationId());
                }
                if (snapshot.conflictPending()) {
                    throw ApiException.unprocessable(
                            "observation has unresolved conflict: " + member.observationId());
                }
                if (snapshot.individualCount() == null || snapshot.sampleArea() == null) {
                    throw ApiException.unprocessable(
                            "observation missing count/sampleArea fields: " + member.observationId());
                }
                if (member.expectedVersion() != snapshot.version()) {
                    throw ApiException.conflict(
                            "stale version for observation: " + member.observationId()
                                    + ", expected " + member.expectedVersion()
                                    + ", current " + snapshot.version(),
                            snapshot.version());
                }
            }
        }
    }

    /**
     * 由主表行与冻结明细组装响应（本事务内写入可读，重放与查询路径同构）。
     */
    private EstimateResponse assemble(EstimateRepository.EstimateRow row) {
        List<EstimateRepository.LayerRow> layerRows = estimateRepository.findLayers(row.estimateId());
        Map<Integer, List<EstimateRepository.MemberRow>> membersByLayer = new HashMap<>();
        for (EstimateRepository.MemberRow member : estimateRepository.findMembers(row.estimateId())) {
            membersByLayer.computeIfAbsent(member.layerIndex(), key -> new ArrayList<>()).add(member);
        }
        List<EstimateResponse.Layer> layers = new ArrayList<>();
        for (EstimateRepository.LayerRow layerRow : layerRows) {
            List<EstimateResponse.Member> members = membersByLayer
                    .getOrDefault(layerRow.layerIndex(), List.of())
                    .stream()
                    .map(member -> new EstimateResponse.Member(member.observationId(), member.obsVersion(),
                            member.individualCount(), member.sampleArea()))
                    .toList();
            String densityExact = "1".equals(layerRow.densityDenominator())
                    ? layerRow.densityNumerator()
                    : layerRow.densityNumerator() + "/" + layerRow.densityDenominator();
            layers.add(new EstimateResponse.Layer(layerRow.layerIndex(), layerRow.totalArea(),
                    layerRow.countSum(), layerRow.sampleAreaSum(), densityExact,
                    layerRow.densityDisplay(), members));
        }
        String totalExact = "1".equals(row.totalDenominator())
                ? row.totalNumerator()
                : row.totalNumerator() + "/" + row.totalDenominator();
        return new EstimateResponse(row.estimateId(), row.operator(), row.layerCount(), layers,
                totalExact, row.totalDisplay(), row.createdAtUtc());
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

    /**
     * 成果生成成功后回填去重记录响应，与成果冻结在同一事务内提交。
     */
    private EstimateOutcome complete(String requestId, HttpStatus status, EstimateResponse body) {
        try {
            requestLogRepository.complete(requestId, status.value(), objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize estimate response", e);
        }
        return new EstimateOutcome(status.value(), body);
    }

    private EstimateResponse readBody(String json) {
        try {
            return objectMapper.readValue(json, EstimateResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored estimate response", e);
        }
    }

    /**
     * 结构化身份指纹：操作类型、资源标识、操作者与规范化后的全部层参数（含成员期望版本）。
     */
    private String fingerprint(String estimateId, String operator, List<CanonicalLayer> layers) {
        StringBuilder raw = new StringBuilder("ESTIMATE");
        raw.append(SEPARATOR).append(estimateId);
        raw.append(SEPARATOR).append(operator);
        for (CanonicalLayer layer : layers) {
            raw.append(SEPARATOR).append(layer.canonicalKey());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 规范化成员：观测标识与期望版本。
     */
    private record CanonicalMember(String observationId, int expectedVersion) {
    }

    /**
     * 规范化层：总体面积与按观测标识排序的成员；canonicalKey 用于排序与指纹。
     */
    private record CanonicalLayer(int totalArea, List<CanonicalMember> members) {
        String canonicalKey() {
            StringBuilder key = new StringBuilder(String.valueOf(totalArea)).append('{');
            for (CanonicalMember member : members) {
                key.append(member.observationId()).append('@').append(member.expectedVersion()).append(',');
            }
            return key.append('}').toString();
        }
    }

    /**
     * 单层计算结果：层序号、规范化层、计数/采样面积合计与精确密度。
     */
    private record LayerComputation(int layerIndex, CanonicalLayer layer, long countSum, long areaSum,
                                    Rational density) {
        EstimateRepository.LayerRow toRow() {
            return new EstimateRepository.LayerRow(layerIndex, layer.totalArea(), countSum, areaSum,
                    density.numerator().toString(), density.denominator().toString(),
                    density.toDisplay(DISPLAY_SCALE));
        }
    }

    /**
     * 估计成果生成结果：HTTP 状态码与响应体。
     */
    public record EstimateOutcome(int status, EstimateResponse body) {
    }
}