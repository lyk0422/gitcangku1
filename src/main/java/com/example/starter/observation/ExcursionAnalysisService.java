package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 持续越限区间分析服务：从一组固定的分钟观测引用判定连续越限区间。
 *
 * <p>生成时在单事务内先占位幂等去重记录，再按 observationId 排序逐条加行锁
 * （SELECT ... FOR UPDATE）核验引用：观测不存在 404、墓碑 422、引用版本非当前版本 409、
 * 站号不一致 400；随后冻结引用快照、阈值与区间结果写入不可变分析记录，与去重记录同事务提交。
 * 只使用引用集合内的读数判定连续性，不补查集合外数据。
 *
 * <p>区间判定：按时刻升序，严格大于阈值 H 且逐分钟相邻的连续段长度达到 N 即产生区间，
 * 到首个非超限或缺分钟结束，右端为最后超限时刻加 1 分钟（左闭右开）；阈值相等不算越限。
 *
 * <p>分析记录生成后冻结：后续源观测修订/删除只影响新分析；旧分析可查并以 sourceUpdated
 * 标记源已更新；同 requestId 同参重放返回首次完整响应，不重新计算。
 */
@Service
public class ExcursionAnalysisService {

    private static final String SEPARATOR = "";
    private static final TypeReference<List<AnalysisReference>> REFERENCES_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<ExcursionInterval>> INTERVALS_TYPE = new TypeReference<>() {
    };

    private final MinuteObservationRepository minuteObservationRepository;
    private final ExcursionAnalysisRepository excursionAnalysisRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ExcursionAnalysisService(MinuteObservationRepository minuteObservationRepository,
                                    ExcursionAnalysisRepository excursionAnalysisRepository,
                                    RequestLogRepository requestLogRepository,
                                    ObjectMapper objectMapper,
                                    Clock clock) {
        this.minuteObservationRepository = minuteObservationRepository;
        this.excursionAnalysisRepository = excursionAnalysisRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 生成越限分析：核验引用集合后冻结引用、阈值与区间，返回不可变分析结果。
     */
    @Transactional
    public AnalysisOutcome create(CreateExcursionAnalysisRequest request) {
        String normalizedThreshold = ValueParsers.normalizeDecimal(request.threshold(), "threshold");
        List<CreateExcursionAnalysisRequest.ObservationRef> refs = request.references();
        Set<String> seenIds = new HashSet<>();
        for (CreateExcursionAnalysisRequest.ObservationRef ref : refs) {
            if (!seenIds.add(ref.observationId())) {
                throw ApiException.badRequest("duplicate reference to observation: " + ref.observationId());
            }
        }
        // 结构化指纹：操作 + 操作者 + 阈值（按值）+ N + 规范化排序后的引用集合（含期望版本）
        List<String> canonicalRefs = refs.stream()
                .map(ref -> ref.observationId() + "@" + ref.version())
                .sorted()
                .toList();
        String fingerprint = fingerprint("ANALYZE", request.operator(), normalizedThreshold,
                String.valueOf(request.consecutiveMinutes()), String.join(",", canonicalRefs));
        AnalysisOutcome replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        AnalysisOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        // 按 observationId 排序逐条加锁核验，与并发修订/合并/删除串行化且避免死锁
        List<CreateExcursionAnalysisRequest.ObservationRef> lockOrder = refs.stream()
                .sorted(Comparator.comparing(CreateExcursionAnalysisRequest.ObservationRef::observationId))
                .toList();
        List<LockedRef> locked = new ArrayList<>();
        for (CreateExcursionAnalysisRequest.ObservationRef ref : lockOrder) {
            MinuteObservationSnapshot current = minuteObservationRepository
                    .findCurrentForUpdate(ref.observationId())
                    .orElseThrow(() -> ApiException.notFound(
                            "minute observation not found: " + ref.observationId()));
            if (current.version() != ref.version()) {
                throw ApiException.conflict("referenced version is not current: "
                        + ref.observationId() + "@" + ref.version(), current.version());
            }
            if (current.deleted()) {
                throw ApiException.unprocessable(
                        "referenced observation is a deletion tombstone: " + ref.observationId());
            }
            locked.add(new LockedRef(ref, current));
        }
        String stationId = null;
        for (LockedRef entry : locked) {
            if (stationId == null) {
                stationId = entry.snapshot().stationId();
            } else if (!stationId.equals(entry.snapshot().stationId())) {
                throw ApiException.badRequest("all references must share the same stationId: "
                        + stationId + " vs " + entry.snapshot().stationId());
            }
        }

        // 固定引用集合：仅从集合内快照计算，按时刻升序
        List<AnalysisReference> frozen = locked.stream()
                .map(entry -> new AnalysisReference(entry.ref().observationId(), entry.ref().version(),
                        entry.snapshot().minuteUtc(), entry.snapshot().reading()))
                .sorted(Comparator.comparing(AnalysisReference::minuteUtc))
                .toList();
        List<ExcursionInterval> intervals = computeIntervals(frozen,
                ValueParsers.parseDecimal(request.threshold(), "threshold"), request.consecutiveMinutes());

        ExcursionAnalysisRecord record = new ExcursionAnalysisRecord(
                UUID.randomUUID().toString(), request.requestId(), request.operator(), stationId,
                request.threshold(), request.consecutiveMinutes(),
                writeJson(frozen), writeJson(intervals), Instant.now(clock));
        try {
            excursionAnalysisRepository.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发同 requestId：唯一键串行化，不向外泄漏约束异常
            throw ApiException.conflict("requestId already used for analysis: " + request.requestId(), null);
        }
        ExcursionAnalysisResponse body = new ExcursionAnalysisResponse(record.analysisId(), stationId,
                record.threshold(), record.consecutiveMinutes(), frozen, intervals, false,
                record.operator(), record.createdAtUtc());
        return complete(request.requestId(), HttpStatus.CREATED, body);
    }

    /**
     * 查询已生成的分析：返回冻结内容，并按查询时的源观测当前状态标记 sourceUpdated。
     */
    @Transactional(readOnly = true)
    public ExcursionAnalysisResponse get(String analysisId) {
        ExcursionAnalysisRecord record = excursionAnalysisRepository.findByAnalysisId(analysisId)
                .orElseThrow(() -> ApiException.notFound("analysis not found: " + analysisId));
        List<AnalysisReference> refs = readJson(record.referencesJson(), REFERENCES_TYPE);
        List<ExcursionInterval> intervals = readJson(record.intervalsJson(), INTERVALS_TYPE);
        return new ExcursionAnalysisResponse(record.analysisId(), record.stationId(), record.threshold(),
                record.consecutiveMinutes(), refs, intervals, sourceUpdated(refs),
                record.operator(), record.createdAtUtc());
    }

    /**
     * 区间判定：严格大于阈值且逐分钟相邻的连续段长度达到 N 才产生区间；
     * 段在首个非超限或缺分钟处结束，右端为最后超限时刻加 1 分钟。
     */
    private List<ExcursionInterval> computeIntervals(List<AnalysisReference> sortedRefs,
                                                     BigDecimal threshold, int consecutiveMinutes) {
        List<ExcursionInterval> intervals = new ArrayList<>();
        Instant runStart = null;
        Instant previousMinute = null;
        int runLength = 0;
        for (AnalysisReference ref : sortedRefs) {
            boolean exceed = ValueParsers.parseDecimal(ref.reading(), "reading").compareTo(threshold) > 0;
            if (exceed && runLength > 0 && ref.minuteUtc().equals(previousMinute.plusSeconds(60))) {
                runLength++;
            } else {
                if (runLength >= consecutiveMinutes) {
                    intervals.add(new ExcursionInterval(runStart, previousMinute.plusSeconds(60)));
                }
                runLength = exceed ? 1 : 0;
                runStart = exceed ? ref.minuteUtc() : null;
            }
            previousMinute = ref.minuteUtc();
        }
        if (runLength >= consecutiveMinutes) {
            intervals.add(new ExcursionInterval(runStart, previousMinute.plusSeconds(60)));
        }
        return intervals;
    }

    /**
     * 源更新标记：任一引用源观测的当前版本偏离引用版本（修订、合并或删除）即为 true。
     */
    private boolean sourceUpdated(List<AnalysisReference> refs) {
        for (AnalysisReference ref : refs) {
            Optional<MinuteObservationSnapshot> current =
                    minuteObservationRepository.findCurrent(ref.observationId());
            if (current.isEmpty() || current.get().deleted() || current.get().version() != ref.version()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 幂等检查：同键同参返回原成功结果；同键异参返回 409；无记录返回 null 继续执行。
     */
    private AnalysisOutcome checkReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new AnalysisOutcome(entry.responseStatus(),
                readJson(entry.responseBody(), new TypeReference<>() {
                }));
    }

    /**
     * 占位写入去重记录；并发同键时主键冲突，读取已提交结果：同参重放、异参 409。
     */
    private AnalysisOutcome insertPlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "ANALYZE");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new AnalysisOutcome(entry.responseStatus(),
                    readJson(entry.responseBody(), new TypeReference<>() {
                    }));
        }
    }

    /**
     * 分析生成成功后回填去重记录响应，与分析记录同事务提交。
     */
    private AnalysisOutcome complete(String requestId, HttpStatus status, ExcursionAnalysisResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeJson(body));
        return new AnalysisOutcome(status.value(), body);
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

    /**
     * 加锁核验后的引用与当前快照对。
     */
    private record LockedRef(CreateExcursionAnalysisRequest.ObservationRef ref,
                             MinuteObservationSnapshot snapshot) {
    }

    /**
     * 分析生成结果：HTTP 状态码与响应体。
     */
    public record AnalysisOutcome(int status, ExcursionAnalysisResponse body) {
    }
}
