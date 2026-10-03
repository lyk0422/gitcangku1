package com.example.starter.dive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

import com.example.starter.dive.DiveDtos.AnomalyDto;
import com.example.starter.dive.DiveDtos.AppendPointsRequest;
import com.example.starter.dive.DiveDtos.AppendResult;
import com.example.starter.dive.DiveDtos.CreateLogRequest;
import com.example.starter.dive.DiveDtos.LogDetailDto;
import com.example.starter.dive.DiveDtos.LogDto;
import com.example.starter.dive.DiveDtos.PointDto;
import com.example.starter.dive.DiveDtos.PointInput;
import com.example.starter.dive.DiveDtos.ReReviewDto;
import com.example.starter.dive.DiveDtos.ReviewDto;
import com.example.starter.dive.DiveDtos.ReviewRequest;
import com.example.starter.dive.DiveLogRepository.LogRow;
import com.example.starter.dive.DiveLogRepository.PointRow;
import com.example.starter.dive.DiveLogRepository.ReviewRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 潜水作业记录业务：点位上传、上浮审核、勘误复核与查询。
 * 幂等规则：同requestId同参重放、异参409、失败回滚不占键。
 */
@Service
public class DiveLogService {

    private static final Duration MAX_MEASUREMENT_GAP = Duration.ofMinutes(5);

    private final DiveLogRepository repository;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DiveLogService(DiveLogRepository repository, TransactionTemplate transactions,
            ObjectMapper objectMapper, Clock businessClock) {
        this.repository = repository;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.clock = businessClock;
    }

    /** 创建作业记录，幂等。 */
    public LogDto createLog(CreateLogRequest request) {
        requireText(request.requestId(), "requestId不能为空");
        if (request.planMaxDepthM() <= 0 || request.planMaxDurationMin() <= 0) {
            throw new BadRequestException("计划深度与时长上限必须为正数");
        }
        if (request.operationStartUtc() == null || request.operationEndUtc() == null
                || !request.operationStartUtc().isBefore(request.operationEndUtc())) {
            throw new BadRequestException("作业起止时刻非法");
        }
        return idempotent("CREATE_LOG", "logs", request.requestId(), request, LogDto.class, () -> {
            long id = repository.insertLog(request.planMaxDepthM(), request.planMaxDurationMin(),
                    request.operationStartUtc(), request.operationEndUtc(), Instant.now(clock));
            LogRow row = repository.findLog(id).orElseThrow();
            return toLogDto(row);
        });
    }

    /** 追加点位（原始点或勘误点），幂等；已审核作业追加时生成复核记录并置原审核过期。 */
    public AppendResult appendPoints(long logId, AppendPointsRequest request) {
        requireText(request.requestId(), "requestId不能为空");
        if (request.points() == null || request.points().isEmpty()) {
            throw new BadRequestException("点位列表不能为空");
        }
        for (PointInput p : request.points()) {
            if (p == null || p.observedAtUtc() == null || !Double.isFinite(p.depthM())) {
                throw new BadRequestException("点位缺少观测时刻或深度非法");
            }
        }
        return idempotent("APPEND_POINTS", "log:" + logId, request.requestId(), request,
                AppendResult.class, () -> {
                    LogRow log = repository.lockLog(logId)
                            .orElseThrow(() -> new NotFoundException("作业不存在: " + logId));
                    var currentReview = repository.findCurrentReview(logId);
                    boolean errata = currentReview.isPresent();
                    int newVersion = log.pointVersion() + 1;
                    int seq = repository.maxPointSeq(logId);
                    Instant now = Instant.now(clock);
                    for (PointInput p : request.points()) {
                        repository.insertPoint(logId, ++seq, p.observedAtUtc(), p.depthM(),
                                newVersion, errata, now);
                    }
                    repository.updatePointVersion(logId, newVersion);
                    if (errata) {
                        repository.markReviewsStale(logId);
                        repository.insertReReview(logId, currentReview.get().id(),
                                log.pointVersion(), newVersion, request.points().size(), now);
                    }
                    return new AppendResult(logId, newVersion, request.points().size(), errata);
                });
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(message);
        }
    }

    /**
     * 幂等执行：登记键与业务写入同事务提交，失败回滚不占键；
     * 并发同键插入冲突时重读登记记录，同参重放、异参409。
     */
    private <T> T idempotent(String operation, String resourceKey, String requestId,
            Object request, Class<T> type, Supplier<T> action) {
        String requestHash = hash(request);
        try {
            return transactions.execute(status -> {
                var existing = repository.findIdempotency(operation, resourceKey, requestId);
                if (existing.isPresent()) {
                    return replay(existing.get(), requestHash, type);
                }
                T result = action.get();
                repository.insertIdempotency(operation, resourceKey, requestId, requestHash,
                        200, writeJson(result), Instant.now(clock));
                return result;
            });
        } catch (DuplicateKeyException e) {
            var existing = repository.findIdempotency(operation, resourceKey, requestId)
                    .orElseThrow(() -> e);
            return replay(existing, requestHash, type);
        }
    }

    private <T> T replay(DiveLogRepository.IdemRow existing, String requestHash, Class<T> type) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new ConflictException("requestId已使用且参数不一致");
        }
        try {
            return objectMapper.readValue(existing.responseBody(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应快照损坏", e);
        }
    }

    private String hash(Object request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(writeJson(request).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }    /** 上浮审核：读取完整一致的点位集合，产出通过/拒绝与异常明细，按版本冻结。 */
    public ReviewDto review(long logId, ReviewRequest request) {
        requireText(request.requestId(), "requestId不能为空");
        return idempotent("REVIEW", "log:" + logId, request.requestId(), request,
                ReviewDto.class, () -> {
                    LogRow log = repository.lockLog(logId)
                            .orElseThrow(() -> new NotFoundException("作业不存在: " + logId));
                    List<PointRow> points = repository.findPoints(logId);
                    if (points.isEmpty()) {
                        throw new BusinessException("无点位数据，无法审核");
                    }
                    var approved = repository.findReview(logId, log.pointVersion(), "APPROVED");
                    if (approved.isPresent()) {
                        return toReviewDto(approved.get());
                    }
                    List<AnomalyDto> anomalies = evaluate(log, points);
                    String decision = anomalies.isEmpty() ? "APPROVED" : "REJECTED";
                    var existing = repository.findReview(logId, log.pointVersion(), decision);
                    if (existing.isPresent()) {
                        return toReviewDto(existing.get());
                    }
                    long reviewId = repository.insertReview(logId, log.pointVersion(),
                            points.size(), decision, writeJson(anomalies), "CURRENT",
                            request.requestId(), Instant.now(clock));
                    return toReviewDto(repository.findReviews(logId).stream()
                            .filter(r -> r.id() == reviewId).findFirst().orElseThrow());
                });
    }

    /** 审核规则：超深、负深度、缺测、时间倒退、首末点未覆盖作业起止、总时长超限。 */
    private List<AnomalyDto> evaluate(LogRow log, List<PointRow> points) {
        List<AnomalyDto> anomalies = new ArrayList<>();
        PointRow prev = null;
        for (PointRow p : points) {
            if (p.depthM() > log.planMaxDepthM()) {
                anomalies.add(new AnomalyDto("DEPTH_EXCEEDED",
                        "第" + p.seq() + "点深度" + p.depthM() + "米，超过计划上限"
                                + log.planMaxDepthM() + "米",
                        p.seq(), p.observedAtUtc(), p.depthM()));
            }
            if (p.depthM() < 0) {
                anomalies.add(new AnomalyDto("DEPTH_NEGATIVE",
                        "第" + p.seq() + "点深度" + p.depthM() + "米为负值",
                        p.seq(), p.observedAtUtc(), p.depthM()));
            }
            if (prev != null) {
                if (!p.observedAtUtc().isAfter(prev.observedAtUtc())) {
                    anomalies.add(new AnomalyDto("TIME_REGRESSION",
                            "第" + p.seq() + "点时刻" + p.observedAtUtc()
                                    + "未晚于上一点" + prev.observedAtUtc(),
                            p.seq(), p.observedAtUtc(), p.depthM()));
                } else {
                    Duration gap = Duration.between(prev.observedAtUtc(), p.observedAtUtc());
                    if (gap.compareTo(MAX_MEASUREMENT_GAP) > 0) {
                        anomalies.add(new AnomalyDto("MEASUREMENT_GAP",
                                "第" + prev.seq() + "点至第" + p.seq() + "点间隔"
                                        + gap.toMinutes() + "分钟，超过5分钟缺测上限",
                                p.seq(), p.observedAtUtc(), p.depthM()));
                    }
                }
            }
            prev = p;
        }
        PointRow first = points.get(0);
        PointRow last = points.get(points.size() - 1);
        if (!first.observedAtUtc().equals(log.operationStartUtc())
                || !last.observedAtUtc().equals(log.operationEndUtc())) {
            anomalies.add(new AnomalyDto("COVERAGE_MISSING",
                    "首点" + first.observedAtUtc() + "、末点" + last.observedAtUtc()
                            + "未覆盖作业起止" + log.operationStartUtc() + "至"
                            + log.operationEndUtc(),
                    null, null, null));
        }
        Duration total = Duration.between(first.observedAtUtc(), last.observedAtUtc());
        if (total.compareTo(Duration.ofMinutes(log.planMaxDurationMin())) > 0) {
            anomalies.add(new AnomalyDto("DURATION_EXCEEDED",
                    "总时长" + total.toMinutes() + "分钟，超过连续作业上限"
                            + log.planMaxDurationMin() + "分钟",
                    null, null, null));
        }
        return anomalies;
    }

    /** 查询作业详情：原始点、当前审核异常、审核版本与复核链。 */
    public LogDetailDto detail(long logId) {
        LogRow log = repository.findLog(logId)
                .orElseThrow(() -> new NotFoundException("作业不存在: " + logId));
        List<PointDto> points = repository.findPoints(logId).stream()
                .map(p -> new PointDto(p.seq(), p.observedAtUtc(), p.depthM(), p.logVersion(),
                        p.errata()))
                .toList();
        ReviewDto current = repository.findCurrentReview(logId).map(this::toReviewDto).orElse(null);
        List<ReviewDto> reviews = repository.findReviews(logId).stream()
                .map(this::toReviewDto).toList();
        List<ReReviewDto> reReviews = repository.findReReviews(logId).stream()
                .map(r -> new ReReviewDto(r.id(), r.staleReviewId(), r.fromPointVersion(),
                        r.toPointVersion(), r.appendedPoints(), r.createdAt()))
                .toList();
        return new LogDetailDto(toLogDto(log), points, current, reviews, reReviews);
    }

    private LogDto toLogDto(LogRow row) {
        return new LogDto(row.id(), row.planMaxDepthM(), row.planMaxDurationMin(),
                row.operationStartUtc(), row.operationEndUtc(), row.pointVersion(), row.createdAt());
    }

    private ReviewDto toReviewDto(ReviewRow row) {
        List<AnomalyDto> anomalies;
        try {
            anomalies = row.anomalies() == null ? List.of()
                    : objectMapper.readValue(row.anomalies(), new TypeReference<>() {
                    });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("审核异常明细损坏", e);
        }
        return new ReviewDto(row.id(), row.diveLogId(), row.pointVersion(), row.pointCount(),
                row.decision(), anomalies, row.status(), row.createdAt());
    }
}