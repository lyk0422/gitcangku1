package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 站台配置登记 / 修订请求。
 * expectedVersion 为 null 表示登记新站点（初始版本 1）；非 null 表示修订，
 * 必须与当前版本一致，修订成功版本加一。operator 参与幂等指纹。
 *
 * @param requestKey       幂等键
 * @param operator         操作者标识
 * @param minDwellMinutes  最小停站分钟数（非负）
 * @param platformCapacity 站台容量（正整数）
 * @param expectedVersion  期望的当前版本；null 表示新建
 */
public record StationConfigRequest(
        @NotBlank String requestKey,
        @NotBlank String operator,
        @PositiveOrZero @NotNull Integer minDwellMinutes,
        @Positive @NotNull Integer platformCapacity,
        Integer expectedVersion) {
}
