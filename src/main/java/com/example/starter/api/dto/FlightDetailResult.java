package com.example.starter.api.dto;

import java.util.List;

/**
 * 航班详情查询结果（含跑道风险固化快照）。
 *
 * @param flight 航班当前状态
 * @param risks  跑道风险快照列表（无风险为空列表）
 */
public record FlightDetailResult(FlightResult flight, List<FlightRiskDto> risks) {
}
