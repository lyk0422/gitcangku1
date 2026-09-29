package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 携带 requestId 与可选操作者的写请求（取消发布单、设备拉取）。
 * operator 缺省为 system，参与幂等指纹；旧客户端不传仍可调用。
 */
public record RequestIdBody(@NotBlank @Size(max = 64) String requestId,
                            @Size(max = 64) String operator) {

    public RequestIdBody(String requestId) {
        this(requestId, null);
    }

    public String effectiveOperator() {
        return operator == null || operator.isBlank() ? CreateReleaseRequest.DEFAULT_OPERATOR : operator.trim();
    }
}
