package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 修改车底最小周转分钟数请求。requestKey 为幂等键；expectedVersion 对车底参数做乐观校验；
 * 修改成功后重新校验该车底全部已发布相邻段，任一违规则整次 422 不生效。
 */
public record UpdateTurnaroundRequest(
        @NotBlank String requestKey,
        @NotNull Integer expectedVersion,
        @NotNull @Min(1) @Max(240) Integer minTurnaroundMinutes) {
}
