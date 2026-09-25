package com.example.starter.api.dto;

/**
 * 单个高度带的垂直分离判定明细。
 *
 * @param bandId          高度带标识
 * @param lowerAltitude   高度带下限（含，米）
 * @param upperAltitude   高度带上限（不含，米）
 * @param capacity        该高度带同时段容量（1~50）
 * @param altitudeOverlap 航线巡航高度是否进入该左闭右开高度带（lower &lt;= 巡航高度 &lt; upper）
 */
public record VerticalBandDto(
        String bandId,
        int lowerAltitude,
        int upperAltitude,
        int capacity,
        boolean altitudeOverlap) {
}
