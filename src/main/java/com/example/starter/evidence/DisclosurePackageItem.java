package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 披露包逐件固化条目，对应 disclosure_package_item 表。写入后不可变、不可更新：
 * 链版本（截至时刻前的链记录数）、位置（截至时刻保管人）、封签快照与生成摘要在建包时冻结，
 * 之后迁移、复核或新增链记录均不得改写。
 *
 * @param id                 主键
 * @param packageId          所属披露包版本主键
 * @param caseKey            所属案件键
 * @param packageVersion     所属包版本号
 * @param position           规范排序后的件次（从 0 开始）
 * @param evidenceKey        证物业务键
 * @param chainVersion       截至时刻链版本：截至时刻前交接+借出+核验记录总条数
 * @param custodianAtAsOf    截至时刻保管人（位置快照）
 * @param sealSnapshotJson   截至时刻封签快照 JSON：封条编号、是否正常、最近核验结果与时间
 * @param itemSummaryJson    逐件生成摘要 JSON：重放链、位置、封签、待核验与借还状态
 * @param createdAt          条目固化时间（Asia/Shanghai）
 */
public record DisclosurePackageItem(
        Long id,
        long packageId,
        String caseKey,
        int packageVersion,
        int position,
        String evidenceKey,
        int chainVersion,
        String custodianAtAsOf,
        String sealSnapshotJson,
        String itemSummaryJson,
        LocalDateTime createdAt) {
}
