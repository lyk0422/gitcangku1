package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 取消走廊预约请求。取消后立即从容量计数移除，历史记录保留。
 *
 * @param reservationKey 预约全局唯一业务键
 * @param requestId      写操作全局唯一请求标识，用于幂等重放
 */
public record ReservationCancelRequest(
        @NotBlank @Size(max = 64) String reservationKey,
        @NotBlank @Size(max = 64) String requestId) {
}
