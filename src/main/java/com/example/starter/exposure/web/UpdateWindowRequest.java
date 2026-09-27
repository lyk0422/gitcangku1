package com.example.starter.exposure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 调整公告归因窗口请求。调整只对调整之后新创建的预占生效，历史归因不重算。
 *
 * @param requestId                 写操作全局唯一幂等键
 * @param operatorId                操作者编号，不可空；纳入幂等指纹
 * @param attributionWindowMinutes  新的 UTC 归因窗口分钟数（要求值），1～1440；与当前值相同则 409
 */
public record UpdateWindowRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operatorId,
        @NotNull @Min(1) @Max(1440) Integer attributionWindowMinutes
) {
}
