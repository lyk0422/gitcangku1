package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建走廊时段预约请求。时段为 UTC 左闭右开 [startTime, endTime)，时长 1~120 分钟。
 *
 * @param reservationKey 预约幂等键，全局唯一；同键同参重放首次结果，异参 409，失败不占键
 * @param corridorId     走廊唯一标识
 * @param startTime      时段起始（含），ISO-8601 UTC 时刻，如 2026-09-25T10:00:00Z
 * @param endTime        时段结束（不含），ISO-8601 UTC 时刻
 * @param reviewId       关联的已 CLEAR 审核结果标识；审核已 STALE 或非 CLEAR 返回 422
 */
public record ReservationCreateRequest(
        @NotBlank @Size(max = 64) String reservationKey,
        @NotBlank @Size(max = 64) String corridorId,
        @NotBlank @Size(max = 40) String startTime,
        @NotBlank @Size(max = 40) String endTime,
        @NotBlank @Size(max = 64) String reviewId) {
}
