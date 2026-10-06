package com.example.starter.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 覆盖闭区间（请求）：航段位置刻度 [0,10000] 上的闭区间。
 * 反向（from &gt; to）或越界由服务层判定为 400。
 *
 * @param from 起始刻度（含），[0,10000]
 * @param to   结束刻度（含），[0,10000] 且不小于 from
 */
public record CoverageIntervalDto(
        @NotNull Integer from,
        @NotNull Integer to) {
}
