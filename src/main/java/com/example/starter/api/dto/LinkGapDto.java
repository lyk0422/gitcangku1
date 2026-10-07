package com.example.starter.api.dto;

/**
 * 一段连续失联（可跨相邻段：前段尾部缺口与后段头部缺口连成同一段）。
 *
 * @param fromSegment 起始航段序号（含，从 0 开始）
 * @param toSegment   结束航段序号（含）；大于 fromSegment 表示跨段缺口
 * @param lossMs      精确失联时长（毫秒，有限小数字符串，不四舍五入）
 */
public record LinkGapDto(int fromSegment, int toSegment, String lossMs) {
}
