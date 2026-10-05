package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 批量禁运设置中的单个簇条目。
 *
 * @param observationId   观测记录唯一标识
 * @param embargoUntilUtc 禁运截止时刻（UTC，ISO-8601），左闭右开区间右端点；不得早于簇创建时刻
 * @param scope           披露范围标签集合；归一化（去空白、去重、排序）后为空返回 422
 */
public record EmbargoItemRequest(
        @NotBlank @Size(max = 64) String observationId,
        @NotNull Instant embargoUntilUtc,
        @NotNull List<@NotBlank @Size(max = 64) String> scope) {
}
