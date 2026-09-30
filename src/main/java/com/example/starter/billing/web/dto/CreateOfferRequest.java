package com.example.starter.billing.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 调度方发布削峰邀约请求；时段为 UTC 左闭右开。
 *
 * @param offerId   邀约 ID（调度方事件标识，全局唯一）
 * @param startTime 时段起点（UTC，含）
 * @param endTime   时段终点（UTC，不含），必须晚于起点
 */
public record CreateOfferRequest(
        @NotBlank(message = "邀约ID不能为空")
        String offerId,
        @NotNull(message = "时段起点不能为空")
        Instant startTime,
        @NotNull(message = "时段终点不能为空")
        Instant endTime) {
}
