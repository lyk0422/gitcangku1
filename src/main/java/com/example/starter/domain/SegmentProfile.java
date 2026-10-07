package com.example.starter.domain;

import java.util.List;

/**
 * 单个航段的通信档案：正整数行驶毫秒，以及该段位置刻度 0 至 10000 上登记的
 * 覆盖闭区间（最多 10 条，可重叠）。
 *
 * @param durationMs 该段行驶时长，正整数，单位毫秒
 * @param coverage   覆盖闭区间列表；空列表合法，表示全段失联
 */
public record SegmentProfile(int durationMs, List<CoverageInterval> coverage) {
}
