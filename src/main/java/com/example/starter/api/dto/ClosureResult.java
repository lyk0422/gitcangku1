package com.example.starter.api.dto;

import java.util.List;

/**
 * 关闭窗口登记结果。
 *
 * @param closureId     关闭窗口唯一标识
 * @param runwayId      跑道标识
 * @param runwayVersion 本次变更生效后的跑道版本
 * @param riskRouteIds  因本次关闭转为 RUNWAY_RISK 的航线（字典序），无则为空列表
 */
public record ClosureResult(String closureId, String runwayId, int runwayVersion,
                            List<String> riskRouteIds) {
}
