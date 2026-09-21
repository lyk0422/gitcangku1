package com.example.starter.evidence.web.dto;

import com.example.starter.evidence.domain.SealCheckResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 封条核验请求。result 为 PASS 或 FAIL；FAIL 使证物进入 SEAL_BROKEN 终态。
 */
public record SealCheckRequest(
        @NotBlank(message = "不能为空") @Size(max = 64) String commandKey,
        @NotNull(message = "不能为空") SealCheckResult result,
        @Size(max = 512) String detail
) {
}
