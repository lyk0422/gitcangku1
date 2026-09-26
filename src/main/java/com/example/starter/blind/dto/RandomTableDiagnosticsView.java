package com.example.starter.blind.dto;

/**
 * 区组随机表诊断视图：交叉核对版本容量、实际席位行数与分配计数；
 * 全部为实际值，读取不改变任何状态。
 *
 * @param experimentId        实验编号
 * @param blockNo             区组号
 * @param currentVersionId    当前最新版本主键
 * @param versionCount        区组版本总数
 * @param capacity            当前版本累计容量
 * @param seatRows            席位表实际行数（应与 capacity 一致）
 * @param allocatedCount      已分配席位数（含已退组）
 * @param remainingCount      未分配席位数（seatRows - allocatedCount）
 * @param currentVersionSealed 当前版本是否已封存
 * @param sealCount           区组封存记录总数
 * @param capacityConserved   容量守恒：capacity == seatRows 且 remainingCount >= 0
 */
public record RandomTableDiagnosticsView(
        String experimentId,
        int blockNo,
        long currentVersionId,
        int versionCount,
        int capacity,
        long seatRows,
        long allocatedCount,
        long remainingCount,
        boolean currentVersionSealed,
        int sealCount,
        boolean capacityConserved
) {
}
