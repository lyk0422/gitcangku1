package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 编组登记/变更请求：车厢按编号去重并规范排序后落库，站台代码去重排序。
 * requestKey 为幂等键，指纹含操作者、计划版本、规范化车厢、站台与计划占用时段；
 * expectedVersion 必须与当前版本一致；已取消计划不可改。
 */
public record ConsistUpdateRequest(
        @NotBlank String requestKey,
        @NotBlank String operator,
        @NotNull Integer expectedVersion,
        @NotNull @Min(1) Integer consistLength,
        @NotNull @Size(min = 1, max = 64) List<@NotBlank String> cars,
        @NotNull @Size(min = 1, max = 16) List<@NotBlank String> platformCodes) {
}
