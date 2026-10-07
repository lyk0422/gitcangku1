package com.example.starter.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 覆盖闭区间（位置刻度 0 至 10000，含端点）。
 * 反向（start &gt; end）或越界由服务层统一校验，返回 400 COVERAGE_INTERVAL_INVALID。
 *
 * @param start 起始刻度（含），范围 [0,10000]
 * @param end   结束刻度（含），范围 [0,10000]
 */
public record CoverageIntervalDto(
        @NotNull Integer start,
        @NotNull Integer end) {
}
