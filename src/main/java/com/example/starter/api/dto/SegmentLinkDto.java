package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 单个航段的通信档案（请求）：正整数行驶毫秒与位置刻度 [0,10000] 上的覆盖闭区间。
 *
 * @param durationMs 该段行驶时长，单位毫秒，必须为正整数
 * @param coverage   覆盖闭区间，最多 10 条、可重叠；空列表（或缺省）合法，表示全段失联
 */
public record SegmentLinkDto(
        @NotNull Long durationMs,
        @Valid @Size(max = 10) List<CoverageIntervalDto> coverage) {
}
