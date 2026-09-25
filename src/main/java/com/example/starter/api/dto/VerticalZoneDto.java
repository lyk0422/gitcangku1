package com.example.starter.api.dto;

import java.util.List;

/**
 * 单个二维相交区域的垂直分离审查明细。
 * 仅当二维路径与区域相交时才出现；逐高度带标注巡航高度是否相交。
 *
 * @param zoneId          区域标识
 * @param twoDIntersects  二维路径是否与该区域相交（明细中恒为 true）
 * @param blocked         该区域是否计入命中：仅未登记高度带的纯禁飞区在二维相交时拦截；
 *                        登记了高度带的区域为容量管理空域，不拦截，按占用计费
 * @param bands           该区域登记的全部高度带及逐带垂直相交判定
 */
public record VerticalZoneDto(
        String zoneId,
        boolean twoDIntersects,
        boolean blocked,
        List<VerticalBandDto> bands) {
}
