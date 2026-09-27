package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 披露包证物明细实体，对应 disclosure_package_item 表。
 * 每行是某证物在包截至时刻的冻结快照；证物侧后续的迁移、复核或新增链记录不得改写本表。
 *
 * @param id           主键
 * @param packageKey   所属披露包业务键
 * @param evidenceKey  证物业务键
 * @param seqNo        包内规范排序位次（证物键字典序，从 1 开始）
 * @param custodianId  截至时刻重放得到的保管人（位置快照）
 * @param sealIntact   截至时刻封签是否正常（异常时整包不生成，已生成包内恒为 true）
 * @param chainVersion 截至时刻链版本（已生效链事件总数）
 * @param chainDigest  截至时刻重放链事件的 SHA-256 摘要
 * @param snapshot     截至时刻冻结快照 JSON
 * @param createdAt    快照生成时间（UTC）
 */
public record DisclosurePackageItem(
        Long id,
        String packageKey,
        String evidenceKey,
        int seqNo,
        String custodianId,
        boolean sealIntact,
        int chainVersion,
        String chainDigest,
        String snapshot,
        LocalDateTime createdAt) {
}
