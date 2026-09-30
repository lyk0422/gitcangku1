package com.example.starter.blind.dto;

/**
 * 中心分配视图：只含无含义盲码与区组号，严禁包含处理代码 treatment。
 *
 * @param allocationId  中心分配记录编号
 * @param siteId        中心编号
 * @param participantId 合成参与者编号
 * @param blindCode     随机无含义盲码，与区组/处理组无对应规律
 * @param blockNo       分配到的区组号（不含区组内席位序号）
 * @param allocatedAt   分配时间，Unix 毫秒，UTC
 */
public record SiteAllocationView(
        long allocationId,
        String siteId,
        String participantId,
        String blindCode,
        int blockNo,
        long allocatedAt
) {
}
