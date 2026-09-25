package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建走廊预约请求。时间窗为 UTC 左闭右开，时长 1～120 分钟；
 * 必须关联一条当前仍有效的 CLEAR 审核结果，且审核航线与走廊相交。
 *
 * @param reservationKey 预约全局唯一业务键
 * @param corridorId     走廊标识
 * @param startTime      开始时刻，epoch 毫秒（UTC），含
 * @param endTime        结束时刻，epoch 毫秒（UTC），不含
 * @param reviewId       关联的已 CLEAR 审核结果标识（仅引用）
 * @param requestId      写操作全局唯一请求标识，用于幂等重放
 */
public record ReservationCreateRequest(
        @NotBlank @Size(max = 64) String reservationKey,
        @NotBlank @Size(max = 64) String corridorId,
        @NotNull Long startTime,
        @NotNull Long endTime,
        @NotBlank @Size(max = 64) String reviewId,
        @NotBlank @Size(max = 64) String requestId) {
}
