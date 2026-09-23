package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 批次逐件不可变归还快照实体，对应 package_return_item 表。
 * 记录每件归还时提交并核验一致的冻结封条版本，供关闭快照与链路证据核验。
 *
 * @param id          主键
 * @param batchId     归还批次主键
 * @param packageId   组合包主键（冗余便于按包查询）
 * @param evidenceKey 本批归还证物业务键
 * @param sealVersion 归还时核验一致的冻结封条版本（借出时版本）
 * @param itemSeq     证物在包内的稳定排序序号
 * @param createdAt   快照创建时间（Asia/Shanghai）
 */
public record PackageReturnItem(
        Long id,
        Long batchId,
        Long packageId,
        String evidenceKey,
        long sealVersion,
        int itemSeq,
        LocalDateTime createdAt) {
}
