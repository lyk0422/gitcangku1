package com.example.starter.batch.dto;

/**
 * 序号闭区间响应项：规范化（排序、相邻合并）后的 [start, end]。
 */
public record SerialIntervalView(long start, long end) {
}
