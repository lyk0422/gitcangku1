package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 预留起飞标记请求。已起飞预留仍占用其窗口容量直至窗口结束，但此后不能被挤出；
 * 重复标记同一条预留返回 409。
 *
 * @param reservationId 预留唯一标识
 * @param requestId     写操作全局唯一请求标识，用于幂等重放
 */
public record DepartureRequest(
        @NotBlank @Size(max = 64) String reservationId,
        @NotBlank @Size(max = 64) String requestId) {
}
