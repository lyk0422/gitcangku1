package com.example.starter.blind.dto;

import java.util.List;

/**
 * 中心剩余额度视图：分配方只能看到各区组剩余额度，看不到配置版本与冻结总量。
 *
 * @param experimentId 实验编号
 * @param siteId       中心编号
 * @param blocks       各区组剩余额度，顺序按区组编号排序
 */
public record RemainingQuotaView(
        String experimentId,
        String siteId,
        List<BlockRemainingView> blocks
) {
}
