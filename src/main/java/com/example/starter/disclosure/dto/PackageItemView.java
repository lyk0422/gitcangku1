package com.example.starter.disclosure.dto;

import com.example.starter.disclosure.SealSnapshot;

/**
 * 披露包逐件冻结快照视图。
 *
 * @param itemOrder            规范化排序序号（从 0 开始）
 * @param evidenceKey          证物业务键
 * @param chainVersion         截至时刻链版本：T{交接数}-L{借出数}-I{核验数}
 * @param custodianSnapshot    截至时刻保管人位置
 * @param sealSnapshot         截至时刻封签快照
 * @param lastInspectionPassed 截至时刻最后一次核验结果：null 无核验
 * @param transferCount        截至时刻之前（含）交接数
 * @param loanCount            截至时刻之前（含）借出数
 * @param inspectionCount      截至时刻之前（含）核验数
 * @param itemSummary          单件冻结摘要（结构化 JSON）
 */
public record PackageItemView(
        int itemOrder,
        String evidenceKey,
        String chainVersion,
        String custodianSnapshot,
        SealSnapshot sealSnapshot,
        Boolean lastInspectionPassed,
        int transferCount,
        int loanCount,
        int inspectionCount,
        Object itemSummary) {
}
