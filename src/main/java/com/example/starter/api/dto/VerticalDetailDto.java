package com.example.starter.api.dto;

/**
 * 航线垂直分离审查明细项（不可变）。每个二维路径相交的区域一条：
 * 巡航高度落入该区域某高度带时给出命中带并 verticalHit=true；
 * 二维相交但高度不相交（垂直分离）时 bandLower/bandUpper 为 null。
 *
 * @param zoneId      二维路径相交的区域标识
 * @param bandLower   命中高度带下限（含），米；null 表示垂直分离
 * @param bandUpper   命中高度带上限（不含），米；null 表示垂直分离
 * @param verticalHit true 表示二维相交且高度相交；false 表示垂直分离
 */
public record VerticalDetailDto(
        String zoneId,
        Integer bandLower,
        Integer bandUpper,
        boolean verticalHit) {
}
