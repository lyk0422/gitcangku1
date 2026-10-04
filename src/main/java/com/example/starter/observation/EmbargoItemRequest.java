package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 批量禁运设置中的单簇请求项。
 *
 * @param observationId   观测记录唯一标识
 * @param embargoUntilUtc 禁运截止时刻（UTC，ISO-8601）；禁运区间左闭右开
 * @param disclosureScope 披露范围标识列表，规范化（去空白、去重、排序）后必须非空
 */
public record EmbargoItemRequest(
        @NotBlank @Size(max = 64) String observationId,
        @NotNull Instant embargoUntilUtc,
        @NotNull List<@NotBlank @Size(max = 64) String> disclosureScope) {
}