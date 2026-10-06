package com.example.starter.api.dto;

import java.util.List;

/**
 * 审查保存的某航段覆盖合并并集（响应）。
 *
 * @param segmentIndex 航段序号，从 0 开始
 * @param intervals    合并后的覆盖闭区间，按起始刻度升序、互不重叠；空列表表示全段无覆盖
 */
public record SegmentUnionDto(
        Integer segmentIndex,
        List<CoverageIntervalDto> intervals) {
}
