package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 解除设备隔离请求。审核人必须不同于该轮隔离确认人，解除后设备可领取新一轮投放任务，
 * 历史连续失败次数保留不清除。
 *
 * @param requestId 写操作幂等请求ID
 * @param operator  解除审核人
 * @param reason    解除原因
 */
public record ReleaseQuarantineRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 256) String reason) {
}
