package com.example.starter.api.dto;

import java.util.List;

/**
 * 按机场与时段（UTC 左闭右开 [from, to)）查询的占用报告：
 * 当前有效占用（含优先级与起飞状态）与该时段相关的不可变替换链。
 *
 * @param airportId        机场标识
 * @param from            查询时段起点，epoch 毫秒（UTC），左闭
 * @param to              查询时段终点，epoch 毫秒（UTC），右开
 * @param occupancies     当前 ACTIVE 占用列表，按窗口起点、预留标识排序
 * @param replacementChain 被挤出预留窗口与本时段相交的不可变替换事件，按发生顺序排序
 */
public record OccupancyReportDto(
        String airportId,
        long from,
        long to,
        List<OccupancyDto> occupancies,
        List<ReplacementDto> replacementChain) {
}
