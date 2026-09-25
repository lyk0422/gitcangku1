package com.example.starter.api.dto;

/**
 * 航班跑道风险条目（固化窗口快照）。
 *
 * @param closureId      触发风险的关闭窗口标识
 * @param runwayId       关闭窗口所属跑道标识（快照）
 * @param runwayVersion  窗口生效跑道版本快照
 * @param startUtc       关闭开始时刻快照，epoch 毫秒（UTC），左闭
 * @param endUtc         关闭结束时刻快照，epoch 毫秒（UTC），右开
 * @param allowEmergency 关闭窗口紧急例外标志快照
 * @param operator       登记操作者快照
 */
public record FlightRiskDto(String closureId, String runwayId, int runwayVersion,
                            long startUtc, long endUtc, boolean allowEmergency,
                            String operator) {
}
