package com.example.starter.frame;

import com.example.starter.observation.ApiException;
import com.example.starter.observation.RequestLogEntry;
import com.example.starter.observation.RequestLogRepository;
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
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * 带版本坐标基准转换的业务服务。
 *
 * <p>规则发布（PUBLISH_RULE）与观测接收（COORDINATE_OBSERVATION）共用 request_log 幂等机制与全局写锁：
 * 写事务先占位幂等记录，再抢占 coordinate_write_lock 唯一行，使"规则发布 vs 观测写入"完全串行——
 * 观测按提交时已生效的唯一规则一次性计算 X、Y，不会出现半旧半新坐标。
 *
 * <p>规则选择按观测 UTC 时刻匹配左闭右开区间：缺少可用规则或同一时刻命中多条规则，
 * 返回 422 且观测不入库（失败事务一并回滚，幂等键不被占用）；目标坐标 = 原坐标 + 偏移量，
 * 按六位小数 HALF_UP 固化；站点矩形 X[0,10]、Y[0,30]，边界点视为站内，
 * 站外有效观测保留为待复核、不自动合并。已落库结果不随后续规则调整重新解释。
 */
@Service
public class CoordinateFrameService {

    private static final String OPERATION_PUBLISH = "PUBLISH_RULE";
    private static final String OPERATION_OBSERVE = "COORDINATE_OBSERVATION";
    private static final String SEPARATOR = "";

    /**
     * 目标坐标保留小数位：结果按六位小数 HALF_UP 固化。
     */
    private static final int TARGET_SCALE = 6;

    /**
     * 站点矩形边界（含边界点）：X 0~10、Y 0~30。
     */
    private static final BigDecimal MIN_X = BigDecimal.ZERO;
    private static final BigDecimal MAX_X = BigDecimal.TEN;
    private static final BigDecimal MIN_Y = BigDecimal.ZERO;
    private static final BigDecimal MAX_Y = new BigDecimal("30");

    private final FrameRuleRepository frameRuleRepository;
    private final CoordinateObservationRepository coordinateObservationRepository;
    private final CoordinateWriteLockRepository writeLockRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;

    public CoordinateFrameService(FrameRuleRepository frameRuleRepository,
                                  CoordinateObservationRepository coordinateObservationRepository,
                                  CoordinateWriteLockRepository writeLockRepository,
                                  RequestLogRepository requestLogRepository,
                                  ObjectMapper objectMapper) {
        this.frameRuleRepository = frameRuleRepository;
        this.coordinateObservationRepository = coordinateObservationRepository;
        this.writeLockRepository = writeLockRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 发布规则新版本：同 ruleId 首版版本号为 1，之后闭合原开放版本并顺延版本号。
     * 新版本生效起始时刻必须严格晚于该链最新版本的起始时刻，否则返回 409。
     */
    @Transactional
    public PublishOutcome publish(PublishFrameRuleRequest request) {
        String fingerprint = fingerprint(OPERATION_PUBLISH, request.ruleId(), request.targetDatum(),
                request.offsetX(), request.offsetY(), request.effectiveFromUtc());
        PublishOutcome replayed = checkPublishReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        PublishOutcome concurrent = insertPublishPlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        // 全局锁内裁决提交时的规则链状态，与观测写入互斥。
        writeLockRepository.acquire();

        Instant effectiveFrom = parseInstant(request.effectiveFromUtc(), "effectiveFromUtc");
        Optional<FrameRule> latest = frameRuleRepository.findLatest(request.ruleId());
        if (latest.isPresent() && !effectiveFrom.isAfter(latest.get().effectiveFromUtc())) {
            throw ApiException.conflict(
                    "effectiveFromUtc must be later than the latest version's effectiveFromUtc: "
                            + request.ruleId(), latest.get().version());
        }

        latest.ifPresent(rule -> frameRuleRepository.closeOpenVersion(request.ruleId(), effectiveFrom));
        FrameRule next = new FrameRule(request.ruleId(), latest.map(FrameRule::version).orElse(0) + 1,
                request.targetDatum(), new BigDecimal(request.offsetX()), new BigDecimal(request.offsetY()),
                effectiveFrom, null);
        frameRuleRepository.insert(next);
        return completePublish(request.requestId(), FrameRuleResponse.of(next));
    }

    /**
     * 接收坐标观测：按观测 UTC 时刻选择唯一生效规则，完成转换与边界判定并固化。
     * 缺规则或命中多条规则返回 422 且不入库；同 requestId 重放首次坐标与状态，改参返回 409。
     */
    @Transactional
    public ObserveOutcome observe(CoordinateObservationRequest request) {
        String fingerprint = fingerprint(OPERATION_OBSERVE, request.coordinateObservationId(),
                request.sourceDatum(), request.sourceX(), request.sourceY(), request.observedAtUtc());
        ObserveOutcome replayed = checkObserveReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        ObserveOutcome concurrent = insertObservePlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        // 全局锁：规则选择与 X、Y 计算基于同一提交时刻的规则快照，杜绝半旧半新。
        writeLockRepository.acquire();

        if (coordinateObservationRepository.find(request.coordinateObservationId()).isPresent()) {
            throw ApiException.conflict(
                    "coordinate observation already exists: " + request.coordinateObservationId(), null);
        }

        Instant observedAt = parseInstant(request.observedAtUtc(), "observedAtUtc");
        List<FrameRule> effectiveRules = frameRuleRepository.findEffectiveAt(observedAt);
        if (effectiveRules.isEmpty()) {
            // 失败随事务回滚：观测不入库，requestId 占位也不占键。
            throw ApiException.unprocessableEntity(
                    "no effective coordinate frame rule at observedAtUtc: " + request.observedAtUtc());
        }
        if (effectiveRules.size() > 1) {
            String versions = effectiveRules.stream()
                    .map(rule -> rule.ruleId() + "@V" + rule.version())
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
            throw ApiException.unprocessableEntity(
                    "multiple effective coordinate frame rules at observedAtUtc "
                            + request.observedAtUtc() + ": " + versions);
        }
        FrameRule rule = effectiveRules.get(0);

        BigDecimal sourceX = new BigDecimal(request.sourceX());
        BigDecimal sourceY = new BigDecimal(request.sourceY());
        BigDecimal targetX = sourceX.add(rule.offsetX()).setScale(TARGET_SCALE, RoundingMode.HALF_UP);
        BigDecimal targetY = sourceY.add(rule.offsetY()).setScale(TARGET_SCALE, RoundingMode.HALF_UP);
        ReviewStatus reviewStatus = insideStation(targetX, targetY)
                ? ReviewStatus.AUTO_MERGED
                : ReviewStatus.PENDING_REVIEW;

        CoordinateObservation observation = new CoordinateObservation(
                request.coordinateObservationId(), request.sourceDatum(),
                request.sourceX(), request.sourceY(), observedAt,
                rule.targetDatum(), targetX, targetY,
                rule.ruleId(), rule.version(), reviewStatus);
        coordinateObservationRepository.insert(observation);
        return completeObserve(request.requestId(), CoordinateObservationResponse.of(observation));
    }

    /**
     * 查询坐标观测，返回原坐标、目标坐标、规则版本与复核状态；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public CoordinateObservationResponse get(String coordinateObservationId) {
        CoordinateObservation observation = coordinateObservationRepository.find(coordinateObservationId)
                .orElseThrow(() -> ApiException.notFound(
                        "coordinate observation not found: " + coordinateObservationId));
        return CoordinateObservationResponse.of(observation);
    }

    /**
     * 查询规则链全部版本（版本号升序）；规则链不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<FrameRuleResponse> listRules(String ruleId) {
        List<FrameRule> rules = frameRuleRepository.findByRuleId(ruleId);
        if (rules.isEmpty()) {
            throw ApiException.notFound("frame rule not found: " + ruleId);
        }
        return rules.stream().map(FrameRuleResponse::of).toList();
    }

    /**
     * 站点矩形判定：X[0,10]、Y[0,30]，边界点视为站内；compareTo 判定使 -0.000000 等值也按界内处理。
     */
    private boolean insideStation(BigDecimal targetX, BigDecimal targetY) {
        return targetX.compareTo(MIN_X) >= 0 && targetX.compareTo(MAX_X) <= 0
                && targetY.compareTo(MIN_Y) >= 0 && targetY.compareTo(MAX_Y) <= 0;
    }

    private Instant parseInstant(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(field + " must be an ISO-8601 UTC instant: " + value);
        }
    }

    // ---------- 幂等（与 observation 包共用 request_log，操作类型各自独立） ----------

    private PublishOutcome checkPublishReplay(String requestId, String fingerprint) {
        RequestLogEntry entry = findReplayEntry(requestId, fingerprint);
        if (entry == null) {
            return null;
        }
        return new PublishOutcome(entry.responseStatus(),
                readBody(entry.responseBody(), FrameRuleResponse.class));
    }

    private ObserveOutcome checkObserveReplay(String requestId, String fingerprint) {
        RequestLogEntry entry = findReplayEntry(requestId, fingerprint);
        if (entry == null) {
            return null;
        }
        return new ObserveOutcome(entry.responseStatus(),
                readBody(entry.responseBody(), CoordinateObservationResponse.class));
    }

    /**
     * 同键同参返回已提交结果（重放）；同键异参返回 409；无记录返回 null 继续执行。
     */
    private RequestLogEntry findReplayEntry(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return entry;
    }

    private PublishOutcome insertPublishPlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, OPERATION_PUBLISH);
            return null;
        } catch (DuplicateKeyException e) {
            return replayAfterPlaceholderConflict(
                    requestId, fingerprint, FrameRuleResponse.class, PublishOutcome::new);
        }
    }

    private ObserveOutcome insertObservePlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, OPERATION_OBSERVE);
            return null;
        } catch (DuplicateKeyException e) {
            return replayAfterPlaceholderConflict(
                    requestId, fingerprint, CoordinateObservationResponse.class, ObserveOutcome::new);
        }
    }

    /**
     * 并发同键占位冲突：等待对方事务结束后读已提交记录，同参重放、异参 409。
     */
    private <B, O> O replayAfterPlaceholderConflict(String requestId, String fingerprint,
                                                    Class<B> bodyType,
                                                    BiFunction<Integer, B, O> outcomeFactory) {
        RequestLogEntry entry = requestLogRepository.find(requestId)
                .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return outcomeFactory.apply(entry.responseStatus(), readBody(entry.responseBody(), bodyType));
    }

    private PublishOutcome completePublish(String requestId, FrameRuleResponse body) {
        requestLogRepository.complete(requestId, HttpStatus.CREATED.value(), writeBody(body));
        return new PublishOutcome(HttpStatus.CREATED.value(), body);
    }

    private ObserveOutcome completeObserve(String requestId, CoordinateObservationResponse body) {
        requestLogRepository.complete(requestId, HttpStatus.CREATED.value(), writeBody(body));
        return new ObserveOutcome(HttpStatus.CREATED.value(), body);
    }

    private String writeBody(Object body) {
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
     * 规则发布结果：HTTP 状态码与响应体。
     */
    public record PublishOutcome(int status, FrameRuleResponse body) {
    }

    /**
     * 观测接收结果：HTTP 状态码与响应体。
     */
    public record ObserveOutcome(int status, CoordinateObservationResponse body) {
    }
}
