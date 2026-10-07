package com.example.starter.batch.dto;

/**
 * 响应中的闭区间序号段：start/end 为区间端点（含端点），length 为区间长度（件数）。
 */
public record IntervalView(int start, int end, int length) {
}
