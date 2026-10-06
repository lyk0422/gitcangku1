package com.example.starter.domain;

import java.util.List;

/**
 * 某航段覆盖区间合并后的并集（审查不可变证据的一部分）。
 *
 * @param segmentIndex 航段序号，从 0 开始
 * @param intervals    合并后的覆盖闭区间，按起始刻度升序、互不重叠；空列表表示全段无覆盖
 */
public record SegmentUnion(int segmentIndex, List<CoverageInterval> intervals) {
}
