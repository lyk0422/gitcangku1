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
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 断面成果业务服务：由同断面 2 至 10 条当前版本断面点与整数水面高程 H 计算水域面积。
 *
 * <p>创建在单事务内完成：先占位写入幂等去重记录，再按观测标识排序逐行加锁（SELECT ... FOR UPDATE）
 * 核验全部源版本（墓碑/缺字段/未解决冲突 422，旧版 409），横向坐标按请求顺序严格递增校验
 * （不排序接受非法顺序），精确计算面积并冻结完整点列、H 与最简分数面积，最后回填去重记录整体提交；
 * 任一失败整体回滚，不生成成果。源观测后续变更只让历史成果显示 sourceUpdated，不重算。
 */
@Service
public class SectionResultService {

    private static final String SEPARATOR = "\u0001";

    private final ObservationRepository observationRepository;
    private final SectionResultRepository sectionResultRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SectionResultService(ObservationRepository observationRepository,
                                SectionResultRepository sectionResultRepository,
                                RequestLogRepository requestLogRepository,
                                ObjectMapper objectMapper,
                                Clock clock) {
        this.observationRepository = observationRepository;
        this.sectionResultRepository = sectionResultRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 创建断面成果，成功返回 201；同 resultId 同参重放返回 200 原成果，异参 409。
     */
    @Transactional
    public SectionResultOutcome create(CreateSectionResultRequest request) {
        String fingerprint = fingerprint(request);
        SectionResultOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        SectionResultOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        SectionResult existing = sectionResultRepository.findByResultId(request.resultId()).orElse(null);
        if (existing != null) {
            if (!sameParams(existing, request)) {
                throw ApiException.conflict(
                        "resultId reused with different parameters: " + request.resultId(), null);
            }
            return complete(request.requestId(), HttpStatus.OK, responseOf(existing));
        }

        SectionResult result = buildResult(request);
        try {
            sectionResultRepository.insert(result);
        } catch (DuplicateKeyException e) {
            // 并发下 resultId 已被其他事务占用：重读后同参重放，否则 409
            SectionResult winner = sectionResultRepository.findByResultId(request.resultId())
                    .orElseThrow(() -> ApiException.conflict(
                            "resultId conflict: " + request.resultId(), null));
            if (!sameParams(winner, request)) {
                throw ApiException.conflict(
                        "resultId reused with different parameters: " + request.resultId(), null);
            }
            return complete(request.requestId(), HttpStatus.OK, responseOf(winner));
        }
        return complete(request.requestId(), HttpStatus.CREATED, responseOf(result));
    }

    /**
     * 按全局唯一 resultId 查询成果；sourceUpdated 按源观测当前版本实时判定。不存在返回 404。
     */
    @Transactional(readOnly = true)
    public SectionResultResponse get(String resultId) {
        SectionResult result = sectionResultRepository.findByResultId(resultId)
                .orElseThrow(() -> ApiException.notFound("section result not found: " + resultId));
        return responseOf(result);
    }

    /**
     * 核验全部源版本并构造冻结成果：重复引用/墓碑/缺字段/跨断面/横向非严格递增 422，旧版 409。
     */
    private SectionResult buildResult(CreateSectionResultRequest request) {
        Set<String> ids = new HashSet<>();
        for (CreateSectionResultRequest.SourceRef ref : request.sources()) {
            if (!ids.add(ref.observationId())) {
                throw ApiException.unprocessable("duplicate source observation: " + ref.observationId());
            }
        }
        // 按观测标识排序加锁，避免并发成果创建因锁顺序不一致死锁
        Map<String, ObservationSnapshot> currentById = new HashMap<>();
        for (String id : ids.stream().sorted().toList()) {
            ObservationSnapshot current = observationRepository.findCurrentForUpdate(id)
                    .orElseThrow(() -> ApiException.notFound("source observation not found: " + id));
            currentById.put(id, current);
        }

        List<FrozenSectionPoint> points = new ArrayList<>();
        String sectionNo = null;
        Integer previousLateral = null;
        for (CreateSectionResultRequest.SourceRef ref : request.sources()) {
            ObservationSnapshot current = currentById.get(ref.observationId());
            if (current.deleted()) {
                throw ApiException.unprocessable("source observation is a tombstone: " + ref.observationId());
            }
            if (current.observationType() != ObservationType.SECTION
                    || current.sectionNo() == null || current.lateralMm() == null
                    || current.elevationMm() == null) {
                throw ApiException.unprocessable(
                        "source observation misses section fields: " + ref.observationId());
            }
            if (ref.expectedVersion() != current.version()) {
                throw ApiException.conflict("source version mismatch for " + ref.observationId()
                        + ": expected " + ref.expectedVersion() + ", current " + current.version(),
                        current.version());
            }
            if (sectionNo == null) {
                sectionNo = current.sectionNo();
            } else if (!sectionNo.equals(current.sectionNo())) {
                throw ApiException.unprocessable("sources belong to different sections: "
                        + sectionNo + " vs " + current.sectionNo());
            }
            if (previousLateral != null && current.lateralMm() <= previousLateral) {
                throw ApiException.unprocessable(
                        "lateral coordinates must be strictly increasing in source order");
            }
            previousLateral = current.lateralMm();
            points.add(new FrozenSectionPoint(ref.observationId(), current.version(),
                    current.lateralMm(), current.elevationMm()));
        }

        List<WaterAreaCalculator.Point> calculatorPoints = points.stream()
                .map(point -> new WaterAreaCalculator.Point(point.lateralMm(), point.elevationMm()))
                .toList();
        WaterAreaCalculator.Fraction area =
                WaterAreaCalculator.computeArea(calculatorPoints, request.waterLevelMm());
        return new SectionResult(request.resultId(), request.requestId(), sectionNo,
                request.waterLevelMm(), points, area.numerator(), area.denominator(),
                request.operator(), Instant.now(clock));
    }

    /**
     * 判断已存在成果与本次请求参数是否完全一致（操作者、H、有序源引用及期望版本）。
     */
    private boolean sameParams(SectionResult result, CreateSectionResultRequest request) {
        if (!Objects.equals(result.operator(), request.operator())
                || result.waterLevelMm() != request.waterLevelMm()
                || result.points().size() != request.sources().size()) {
            return false;
        }
        for (int i = 0; i < request.sources().size(); i++) {
            FrozenSectionPoint point = result.points().get(i);
            CreateSectionResultRequest.SourceRef ref = request.sources().get(i);
            if (!Objects.equals(point.observationId(), ref.observationId())
                    || point.version() != ref.expectedVersion()) {
                return false;
            }
        }
        return true;
    }

    private SectionResultResponse responseOf(SectionResult result) {
        return SectionResultResponse.of(result, isSourceUpdated(result));
    }

    /**
     * 任一源观测当前版本不同于成果冻结版本（含删除墓碑）即为源已更新；历史成果本身不重算。
     */
    private boolean isSourceUpdated(SectionResult result) {
        for (FrozenSectionPoint point : result.points()) {
            Optional<ObservationSnapshot> current = observationRepository.findCurrent(point.observationId());
            if (current.isEmpty() || current.get().version() != point.version()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 幂等检查：同键同参返回原成功结果；同键异参返回 409；无记录返回 null 继续执行。
     */
    private SectionResultOutcome checkReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new SectionResultOutcome(entry.responseStatus(), readBody(entry.responseBody()));
    }

    /**
     * 占位写入去重记录；并发同键时主键冲突，等待对方事务结束后读取已提交结果：
     * 同参返回重放结果，异参抛 409；正常占位返回 null。业务失败时占位随事务回滚，不占键。
     */
    private SectionResultOutcome insertPlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "SECTION_RESULT");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new SectionResultOutcome(entry.responseStatus(), readBody(entry.responseBody()));
        }
    }

    /**
     * 业务成功后回填去重记录响应，并构造本次写操作结果；与业务变更同事务提交。
     */
    private SectionResultOutcome complete(String requestId, HttpStatus status, SectionResultResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeBody(body));
        return new SectionResultOutcome(status.value(), body);
    }

    /**
     * 结构化指纹：操作、资源（resultId）、非空操作者、水面高程 H（整数按值）与有序全参数源引用。
     */
    private String fingerprint(CreateSectionResultRequest request) {
        StringBuilder raw = new StringBuilder("SECTION_RESULT");
        raw.append(SEPARATOR).append(request.resultId());
        raw.append(SEPARATOR).append(request.operator());
        raw.append(SEPARATOR).append(request.waterLevelMm());
        for (CreateSectionResultRequest.SourceRef ref : request.sources()) {
            raw.append(SEPARATOR).append(ref.observationId())
                    .append(SEPARATOR).append(ref.expectedVersion());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String writeBody(SectionResultResponse body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }

    private SectionResultResponse readBody(String json) {
        try {
            return objectMapper.readValue(json, SectionResultResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored response", e);
        }
    }

    /**
     * 断面成果写操作结果：HTTP 状态码与响应体。
     */
    public record SectionResultOutcome(int status, SectionResultResponse body) {
    }
}
