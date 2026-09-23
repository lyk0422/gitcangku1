package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 组合包归还批次实体，对应 package_return_batch 表。批次只追加；
 * 双人确认（接收人、复核人不同且均有案件权限）；任一件校验失败则整批回滚。
 *
 * @param id            主键
 * @param packageId     组合包主键
 * @param receiverId    接收人
 * @param reviewerId    复核人（与接收人不同）
 * @param batchSeq      包内批次稳定排序序号（按提交顺序）
 * @param returnedAt    本批归还时刻（UTC）
 * @param closedPackage 本批是否触发组合包自动关闭
 * @param createdAt     批次创建时间（Asia/Shanghai）
 */
public record PackageReturnBatch(
        Long id,
        Long packageId,
        String receiverId,
        String reviewerId,
        int batchSeq,
        LocalDateTime returnedAt,
        boolean closedPackage,
        LocalDateTime createdAt) {
}
