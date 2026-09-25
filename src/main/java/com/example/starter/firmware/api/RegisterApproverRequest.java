package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登记冻结紧急例外确认人请求。
 */
public record RegisterApproverRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String approverId) {
}
