package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 批次逐件保管链实体，对应 package_return_chain 表。
 * 每批归还对每件证物追加一条不可变记录，与批次双人确认、证物状态变更同事务写入。
 *
 * @param id          主键
 * @param packageId   所属组合包主键
 * @param batchId     所属归还批次主键
 * @param evidenceKey 本件证物业务键
 * @param sealVersion 归还核验通过的借出冻结封条版本
 * @param receiverId  本件接收人
 * @param reviewerId  本件复核人
 * @param eventAt     逐件保管链事件时间（UTC）
 */
public record PackageReturnChain(
        Long id,
        Long packageId,
        Long batchId,
        String evidenceKey,
        Long sealVersion,
        String receiverId,
        String reviewerId,
        LocalDateTime eventAt) {
}
