package com.example.starter.disclosure.dto;

import java.time.LocalDateTime;

/**
 * 单件证物按截至时刻重放的结果。
 * 成功时封装链版本、位置、封签快照与计数；失败时携带可区分原因码（无快照）。
 *
 * @param evidenceKey          证物业务键
 * @param ok                   截至时刻重放是否通过
 * @param reasonCode           失败原因码；ok=true 时为 null
 * @param chainVersion         截至时刻链版本：T{交接数}-L{借出数}-I{核验数}
 * @param custodianAtCutoff    截至时刻保管人位置
 * @param sealSnapshot         截至时刻封签状态
 * @param lastInspectionPassed 截至时刻最后一次核验结果：null 截至时刻无核验
 * @param transferCount        截至时刻之前（含）交接记录数
 * @param loanCount            截至时刻之前（含）借出记录数
 * @param inspectionCount      截至时刻之前（含）核验记录数
 * @param cutoffAt             本次重放使用的截至时刻（UTC）
 */
public record CutoffEvidenceSnapshot(
        String evidenceKey,
        boolean ok,
        CutoffReplayCode reasonCode,
        String chainVersion,
        String custodianAtCutoff,
        SealSnapshotView sealSnapshot,
        Boolean lastInspectionPassed,
        int transferCount,
        int loanCount,
        int inspectionCount,
        LocalDateTime cutoffAt) {

    /**
     * 截至时刻封签视图：INTACT 正常 / BROKEN 异常。
     */
    public enum SealSnapshotView {
        INTACT,
        BROKEN
    }
}
