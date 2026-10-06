package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 确认/取消预占请求体；预占单编号来自路径。
 *
 * @param requestId       写操作全局唯一幂等键
 * @param expectedVersion 期望的预占单当前版本号；提供时与当前版本不一致返回 409，缺省不校验
 */
public record ReservationActionRequest(
        @NotBlank @Size(max = 64) String requestId,
        Long expectedVersion
) {
    /** 兼容仅携带幂等键的确认/取消请求。 */
    public ReservationActionRequest(String requestId) {
        this(requestId, null);
    }
}
