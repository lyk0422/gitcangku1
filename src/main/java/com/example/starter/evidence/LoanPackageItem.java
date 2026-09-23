package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 组合包逐件借出明细实体，对应 package_loan_item 表。
 * 建包时一次性写入；sealVersion 冻结借出提交时的证物版本，归还时必须逐件匹配。
 *
 * @param id              主键
 * @param packageId       组合包主键
 * @param evidenceKey     包内证物业务键
 * @param sealVersion     借出提交时冻结的证物版本（封条版本）
 * @param itemSeq         包内稳定排序序号（按证物锁定顺序）
 * @param status          明细状态
 * @param returnedBatchId 归还所在批次主键；null 表示尚未归还
 * @param returnedAt      实际归还时刻（UTC）；null 表示尚未归还
 * @param createdAt       明细创建时间（Asia/Shanghai）
 */
public record LoanPackageItem(
        Long id,
        Long packageId,
        String evidenceKey,
        long sealVersion,
        int itemSeq,
        PackageItemStatus status,
        Long returnedBatchId,
        LocalDateTime returnedAt,
        LocalDateTime createdAt) {
}
