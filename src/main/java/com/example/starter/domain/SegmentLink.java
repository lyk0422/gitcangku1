package com.example.starter.domain;

import java.util.List;

/**
 * 单个航段的通信档案：行驶时长与位置刻度 [0,10000] 上的合成覆盖闭区间。
 *
 * @param durationMs 该段行驶时长，单位毫秒，正整数
 * @param coverage   覆盖闭区间（可重叠，每段最多 10 条；空列表合法，表示全段失联）
 */
public record SegmentLink(long durationMs, List<CoverageInterval> coverage) {
}
