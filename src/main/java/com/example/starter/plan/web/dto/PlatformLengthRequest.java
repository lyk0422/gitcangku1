package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 调整站台有效长度请求。requestKey 为幂等键，指纹含操作者、站台代码与目标长度。
 * 下调时同一事务回查未来已发布计划并标记 PLATFORM_RISK 风险快照。
 */
public record PlatformLengthRequest(
        @NotBlank String requestKey,
        @NotBlank String operator,
        @NotNull @Min(1) Integer effectiveLength) {
}
