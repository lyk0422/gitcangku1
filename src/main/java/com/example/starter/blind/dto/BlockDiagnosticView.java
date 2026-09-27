package com.example.starter.blind.dto;

import java.util.List;

/**
 * 区组诊断视图：版本链名额守恒核对与 UTC 左闭右开时间窗内事件。
 * 读取不改变状态；数量字段给实际值、要求值或差额。
 *
 * @param experimentId    实验编号
 * @param blockNo         区组号
 * @param versions        各版本（含未封存的初始版本）名额实际值
 * @param totalCapacity   全部版本容量合计，单位：个序号（要求值）
 * @param totalAllocated  实际已分配序号数（含已退组，序号不释放）
 * @param totalRemaining  实际可分配名额合计（CLOSED 版本余量计 0）
 * @param totalAbandoned  关闭余量时放弃的名额合计
 * @param difference      差额：容量合计 - 已分配 - 可分配 - 已放弃；0 表示守恒
 * @param quotaConsistent 新旧版本容量合计与未分配名额是否守恒
 * @param events          时间窗 [from, to) 内的封存/扩容/关闭/分配事件，按时刻升序
 * @param from            时间窗起点，Unix 毫秒，UTC（含）；null 表示不限制
 * @param to              时间窗终点，Unix 毫秒，UTC（不含）；null 表示不限制
 */
public record BlockDiagnosticView(
        String experimentId,
        int blockNo,
        List<VersionQuota> versions,
        int totalCapacity,
        int totalAllocated,
        int totalRemaining,
        int totalAbandoned,
        int difference,
        boolean quotaConsistent,
        List<DiagnosticEvent> events,
        Long from,
        Long to
) {
    /**
     * 单个版本的名额实际值。
     *
     * @param versionNo 版本号
     * @param sealed    是否已封存；false 表示封存前的初始版本
     * @param sealId    封存编号；未封存为 null
     * @param status    封存状态；未封存为 null
     * @param capacity  版本容量（要求值）
     * @param allocated 已分配序号数（实际值，含封存前历史分配，按席位版本归属）
     * @param remaining 可分配名额（实际值；CLOSED 恒为 0）
     * @param abandoned 关闭余量放弃的名额（实际值；SEALED 恒为 0）
     */
    public record VersionQuota(
            int versionNo,
            boolean sealed,
            String sealId,
            String status,
            int capacity,
            int allocated,
            int remaining,
            int abandoned
    ) {
    }

    /**
     * 时间窗内诊断事件；不含处理代码与区内序号。
     *
     * @param type      SEAL / EXPAND / CLOSE / ALLOCATE
     * @param at        事件时刻，Unix 毫秒，UTC
     * @param sealId    关联封存编号；ALLOCATE 在未封存版本上时为 null
     * @param versionNo 关联随机表版本号
     * @param detail    事件补充说明（如参与者编号），不含盲底
     */
    public record DiagnosticEvent(
            String type,
            long at,
            String sealId,
            int versionNo,
            String detail
    ) {
    }
}
