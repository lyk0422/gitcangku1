package com.example.starter.plan;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 整体替换草稿计划占用清单请求。
 *
 * @param requestKey 幂等键
 * @param expectedVersion 期望的当前版本，不匹配返回 409
 * @param occupancies 新的区段占用清单，1～30 条，整体替换
 */
public record UpdatePlanRequest(
        @NotBlank String requestKey,
        @NotNull Long expectedVersion,
        @NotNull @Size(min = 1, max = 30) List<@Valid OccupancyInput> occupancies) {
}
