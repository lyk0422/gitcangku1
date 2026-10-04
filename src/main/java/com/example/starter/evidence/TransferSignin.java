package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 检查点签到记录实体，对应 transfer_signin 表。记录只追加、不可变；
 * 仅保存核对通过的正常签到，每个检查点至多一条。
 *
 * @param id            主键
 * @param transferId    所属交接记录 id
 * @param evidenceKey   关联证物业务键
 * @param checkpointSeq 签到的检查点序号
 * @param sealNo        签到时提交并核对一致的封条编号
 * @param arrivedAtUtc  到达时刻（UTC），不得早于上一签到点到达时刻
 * @param createdAt     记录创建时间（Asia/Shanghai）
 */
public record TransferSignin(
        Long id,
        Long transferId,
        String evidenceKey,
        int checkpointSeq,
        String sealNo,
        LocalDateTime arrivedAtUtc,
        LocalDateTime createdAt) {
}