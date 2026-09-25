package com.example.starter.api.dto;

/**
 * 高度带配置结果中的单个带视图。
 *
 * @param bandId         高度带标识
 * @param lowerAltitude  高度带下限（含，米）
 * @param upperAltitude  高度带上限（不含，米）
 * @param capacity       当前容量（1~50）
 * @param created        true 表示本次配置新增的带；false 表示已存在带（可能仅上调容量）
 */
public record BandResultDto(
        String bandId,
        int lowerAltitude,
        int upperAltitude,
        int capacity,
        boolean created) {
}
