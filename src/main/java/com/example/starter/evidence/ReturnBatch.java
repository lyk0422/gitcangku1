package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 分批归还批次实体，对应 return_batch 表。每个批次归还包内一个非空子集；
 * 接收人与复核人必须不同且都具备案件权限。记录只追加、不可变。
 *
 * @param id          主键
 * @param packageId   所属组合包主键
 * @param receiverId  接收人（具备案件权限，与复核人不同）
 * @param reviewerId  复核人（具备案件权限，与接收人不同）
 * @param returnedAt  本批归还时刻（UTC）
 * @param note        批次备注；null 表示未填写
 * @param createdAt   批次落库时间（Asia/Shanghai）
 */
public record ReturnBatch(
        Long id,
        Long packageId,
        String receiverId,
        String reviewerId,
        LocalDateTime returnedAt,
        String note,
        LocalDateTime createdAt) {
}
