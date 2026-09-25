package com.example.starter.api.dto;

import java.util.List;

/**
 * 区域高度带配置结果。
 *
 * @param zoneId       区域标识
 * @param zoneVersion  修改成功后的区域配置版本（每次修改加一）
 * @param bands        当前完整高度带配置（按下限升序）
 */
public record BandConfigResult(String zoneId, int zoneVersion, List<BandSpecDto> bands) {
}
