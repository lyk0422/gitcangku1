package com.example.starter.dive;

import java.time.Instant;
import java.util.List;

/**
 * 潜水作业记录接口的数据传输对象集合。
 */
public final class DiveDtos {

    private DiveDtos() {
    }

    /** 单个深度点位入参：UTC观测时刻与深度（米）。 */
    public record PointInput(Instant observedAtUtc, double depthM) {
    }

    /** 创建作业请求：计划限制与作业起止（UTC），requestId为幂等键。 */
    public record CreateLogRequest(String requestId, int planMaxDepthM, int planMaxDurationMin,
            Instant operationStartUtc, Instant operationEndUtc) {
    }

    /** 追加点位请求：requestId为幂等键，points按时间顺序上传。 */
    public record AppendPointsRequest(String requestId, List<PointInput> points) {
    }

    /** 审核请求：requestId为幂等键。 */
    public record ReviewRequest(String requestId) {
    }

    /** 作业概要响应。 */
    public record LogDto(long id, int planMaxDepthM, int planMaxDurationMin,
            Instant operationStartUtc, Instant operationEndUtc, int pointVersion, Instant createdAtUtc) {
    }

    /** 点位视图。 */
    public record PointDto(int seq, Instant observedAtUtc, double depthM, int logVersion, boolean errata) {
    }

    /** 追加点位响应。 */
    public record AppendResult(long diveLogId, int pointVersion, int appended, boolean errataRecorded) {
    }

    /** 审核异常明细：类型、说明与关联点位。 */
    public record AnomalyDto(String type, String message, Integer pointSeq,
            Instant observedAtUtc, Double depthM) {
    }

    /** 审核结论视图。 */
    public record ReviewDto(long id, long diveLogId, int pointVersion, int pointCount,
            String decision, List<AnomalyDto> anomalies, String status, Instant createdAtUtc) {
    }

    /** 复核链记录视图。 */
    public record ReReviewDto(long id, long staleReviewId, int fromPointVersion,
            int toPointVersion, int appendedPoints, Instant createdAtUtc) {
    }

    /** 作业详情：原始点、当前审核异常区间、审核版本与复核链。 */
    public record LogDetailDto(LogDto log, List<PointDto> points, ReviewDto currentReview,
            List<ReviewDto> reviews, List<ReReviewDto> reReviews) {
    }
}