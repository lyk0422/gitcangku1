package com.example.starter.api.dto;

import java.util.List;

/**
 * 区域高度带配置结果。
 *
 * @param zoneId           禁飞区标识
 * @param zoneVersion      配置成功后的区域配置版本（每次成功配置加一）
 * @param airspaceVersion  配置成功后生效的全局空域版本
 * @param bands            配置后该区域的完整高度带集合
 */
public record BandConfigureResult(
        String zoneId,
        int zoneVersion,
        long airspaceVersion,
        List<BandResultDto> bands) {
}
