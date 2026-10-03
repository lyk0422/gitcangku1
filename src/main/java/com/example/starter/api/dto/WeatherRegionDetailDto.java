package com.example.starter.api.dto;

import java.util.List;

/**
 * 气象区域明细查询结果（只读）。
 *
 * @param regionId    气象区域标识
 * @param xMin        左边界（含），单位米
 * @param yMin        下边界（含），单位米
 * @param xMax        右边界（含），单位米
 * @param yMax        上边界（含），单位米
 * @param dutyVersion 当前配置生效时的气象值班版本
 * @param windows     当前最低标准窗口（按时段起点升序）
 */
public record WeatherRegionDetailDto(
        String regionId,
        int xMin,
        int yMin,
        int xMax,
        int yMax,
        long dutyVersion,
        List<MinimaWindowDto> windows) {
}