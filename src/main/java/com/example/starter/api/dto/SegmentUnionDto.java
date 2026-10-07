package com.example.starter.api.dto;

import java.util.List;

/**
 * 审查时某航段的合并覆盖并集（不可变存档内容）。
 *
 * @param segment 航段序号（从 0 开始）
 * @param merged  合并后的覆盖闭区间，按起始刻度升序、互不重叠
 */
public record SegmentUnionDto(int segment, List<CoverageIntervalDto> merged) {
}
