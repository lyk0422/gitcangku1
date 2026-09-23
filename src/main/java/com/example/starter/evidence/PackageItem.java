package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 组合包证物明细实体，对应 package_item 表。记录只追加；
 * 借出时冻结封条版本，归还时逐件核验 sealVersion；归还后写回批次与归还时刻，历史不可覆盖。
 *
 * @param id                主键
 * @param packageId         所属组合包主键
 * @param evidenceKey       关联证物业务键
 * @param frozenSealVersion 借出时冻结的封条版本（evidence.version）
 * @param returnBatchId     已归还批次主键；null 表示尚未归还
 * @param returnedAt        实际归还时刻（UTC）；null 表示尚未归还
 * @param createdAt         明细创建时间（Asia/Shanghai）
 */
public record PackageItem(
        Long id,
        Long packageId,
        String evidenceKey,
        Long frozenSealVersion,
        Long returnBatchId,
        LocalDateTime returnedAt,
        LocalDateTime createdAt) {
}
