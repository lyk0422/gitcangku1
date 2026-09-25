package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 取消走廊预约请求。取消立即从容量计数中移除，历史记录保留。
 *
 * @param reservationId 预约唯一标识
 * @param requestId     写操作全局唯一请求标识，用于幂等重放
 */
public record ReservationCancelRequest(
        @NotBlank @Size(max = 64) String reservationId,
        @NotBlank @Size(max = 64) String requestId) {
}
