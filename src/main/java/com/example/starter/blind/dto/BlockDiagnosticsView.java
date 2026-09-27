package com.example.starter.blind.dto;

/**
 * 区组诊断视图：容量与名额的实际值，供调用方核对守恒；只读，不改变状态。
 *
 * @param experimentId         实验编号
 * @param blockNo              区组号
 * @param sealed               该区组是否已封存任一随机表版本
 * @param latestVersion        最新版本号；未封存为 null
 * @param capacity             当前累计容量（最大席位序号）
 * @param allocatedCount       已分配席位数（含已退组，席位不释放）
 * @param remainingAllocatable 当前可分配的剩余名额（已关闭余量不计入）
 */
public record BlockDiagnosticsView(
        String experimentId,
        int blockNo,
        boolean sealed,
        Integer latestVersion,
        int capacity,
        int allocatedCount,
        int remainingAllocatable
) {
}
