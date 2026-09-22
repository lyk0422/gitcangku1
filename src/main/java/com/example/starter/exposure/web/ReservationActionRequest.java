package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 确认/取消预占请求体，仅携带幂等键；预占单编号来自路径。
 *
 * @param requestId 写操作全局唯一幂等键
 */
public record ReservationActionRequest(
        @NotBlank @Size(max = 64) String requestId
) {
}
