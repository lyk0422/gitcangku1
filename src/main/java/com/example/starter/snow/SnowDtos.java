package com.example.starter.snow;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 接口请求与响应 DTO 集合；时间字段均为 UTC ISO-8601 字符串。
 */
final class SnowDtos {

    private SnowDtos() {
    }

    /** 雪况读数上报请求；hourUtc 必须为整点。 */
    record ReadingRequest(
            @NotBlank String requestId,
            @NotBlank String hourUtc,
            @NotNull Integer snowDepthCm,
            @NotNull Integer newSnowCm,
            String source) {
    }

    /** 旧读数勘误请求；两个修正字段至少一个非空。 */
    record CorrectionRequest(
            @NotBlank String requestId,
            @NotBlank String reviewer,
            Integer correctedSnowDepthCm,
            Integer correctedNewSnowCm,
            String note) {
    }

    /** 解除审核创建请求，reviewer 作为第一名确认人。 */
    record ReviewCreateRequest(
            @NotBlank String requestId,
            @NotBlank String reviewer) {
    }

    /** 解除审核确认请求。 */
    record ConfirmationRequest(
            @NotBlank String requestId,
            @NotBlank String reviewer) {
    }

    /** 读数上报响应。 */
    record ReadingResponse(
            long readingId,
            String areaId,
            String hourUtc,
            boolean closureCreated,
            int invalidatedReviews) {
    }

    /** 勘误响应。 */
    record CorrectionResponse(
            long correctionId,
            long readingId,
            boolean closureCreated,
            int invalidatedReviews) {
    }

    /** 审核单响应；liftVersion 仅在第二人确认生成解除版本后非空。 */
    record ReviewResponse(
            long reviewId,
            String status,
            List<String> reviewers,
            Long liftVersion) {
    }

    /** 单个小时窗口读数视图；effectiveNewSnowCm 为勘误后的有效新增积雪。 */
    record WindowReading(
            String hourUtc,
            int snowDepthCm,
            int newSnowCm,
            Integer effectiveNewSnowCm) {
    }

    /** 审核单视图。 */
    record ReviewView(
            long id,
            String status,
            List<String> reviewers,
            String invalidatedReason,
            String windowStart,
            String windowEnd,
            long readingsVersion) {
    }

    /** 封闭/解除时间线事件。 */
    record TimelineEvent(
            String type,
            String at,
            String detail) {
    }

    /** 区域状态查询响应。 */
    record AreaStatusResponse(
            String areaId,
            String state,
            Long activeClosureId,
            boolean windowComplete,
            boolean windowWithinLimit,
            List<WindowReading> recentWindow,
            List<ReviewView> reviews,
            List<TimelineEvent> timeline) {
    }

    /** 服务层结果，携带 HTTP 状态码以支持幂等重放原状态。 */
    record ServiceResult<T>(int status, T body) {
    }
}