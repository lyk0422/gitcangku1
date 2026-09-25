package com.example.starter.repo;

/**
 * 航线垂直分离审查不可变明细记录。
 *
 * @param reviewId    所属审核记录标识
 * @param zoneId      二维路径相交的区域标识
 * @param bandLower   命中高度带下限（含），米；null 表示二维相交但高度不相交
 * @param bandUpper   命中高度带上限（不含），米；null 表示垂直分离
 * @param verticalHit true 表示二维相交且巡航高度落入高度带；false 表示垂直分离
 * @param detailSeq   明细序号（按 zoneId 字典序，从 0 开始）
 */
public record VerticalDetailPo(String reviewId, String zoneId, Integer bandLower,
                               Integer bandUpper, boolean verticalHit, int detailSeq) {
}
