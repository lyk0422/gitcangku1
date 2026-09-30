package com.example.starter.blind.dto;

/**
 * 中心区组分配视图：仅含中心、区组编号与区内顺序号；严禁包含处理代码或实验席位号。
 *
 * @param experimentId 实验编号
 * @param siteId       中心编号
 * @param blockCode    区组编号，不表示处理组
 * @param participantId 合成参与者编号
 * @param serialNo     中心内区组分配顺序号，从 1 开始，仅为次序
 * @param allocatedAt  分配时间，Unix 毫秒，UTC
 */
public record SiteAllocationView(
        String experimentId,
        String siteId,
        String blockCode,
        String participantId,
        int serialNo,
        long allocatedAt
) {
}
