package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 受监管交接检查点实体，对应 transfer_checkpoint 表。
 * 发起交接时一次性写入，名单创建后不可修改。
 *
 * @param id          主键
 * @param transferId  所属交接记录 id
 * @param evidenceKey 关联证物业务键
 * @param seq         检查点序号，从 1 开始连续递增
 * @param deadlineUtc 该点 UTC 截止时刻；到达时刻等于截止仍合法
 * @param createdAt   记录创建时间（Asia/Shanghai）
 */
public record TransferCheckpoint(
        Long id,
        Long transferId,
        String evidenceKey,
        int seq,
        LocalDateTime deadlineUtc,
        LocalDateTime createdAt) {
}