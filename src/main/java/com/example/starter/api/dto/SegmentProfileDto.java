package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 单航段通信档案：正整数行驶毫秒 + 该段位置刻度 0 至 10000 上的覆盖闭区间。
 *
 * @param durationMs 该段行驶时长，正整数，单位毫秒
 * @param coverage   覆盖闭区间（最多 10 条，可重叠）；空列表合法，表示全段失联
 */
public record SegmentProfileDto(
        @NotNull @Positive Integer durationMs,
        @NotNull @Valid @Size(max = 10) List<CoverageIntervalDto> coverage) {
}
