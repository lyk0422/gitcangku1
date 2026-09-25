package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 车底最小周转分钟数登记/修改请求。
 *
 * <p>车底不存在时 {@code expectedVersion=0} 表示首次登记（创建后版本为 1）；
 * 已存在车底须携带当前版本，版本冲突返回 409。分钟数取值 1～240。
 * 修改成功后重新校验该车底全部已发布相邻段，任一处不满足返回 422，参数不生效。
 */
public record TurnaroundUpdateRequest(
        @NotBlank String requestKey,
        @NotNull Integer expectedVersion,
        @NotNull @Min(1) @Max(240) Integer minTurnaroundMinutes) {
}
