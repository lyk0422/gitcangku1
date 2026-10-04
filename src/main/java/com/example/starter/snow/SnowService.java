package com.example.starter.snow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.snow.SnowDtos.AreaStatusResponse;
import com.example.starter.snow.SnowDtos.ConfirmationRequest;
import com.example.starter.snow.SnowDtos.CorrectionRequest;
import com.example.starter.snow.SnowDtos.CorrectionResponse;
import com.example.starter.snow.SnowDtos.ReadingRequest;
import com.example.starter.snow.SnowDtos.ReadingResponse;
import com.example.starter.snow.SnowDtos.ReviewCreateRequest;
import com.example.starter.snow.SnowDtos.ReviewResponse;
import com.example.starter.snow.SnowDtos.ReviewView;
import com.example.starter.snow.SnowDtos.ServiceResult;
import com.example.starter.snow.SnowDtos.TimelineEvent;
import com.example.starter.snow.SnowDtos.WindowReading;
import com.example.starter.snow.SnowRepository.ClosureRow;
import com.example.starter.snow.SnowRepository.DecisionRow;
import com.example.starter.snow.SnowRepository.IdemRow;
import com.example.starter.snow.SnowRepository.ReviewRow;
import com.example.starter.snow.SnowRepository.WindowRow;

/**
 * 雪崩控制区业务服务：合成雪况记录、封闭命令、解除双人审核与状态查询。
 * 每区域写操作通过区域行 FOR UPDATE 锁串行化，保证读数版本一致与并发确认只产生一个解除版本。
 */
@Service
public class SnowService {

    /** 新增积雪超限阈值(厘米)：超过该值视为积雪异常。 */
    static final int NEW_SNOW_LIMIT_CM = 5;

    /** 解除审核要求的连续整点个数。 */
    static final int WINDOW_HOURS = 3;

    private final SnowRepository repo;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public SnowService(SnowRepository repo, Clock clock, ObjectMapper objectMapper) {
        this.repo = repo;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /** 超限读数带来的副作用：失效的审核单数量与是否新建封闭命令。 */
    private record Effects(boolean closureCreated, int invalidated) {
    }

    /** 上报整点雪况读数；超限读数使未完成审核失效并在区域开放时创建封闭命令。 */
    @Transactional
    public ServiceResult<ReadingResponse> submitReading(String areaId, ReadingRequest req) {
        Instant hour = parseHour(req.hourUtc());
        Instant now = Instant.now(clock);
        repo.ensureArea(areaId, areaId, now);
        repo.lockArea(areaId);
        String source = req.source() == null || req.source().isBlank() ? "REPORT" : req.source();
        String payload = areaId + "|" + req.hourUtc() + "|" + req.snowDepthCm() + "|" + req.newSnowCm() + "|" + source;
        return idempotent("reading:" + areaId, req.requestId(), payload, ReadingResponse.class, () -> {
            if (repo.findReadingId(areaId, hour).isPresent()) {
                throw ApiException.conflict("READING_ALREADY_EXISTS", "该整点读数已存在，旧读数仅可追加勘误");
            }
            long id = repo.insertReading(areaId, hour, req.snowDepthCm(), req.newSnowCm(), source,
                    req.requestId(), now);
            Effects effects = applyAnomalyIfNeeded(areaId, req.newSnowCm(), now);
            return new ServiceResult<>(201,
                    new ReadingResponse(id, areaId, req.hourUtc(), effects.closureCreated(), effects.invalidated()));
        });
    }

    /** 对旧读数追加勘误与复核结果，不修改原读数；勘误后超限同样触发审核失效与封闭。 */
    @Transactional
    public ServiceResult<CorrectionResponse> submitCorrection(String areaId, String hourUtc, CorrectionRequest req) {
        Instant hour = parseHour(hourUtc);
        Instant now = Instant.now(clock);
        repo.ensureArea(areaId, areaId, now);
        repo.lockArea(areaId);
        if (req.correctedSnowDepthCm() == null && req.correctedNewSnowCm() == null) {
            throw ApiException.unprocessable("EMPTY_CORRECTION", "勘误至少修正一项");
        }
        String payload = areaId + "|" + hourUtc + "|" + req.correctedSnowDepthCm() + "|"
                + req.correctedNewSnowCm() + "|" + req.reviewer() + "|" + req.note();
        return idempotent("correction:" + areaId + ":" + hour, req.requestId(), payload, CorrectionResponse.class,
                () -> {
                    Long readingId = repo.findReadingId(areaId, hour)
                            .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND", "该整点读数不存在"));
                    long id = repo.insertCorrection(readingId, req.correctedSnowDepthCm(), req.correctedNewSnowCm(),
                            req.reviewer(), req.note(), req.requestId(), now);
                    Effects effects = req.correctedNewSnowCm() == null
                            ? new Effects(false, 0)
                            : applyAnomalyIfNeeded(areaId, req.correctedNewSnowCm(), now);
                    return new ServiceResult<>(201,
                            new CorrectionResponse(id, readingId, effects.closureCreated(), effects.invalidated()));
                });
    }

    /** 新读数或勘误超限时：未完成双人审核失效；区域无生效封闭命令时创建新封闭命令。 */
    private Effects applyAnomalyIfNeeded(String areaId, int effectiveNewSnowCm, Instant now) {
        if (effectiveNewSnowCm <= NEW_SNOW_LIMIT_CM) {
            return new Effects(false, 0);
        }
        int invalidated = repo.invalidatePendingReviews(areaId,
                "NEW_OVER_LIMIT_READING: 新增积雪" + effectiveNewSnowCm + "cm超过" + NEW_SNOW_LIMIT_CM + "cm", now);
        boolean created = false;
        if (repo.findOpenClosure(areaId).isEmpty()) {
            repo.insertClosure(areaId, "新增积雪超限(" + effectiveNewSnowCm + "cm)，自动封闭", now);
            created = true;
        }
        return new Effects(created, invalidated);
    }

    /** 解析并校验整点；非 ISO-8601 或非整点返回 422。 */
    private Instant parseHour(String hourUtc) {
        final Instant instant;
        try {
            instant = Instant.parse(hourUtc);
        } catch (DateTimeParseException e) {
            throw ApiException.unprocessable("INVALID_HOUR", "hourUtc 必须为 UTC ISO-8601 时间，如 2026-01-01T10:00:00Z");
        }
        if (!instant.equals(instant.truncatedTo(ChronoUnit.HOURS))) {
            throw ApiException.unprocessable("INVALID_HOUR", "hourUtc 必须为整点");
        }
        return instant;
    }

    /** 最近三个已完成整点(UTC)。 */
    private List<Instant> windowHours() {
        Instant currentHour = Instant.now(clock).truncatedTo(ChronoUnit.HOURS);
        return hoursBetween(currentHour.minus(WINDOW_HOURS, ChronoUnit.HOURS),
                currentHour.minus(1, ChronoUnit.HOURS));
    }

    /** 生成 [start, end] 逐小时整点列表。 */
    private List<Instant> hoursBetween(Instant start, Instant end) {
        List<Instant> hours = new ArrayList<>();
        for (Instant h = start; !h.isAfter(end); h = h.plus(1, ChronoUnit.HOURS)) {
            hours.add(h);
        }
        return hours;
    }

    /** 校验窗口读数完整且逐小时均不超限；缺整点或超限返回 422。 */
    private List<WindowRow> validateWindow(String areaId, List<Instant> hours) {
        List<WindowRow> rows = repo.findWindowRows(areaId, hours);
        if (rows.size() < hours.size()) {
            throw ApiException.unprocessable("MISSING_HOURLY_READINGS",
                    "最近连续" + WINDOW_HOURS + "个整点读数不完整");
        }
        for (WindowRow row : rows) {
            if (row.effectiveNewSnowCm() > NEW_SNOW_LIMIT_CM) {
                throw ApiException.unprocessable("WINDOW_EXCEEDS_LIMIT",
                        "整点" + row.hourUtc() + "新增积雪" + row.effectiveNewSnowCm() + "cm超过"
                                + NEW_SNOW_LIMIT_CM + "cm");
            }
        }
        return rows;
    }
    /** 创建解除审核单并记录第一名确认人；区域未封闭返回 409，窗口缺读数或超限返回 422。 */
    @Transactional
    public ServiceResult<ReviewResponse> createReview(String areaId, ReviewCreateRequest req) {
        Instant now = Instant.now(clock);
        repo.ensureArea(areaId, areaId, now);
        repo.lockArea(areaId);
        List<Instant> hours = windowHours();
        String payload = areaId + "|" + req.reviewer();
        return idempotent("review-create:" + areaId, req.requestId(), payload, ReviewResponse.class, () -> {
            if (repo.findOpenClosure(areaId).isEmpty()) {
                throw ApiException.conflict("AREA_NOT_CLOSED", "区域未封闭，无需解除审核");
            }
            validateWindow(areaId, hours);
            long version = repo.countReadings(areaId);
            long reviewId = repo.insertReview(areaId, hours.get(0), hours.get(hours.size() - 1), version, now);
            repo.insertConfirmation(reviewId, req.reviewer(), req.requestId(), now);
            return new ServiceResult<>(201,
                    new ReviewResponse(reviewId, "PENDING", List.of(req.reviewer()), null));
        });
    }

    /** 第二名审核员确认；依据审核单快照的读数版本复核，一致才生成解除版本。 */
    @Transactional
    public ServiceResult<ReviewResponse> confirm(String areaId, long reviewId, ConfirmationRequest req) {
        Instant now = Instant.now(clock);
        repo.ensureArea(areaId, areaId, now);
        repo.lockArea(areaId);
        String payload = reviewId + "|" + req.reviewer();
        return idempotent("confirm:" + reviewId, req.requestId(), payload, ReviewResponse.class, () -> {
            ReviewRow review = repo.findReview(reviewId)
                    .filter(r -> r.areaId().equals(areaId))
                    .orElseThrow(() -> ApiException.notFound("REVIEW_NOT_FOUND", "审核单不存在"));
            List<String> reviewers = repo.listReviewers(reviewId);
            if (reviewers.contains(req.reviewer())) {
                throw ApiException.conflict("DUPLICATE_REVIEWER", "同一审核员不可重复签字");
            }
            if (!"PENDING".equals(review.status())) {
                throw ApiException.conflict("REVIEW_NOT_PENDING",
                        "审核单状态为" + review.status() + "，不可继续确认");
            }
            if (repo.countReadings(areaId) != review.readingsVersion()) {
                repo.updateReviewStatus(reviewId, "INVALIDATED", "READINGS_VERSION_CONFLICT: 审核期间读数已变化", now);
                throw ApiException.conflict("READINGS_VERSION_CONFLICT", "读数版本已变化，审核失效");
            }
            try {
                validateWindow(areaId, hoursBetween(review.windowStart(), review.windowEnd()));
            } catch (ApiException e) {
                repo.updateReviewStatus(reviewId, "INVALIDATED", e.getCode(), now);
                throw e;
            }
            if (repo.findOpenClosure(areaId).isEmpty()) {
                throw ApiException.conflict("AREA_NOT_CLOSED", "区域未封闭，无需解除审核");
            }
            repo.insertConfirmation(reviewId, req.reviewer(), req.requestId(), now);
            List<String> all = repo.listReviewers(reviewId);
            Long liftVersion = null;
            String status = "PENDING";
            if (all.size() >= 2) {
                long version = repo.nextLiftVersion(areaId);
                repo.insertDecision(areaId, reviewId, version, now);
                repo.updateReviewStatus(reviewId, "CONFIRMED", null, now);
                repo.closeOpenClosures(areaId, now);
                liftVersion = version;
                status = "CONFIRMED";
            }
            return new ServiceResult<>(200, new ReviewResponse(reviewId, status, all, liftVersion));
        });
    }

    /** 查询区域状态：连续整点读数、审核人、失效原因与封闭/解除时间线。 */
    @Transactional(readOnly = true)
    public AreaStatusResponse getStatus(String areaId) {
        List<Instant> hours = windowHours();
        List<WindowRow> rows = repo.findWindowRows(areaId, hours);
        List<WindowReading> window = rows.stream()
                .map(r -> new WindowReading(r.hourUtc().toString(), r.snowDepthCm(), r.newSnowCm(),
                        r.effectiveNewSnowCm()))
                .toList();
        boolean complete = rows.size() == hours.size();
        boolean withinLimit = complete
                && rows.stream().allMatch(r -> r.effectiveNewSnowCm() <= NEW_SNOW_LIMIT_CM);
        var openClosure = repo.findOpenClosure(areaId);
        List<ReviewView> reviews = repo.listReviews(areaId).stream()
                .map(r -> new ReviewView(r.id(), r.status(), repo.listReviewers(r.id()), r.invalidatedReason(),
                        r.windowStart().toString(), r.windowEnd().toString(), r.readingsVersion()))
                .toList();
        List<TimelineEvent> timeline = new ArrayList<>();
        for (ClosureRow c : repo.listClosures(areaId)) {
            timeline.add(new TimelineEvent("CLOSURE_OPENED", c.createdAt().toString(),
                    "封闭命令#" + c.id() + ": " + c.reason()));
            if (c.closedAt() != null) {
                timeline.add(new TimelineEvent("CLOSURE_LIFTED", c.closedAt().toString(),
                        "封闭命令#" + c.id() + " 已解除"));
            }
        }
        for (DecisionRow d : repo.listDecisions(areaId)) {
            timeline.add(new TimelineEvent("LIFT_DECIDED", d.effectiveAt().toString(),
                    "解除版本v" + d.version() + "，来源审核单#" + d.reviewId()));
        }
        timeline.sort(Comparator.comparing(TimelineEvent::at));
        return new AreaStatusResponse(areaId, openClosure.isPresent() ? "CLOSED" : "OPEN",
                openClosure.map(ClosureRow::id).orElse(null), complete, withinLimit, window, reviews, timeline);
    }

    /** 幂等执行：同 requestId 同参返回首次响应，异参返回 409。 */
    private <T> ServiceResult<T> idempotent(String scope, String requestId, String payload, Class<T> type,
            Supplier<ServiceResult<T>> action) {
        String hash = sha256(payload);
        var existing = repo.findIdempotency(scope, requestId);
        if (existing.isPresent()) {
            IdemRow row = existing.get();
            if (!row.payloadHash().equals(hash)) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "相同requestId携带不同参数");
            }
            return new ServiceResult<>(row.status(), readBody(row.body(), type));
        }
        ServiceResult<T> result = action.get();
        repo.insertIdempotency(scope, requestId, hash, result.status(), writeBody(result.body()),
                Instant.now(clock));
        return result;
    }

    private <T> T readBody(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private String writeBody(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应序列化失败", e);
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}