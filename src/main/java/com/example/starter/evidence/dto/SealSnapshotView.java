package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 封签截至时刻快照视图（建包时冻结）。
 *
 * @param sealNo                 封条编号
 * @param intactAtAsOf           截至时刻封签是否正常（截至时刻前无失败核验）
 * @param latestPassed           截至时刻前最近一次核验结果；null 表示截至时刻前从未核验
 * @param latestInspectionAt     截至时刻前最近一次核验时间（Asia/Shanghai）；null 表示无核验
 * @param inspectionsUpToAsOf    截至时刻前核验记录条数（含归还时的封条核验）
 */
public record SealSnapshotView(
        String sealNo,
        boolean intactAtAsOf,
        Boolean latestPassed,
        LocalDateTime latestInspectionAt,
        int inspectionsUpToAsOf) {
}
