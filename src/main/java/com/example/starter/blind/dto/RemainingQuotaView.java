package com.example.starter.blind.dto;

import java.util.List;

/**
 * 分配方视角视图：只暴露当前已启用快照下各区组的剩余额度，
 * 不返回总名额、已分配量、版本确认留痕，更不包含处理代码或席位序号。
 *
 * @param siteId 中心编号
 * @param blocks 各区组剩余额度
 */
public record RemainingQuotaView(
        String siteId,
        List<BlockRemaining> blocks
) {

    /**
     * 单个区组的剩余可分配量。
     *
     * @param blockNo   区组号，从 1 开始
     * @param remaining 剩余可分配量
     */
    public record BlockRemaining(int blockNo, int remaining) {
    }
}
