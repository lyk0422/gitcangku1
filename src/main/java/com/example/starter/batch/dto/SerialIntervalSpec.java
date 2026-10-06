package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 序号闭区间请求项：[start, end]，范围 1～1000000；越界、重叠、相邻合并等规则在服务层判定。
 */
public record SerialIntervalSpec(
        @NotNull(message = "区间起点 start 不能为空") Long start,
        @NotNull(message = "区间终点 end 不能为空") Long end
) {
}
