package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 请求中的闭区间序号段：start、end 均为 1..1000000 内整数且 start &lt;= end；
 * 重叠与相邻合并在服务端规范化时处理。
 */
public record IntervalSpec(
        @NotNull(message = "区间起点不能为空") Integer start,
        @NotNull(message = "区间终点不能为空") Integer end
) {
}
