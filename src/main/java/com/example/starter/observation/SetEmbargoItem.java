package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 批量禁运设置中的单簇条目。
 *
 * @param observationId   观测记录唯一标识
 * @param embargoUntilUtc 禁运截止时刻（UTC，左闭右开区间右端点：到达该时刻即到期）
 * @param disclosureScope 披露范围；空白在业务层判 422
 */
public record SetEmbargoItem(
        @NotBlank @Size(max = 64) String observationId,
        @NotNull Instant embargoUntilUtc,
        @Size(max = 256) String disclosureScope) {
}