package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 冻结恢复申请请求：仅 FROZEN 记录可申请；申请人后续不能自行审批。
 */
public record ResumeFreezeRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 256) String reason) {
}
