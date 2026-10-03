package com.example.starter.dive.web;

import com.example.starter.dive.domain.Anomaly;
import com.example.starter.dive.domain.DepthPoint;
import com.example.starter.dive.domain.DiveReview;
import com.example.starter.dive.domain.DiveSession;
import com.example.starter.dive.service.DiveLogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 潜水作业记录与审核REST入口（合成训练数据，仅记录与审核，不提供医疗建议）。
 */
@RestController
@RequestMapping("/api/dive/sessions")
public class DiveLogController {

    private final DiveLogService service;

    public DiveLogController(DiveLogService service) {
        this.service = service;
    }

    /** 追加上传点位：同requestId同参重放、异参409。 */
    @PostMapping("/{sessionId}/points")
    public UploadResponse uploadPoints(@PathVariable long sessionId,
                                       @Valid @RequestBody UploadPointsRequest request) {
        List<DiveLogService.NewPoint> points = request.points().stream()
                .map(item -> new DiveLogService.NewPoint(item.recordedAtUtc(), item.depthM()))
                .toList();
        DiveLogService.UploadResult result =
                service.uploadPoints(sessionId, request.requestId(), points);
        return new UploadResponse(result.sessionId(), result.pointVersion(), result.acceptedCount(),
                result.firstSeq(), result.lastSeq(), result.replayed());
    }

    /** 请求上浮审核：返回通过或带具体异常的拒绝。 */
    @PostMapping("/{sessionId}/reviews")
    public ReviewResponse requestReview(@PathVariable long sessionId,
                                        @Valid @RequestBody ReviewRequest request) {
        DiveLogService.ReviewResult result = service.requestReview(sessionId, request.requestId());
        return new ReviewResponse(result.reviewId(), result.decision(), result.pointVersion(),
                result.anomalies(), result.replayed());
    }

    /** 查询总览：原始点、异常区间、审核版本与复核链。 */
    @GetMapping("/{sessionId}/overview")
    public OverviewResponse overview(@PathVariable long sessionId) {
        DiveLogService.OverviewResult result = service.overview(sessionId);
        return new OverviewResponse(result.session(), result.points(),
                result.activeReview(), result.reviews());
    }

    /** 点位上传请求。 */
    public record UploadPointsRequest(
            @NotBlank @Size(max = 64) String requestId,
            @NotEmpty List<@Valid PointItem> points) {
    }

    /** 单个待上传点位。 */
    public record PointItem(@NotNull Instant recordedAtUtc, @NotNull BigDecimal depthM) {
    }

    /** 点位上传响应。 */
    public record UploadResponse(long sessionId, int pointVersion, int acceptedCount,
                                 int firstSeq, int lastSeq, boolean replayed) {
    }

    /** 审核请求。 */
    public record ReviewRequest(@NotBlank @Size(max = 64) String requestId) {
    }

    /** 审核响应。 */
    public record ReviewResponse(long reviewId, DiveReview.ReviewDecision decision, int pointVersion,
                                 List<Anomaly> anomalies, boolean replayed) {
    }

    /** 总览响应。 */
    public record OverviewResponse(DiveSession session, List<DepthPoint> points,
                                   DiveReview activeReview, List<DiveReview> reviews) {
    }
}