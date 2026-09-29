package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 扩量请求：expectedVersion 必须等于发布单当前版本，成功加一；ratio 只增不减。
 * operator 为可选操作者（缺省 system），参与幂等指纹。
 */
public record ExpandReleaseRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Size(max = 64) String operator,
        @Min(1) int expectedVersion,
        @Min(0) @Max(100) int ratio) {

    public ExpandReleaseRequest(String requestId, int expectedVersion, int ratio) {
        this(requestId, null, expectedVersion, ratio);
    }

    public String effectiveOperator() {
        return operator == null || operator.isBlank() ? CreateReleaseRequest.DEFAULT_OPERATOR : operator.trim();
    }
}
