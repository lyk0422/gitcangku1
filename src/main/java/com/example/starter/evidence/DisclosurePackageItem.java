package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 披露包逐件冻结快照实体，对应 disclosure_package_item 表。
 * 建包时按截至 UTC 时刻重放后固化，之后迁移、复核或新增链记录均不得改写。
 *
 * @param id                     主键
 * @param packageKey             所属披露包业务键
 * @param evidenceKey            证物业务键
 * @param sortOrder              规范化（字典序）集合序号，从 0 开始
 * @param chainVersion           截至时刻链版本（入库+截至时刻前已决交接/借出/核验事件总数）
 * @param chainHash              截至时刻保管链指纹 SHA-256
 * @param custodianId            截至时刻位置（保管人）快照
 * @param evidenceStatus         截至时刻重放证物状态
 * @param sealNo                 封条编号快照
 * @param sealOk                 截至时刻封签是否正常
 * @param latestInspectionPassed 截至时刻最近一次核验结果：null 从未核验
 * @param itemDigest             单件生成摘要 SHA-256
 * @param snapshotJson           逐件链/位置/封签完整快照 JSON
 * @param createdAt              快照固化时间（Asia/Shanghai）
 */
public record DisclosurePackageItem(
        Long id,
        String packageKey,
        String evidenceKey,
        int sortOrder,
        int chainVersion,
        String chainHash,
        String custodianId,
        EvidenceStatus evidenceStatus,
        String sealNo,
        boolean sealOk,
        Boolean latestInspectionPassed,
        String itemDigest,
        String snapshotJson,
        LocalDateTime createdAt) {
}
