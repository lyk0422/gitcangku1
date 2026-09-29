package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 人工恢复请求：expectedVersion 必须等于发布单当前版本，成功加一并开启新监控轮次。
 * operator 为可选操作者（缺省 system），参与幂等指纹。
 */
public record ResumeReleaseRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Size(max = 64) String operator,
        @Min(1) int expectedVersion,
        @NotBlank @Size(max = 256) String reason) {

    public ResumeReleaseRequest(String requestId, int expectedVersion, String reason) {
        this(requestId, null, expectedVersion, reason);
    }

    public String effectiveOperator() {
        return operator == null || operator.isBlank() ? CreateReleaseRequest.DEFAULT_OPERATOR : operator.trim();
    }
}
