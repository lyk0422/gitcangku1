package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 容量交换的区段时段请求项，区间左闭右开，起止均为 UTC 时刻。
 * 占用段身份仅由 sectionId 与 [startUtc, endUtc) 确定，不含列车编号；
 * 目标段继承参与计划当前唯一列车的 trainNo。
 */
public record SwapSegmentRequest(
        @NotBlank String sectionId,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc) {
}
