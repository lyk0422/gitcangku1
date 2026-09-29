package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.Purpose;

/**
 * 只读查询视图集合：历史窗口与诊断。所有接口均为纯读取，不改变业务状态。
 */
public final class Queries {

    private Queries() {
    }

    /**
     * 历史区间窗口。
     *
     * @param fromUtc 区间下界（含），ISO-8601；null 表示未设置
     * @param toUtc   区间上界（不含），ISO-8601；null 表示未设置
     * @param count   本次返回条目数
     * @param entries 账本条目，按 (发生时间, 顺序号) 稳定升序
     */
    public record HistoryWindow(String fromUtc, String toUtc, int count, List<LedgerEntryResponse> entries) {
    }

    /**
     * 主体＋用途维度的诊断视图。
     *
     * @param subjectKey      主体标识
     * @param purpose         用途
     * @param epochs          各授权代次诊断，按 epoch 升序
     * @param totalLedgerEntries 该主体＋用途全部账本条目数（跨所有 epoch）
     */
    public record Diagnostics(String subjectKey, Purpose purpose, List<EpochDiagnostics> epochs,
                              long totalLedgerEntries) {
    }

    /**
     * 单个 epoch 的诊断数据。
     *
     * @param epoch          授权代次
     * @param status         授权状态：ACTIVE / REVOKED
     * @param quota          额度视图；未声明时 declared=false 且数值字段为 null
     * @param totalRecords   该代记录总数（含已清除，物理保留）
     * @param clearedRecords 已标记清除的记录数
     * @param ledgerEntries  该代账本条目数
     */
    public record EpochDiagnostics(int epoch, String status, QuotaResponse quota,
                                   long totalRecords, long clearedRecords, long ledgerEntries) {
    }
}
