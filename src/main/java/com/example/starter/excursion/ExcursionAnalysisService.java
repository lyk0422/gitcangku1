package com.example.starter.excursion;

import com.example.starter.observation.ApiException;
import com.example.starter.observation.RequestLogEntry;
import com.example.starter.observation.RequestLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 越限区间分析服务：引用一组当前版本观测，冻结阈值、引用与区间结果。
 *
 * <p>分析在单事务内完成：先占位写入幂等去重记录，再按 observationId 排序逐行加锁
 * （SELECT ... FOR UPDATE）固定引用集合——与并发的观测修订、合并或删除共同核验、彼此串行；
 * 随后仅基于该集合计算区间（不补查集合外读数），原子写入不可变分析记录与去重响应。
 * 任何校验失败都整体回滚：整次不写分析，去重记录不占键。
 *
 * <p>校验顺序：请求结构（400）→ 记录存在（404）→ 墓碑（422）→ 引用版本非当前（409）→ 源站一致（400）。
 * 后续观测更正只影响新分析；旧分析仍可查询，并以 sourceUpdated 标明已有源更新。
 */
@Service
public class ExcursionAnalysisService {

    private final ExcursionRepository excursionRepository;
    private final AnalysisRepository analysisRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ExcursionAnalysisService(ExcursionRepository excursionRepository,
                                    AnalysisRepository analysisRepository,
                                    RequestLogRepository requestLogRepository,
                                    ObjectMapper objectMapper,
                                    Clock clock) {
        this.excursionRepository = excursionRepository;
        this.analysisRepository = analysisRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 生成越限区间分析：固定引用集合后计算区间并冻结结果。
     */
    @Transactional
    public AnalyzeOutcome analyze(AnalyzeExcursionRequest request) {
        validateDistinctReferences(request.references());
        String fingerprint = analysisFingerprint(request);
        AnalyzeOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        AnalyzeOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        // 按 observationId 排序逐行加锁，固定引用集合，避免与并发合并/删除死锁
        List<AnalyzeExcursionRequest.ObservationRef> orderedRefs = request.references().stream()
                .sorted(Comparator.comparing(AnalyzeExcursionRequest.ObservationRef::observationId))
                .toList();
        List<ExcursionSnapshot> locked = new ArrayList<>();
        for (AnalyzeExcursionRequest.ObservationRef ref : orderedRefs) {
            ExcursionSnapshot current = excursionRepository.findCurrentForUpdate(ref.observationId())
                    .orElseThrow(() -> ApiException.notFound("observation not found: " + ref.observationId()));
            if (current.deleted()) {
                throw ApiException.unprocessable("referenced observation is deleted: " + ref.observationId());
            }
            if (current.version() != ref.version()) {
                throw ApiException.conflict("referenced version is not current for observation: "
                        + ref.observationId(), current.version());
            }
            locked.add(current);
        }

        String stationId = locked.get(0).stationId();
        for (ExcursionSnapshot snapshot : locked) {
            if (!snapshot.stationId().equals(stationId)) {
                throw ApiException.badRequest("referenced observations must share the same stationId: "
                        + stationId + " vs " + snapshot.stationId());
            }
        }

        // 仅基于固定引用集合计算：按时刻升序，严格大于阈值的相邻整分钟达到 N 即成区间
        BigDecimal threshold = new BigDecimal(request.threshold());
        List<ExcursionIntervals.Point> points = locked.stream()
                .map(s -> new ExcursionIntervals.Point(Instant.parse(s.minuteUtc()), new BigDecimal(s.reading())))
                .toList();
        List<ExcursionIntervals.Interval> intervals =
                ExcursionIntervals.compute(points, threshold, request.consecutiveMinutes());

        List<AnalysisResponse.ReferenceSnapshot> references = locked.stream()
                .sorted(Comparator.comparing(ExcursionSnapshot::minuteUtc))
                .map(s -> new AnalysisResponse.ReferenceSnapshot(
                        s.observationId(), s.version(), s.minuteUtc(), s.reading()))
                .toList();

        AnalysisRecord record = new AnalysisRecord(
                UUID.randomUUID().toString(), request.requestId(), stationId, request.threshold(),
                request.consecutiveMinutes(), references.size(),
                writeJson(references), writeJson(intervals), request.operator(),
                Instant.now(clock).toString());
        analysisRepository.insert(record);

        AnalysisResponse body = toResponse(record, references, intervals, false);
        requestLogRepository.complete(request.requestId(), HttpStatus.CREATED.value(), writeJson(body));
        return new AnalyzeOutcome(HttpStatus.CREATED.value(), body);
    }

    /**
     * 查询已冻结的分析结果；sourceUpdated 按引用版本与当前版本实时比对（含删除墓碑）。
     */
    @Transactional(readOnly = true)
    public AnalysisResponse getAnalysis(String analysisId) {
        AnalysisRecord record = analysisRepository.findByAnalysisId(analysisId)
                .orElseThrow(() -> ApiException.notFound("analysis not found: " + analysisId));
        List<AnalysisResponse.ReferenceSnapshot> references = readJson(record.referencesJson(),
                new TypeReference<>() {
                });
        List<ExcursionIntervals.Interval> intervals = readJson(record.intervalsJson(),
                new TypeReference<>() {
                });
        return toResponse(record, references, intervals, sourceUpdated(references));
    }

    /**
     * 判断任一引用观测是否已有更新版本（版本号前进即视为源已更新，含删除墓碑）。
     */
    private boolean sourceUpdated(List<AnalysisResponse.ReferenceSnapshot> references) {
        for (AnalysisResponse.ReferenceSnapshot ref : references) {
            Optional<ExcursionSnapshot> current = excursionRepository.findCurrent(ref.observationId());
            if (current.isEmpty() || current.get().version() != ref.version()) {
                return true;
            }
        }
        return false;
    }

    private AnalysisResponse toResponse(AnalysisRecord record,
                                        List<AnalysisResponse.ReferenceSnapshot> references,
                                        List<ExcursionIntervals.Interval> intervals,
                                        boolean sourceUpdated) {
        return new AnalysisResponse(record.analysisId(), record.stationId(), record.thresholdH(),
                record.consecutiveMinutes(), references, intervals, sourceUpdated,
                record.operator(), record.createdAtUtc());
    }

    /**
     * 引用 observationId 必须互不相同，否则 400。
     */
    private void validateDistinctReferences(List<AnalyzeExcursionRequest.ObservationRef> references) {
        Set<String> seen = new HashSet<>();
        for (AnalyzeExcursionRequest.ObservationRef ref : references) {
            if (!seen.add(ref.observationId())) {
                throw ApiException.badRequest("duplicate referenced observation: " + ref.observationId());
            }
        }
    }

    /**
     * 结构化指纹：操作、阈值（按值）、N、全部引用（含期望版本，按请求顺序）与操作者。
     */
    private String analysisFingerprint(AnalyzeExcursionRequest request) {
        List<String> parts = new ArrayList<>();
        parts.add(ExcursionService.normalizeDecimal(request.threshold()));
        parts.add(String.valueOf(request.consecutiveMinutes()));
        for (AnalyzeExcursionRequest.ObservationRef ref : request.references()) {
            parts.add(ref.observationId() + "@" + ref.version());
        }
        parts.add(request.operator());
        return ExcursionService.fingerprint("EXC_ANALYZE", parts.toArray(new String[0]));
    }

    /**
     * 幂等检查：同键同参返回原成功结果（不返新告警）；同键异参返回 409；无记录返回 null。
     */
    private AnalyzeOutcome checkReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new AnalyzeOutcome(entry.responseStatus(), readBody(entry.responseBody()));
    }

    /**
     * 占位写入去重记录，语义与观测写操作一致：并发同键冲突后读已提交结果，同参重放、异参 409。
     */
    private AnalyzeOutcome insertPlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "EXC_ANALYZE");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new AnalyzeOutcome(entry.responseStatus(), readBody(entry.responseBody()));
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize analysis payload", e);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored analysis payload", e);
        }
    }

    private AnalysisResponse readBody(String json) {
        try {
            return objectMapper.readValue(json, AnalysisResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored analysis response", e);
        }
    }

    /**
     * 分析结果：HTTP 状态码与响应体。
     */
    public record AnalyzeOutcome(int status, AnalysisResponse body) {
    }
}
