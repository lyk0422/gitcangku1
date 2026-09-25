package com.example.starter.repo;

/**
 * 航班跑道风险快照（新关闭窗口生效时固化，窗口内容不随后续变更）。
 *
 * @param flightId       风险航班标识
 * @param closureId      触发风险的关闭窗口标识
 * @param runwayId       关闭窗口所属跑道标识（快照）
 * @param startUtc       关闭开始时刻快照，epoch 毫秒（UTC），左闭
 * @param endUtc         关闭结束时刻快照，epoch 毫秒（UTC），右开
 * @param allowEmergency 关闭窗口紧急例外标志快照
 * @param operator       登记操作者快照
 * @param runwayVersion  窗口生效跑道版本快照
 * @param snapshotAt     快照固化时间，epoch 毫秒（UTC）
 */
public record FlightRiskPo(String flightId, String closureId, String runwayId,
                           long startUtc, long endUtc, boolean allowEmergency,
                           String operator, int runwayVersion, long snapshotAt) {
}
