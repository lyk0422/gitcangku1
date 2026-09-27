package com.example.starter.disclosure;

import java.time.LocalDateTime;

/**
 * 披露包逐件证物冻结快照，对应 disclosure_package_item 表。
 * 快照在包生成事务内固化，之后证物迁移、复核、借出或新增链记录都不会改写这些字段。
 *
 * @param id                   主键
 * @param packageKey           所属披露包指纹
 * @param itemOrder            规范化排序序号（证物键升序，从 0 开始）
 * @param evidenceKey          证物业务键
 * @param chainVersion         截至时刻链版本：T{交接数}-L{借出数}-I{核验数}
 * @param custodianSnapshot    截至时刻保管人位置快照
 * @param sealSnapshot         截至时刻封签快照
 * @param lastInspectionPassed 截至时刻最后一次封条核验结果：null 无核验 / true 通过 / false 失败
 * @param transferCount        截至时刻之前（含）交接记录数
 * @param loanCount            截至时刻之前（含）借出记录数
 * @param inspectionCount      截至时刻之前（含）封条核验记录数
 * @param itemSummary          单件冻结摘要 JSON
 * @param createdAt            快照固化时间（Asia/Shanghai）
 */
public record DisclosurePackageItem(
        Long id,
        String packageKey,
        int itemOrder,
        String evidenceKey,
        String chainVersion,
        String custodianSnapshot,
        SealSnapshot sealSnapshot,
        Boolean lastInspectionPassed,
        int transferCount,
        int loanCount,
        int inspectionCount,
        String itemSummary,
        LocalDateTime createdAt) {
}
