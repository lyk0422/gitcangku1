package com.example.starter.station.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 站台配置创建/修订请求。requestKey 在 STATION_CONFIG 操作类型内幂等；
 * 首次创建后同站再次提交即修订（版本加一），历史版本不可变。
 *
 * @param requestKey       幂等键
 * @param minDwellMinutes  要求的最小停站分钟数，0～1440
 * @param platformCapacity 站台容量（可并行停靠列车数上限），1～99
 * @param operatorId       操作者标识，参与幂等指纹；空白时服务端归一为 anonymous
 */
public record StationConfigRequest(
        @NotBlank String requestKey,
        @NotNull @Min(0) @Max(1440) Integer minDwellMinutes,
        @NotNull @Min(1) @Max(99) Integer platformCapacity,
        String operatorId) {
}
