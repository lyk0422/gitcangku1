package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 批量禁运设置中的单个簇条目。
 *
 * @param observationId   观测记录唯一标识
 * @param embargoUntilUtc 禁运截止时刻（UTC，ISO-8601）；生效区间左闭右开，到达该时刻即到期
 * @param disclosureScope 披露范围；空值或全空白在业务层按 422 拒绝
 */
public record EmbargoItem(
        @NotBlank @Size(max = 64) String observationId,
        @NotNull Instant embargoUntilUtc,
        @Size(max = 512) String disclosureScope) {
}