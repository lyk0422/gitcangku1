package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 配置（或修订）气象区域请求：非退化轴对齐闭矩形 + 按 UTC 时段生效的最低标准。
 * 同一 regionId 重复配置视为修订，整体替换几何与窗口，气象值班版本加一。
 *
 * @param regionId  气象区域唯一标识
 * @param xMin      左边界（含），单位米
 * @param yMin      下边界（含），单位米
 * @param xMax      右边界（含），单位米
 * @param yMax      上边界（含），单位米
 * @param windows   最低标准窗口（至少一个；同一区域内时段不得重叠）
 * @param operator  操作者标识，参与幂等指纹计算
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record WeatherRegionConfigRequest(
        @NotBlank @Size(max = 64) String regionId,
        @NotNull @Min(-100000) @Max(100000) Integer xMin,
        @NotNull @Min(-100000) @Max(100000) Integer yMin,
        @NotNull @Min(-100000) @Max(100000) Integer xMax,
        @NotNull @Min(-100000) @Max(100000) Integer yMax,
        @NotNull @Valid @Size(min = 1, max = 50) List<MinimaWindowDto> windows,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String requestId) {
}