package com.example.starter.api.dto;

import java.util.List;

/**
 * 航线垂直分离审查明细。基于审核不可变快照（航点、巡航高度、时刻）与当前有效区域及高度带计算；
 * 仅列出二维路径相交的区域。
 *
 * @param reviewId        审核记录标识
 * @param routeId         航线标识
 * @param routeVersion    审核时的航线版本
 * @param airspaceVersion 审核时的空域版本
 * @param cruiseAltitudeM 审核时巡航高度快照，单位米
 * @param startAt         巡航起始时刻，epoch 毫秒（UTC，含）
 * @param endAt           巡航结束时刻，epoch 毫秒（UTC，不含）
 * @param zones           二维相交区域的垂直分离明细
 */
public record VerticalSeparationResult(
        String reviewId,
        String routeId,
        int routeVersion,
        long airspaceVersion,
        int cruiseAltitudeM,
        long startAt,
        long endAt,
        List<ZoneVerticalDetail> zones) {

    /**
     * 单个二维相交区域的垂直分离明细。
     *
     * @param zoneId      区域标识
     * @param blocksRoute 是否存在高度带与巡航高度相交（相交即拦截）
     * @param bands       该区域全部高度带的垂直分离项
     */
    public record ZoneVerticalDetail(
            String zoneId,
            boolean blocksRoute,
            List<BandVerticalDetail> bands) {
    }

    /**
     * 单个高度带的垂直分离项。
     *
     * @param bandId               高度带标识
     * @param lowerM               高度下限（含），米
     * @param upperM               高度上限（不含），米
     * @param capacity             同时容量
     * @param altitudeIntersects   巡航高度是否落入该带
     * @param verticalSeparationM  垂直间隔（米）：落入为 0；低于下限为下限减高度；高于或等于上限为高度减上限
     */
    public record BandVerticalDetail(
            String bandId,
            int lowerM,
            int upperM,
            int capacity,
            boolean altitudeIntersects,
            int verticalSeparationM) {
    }
}
