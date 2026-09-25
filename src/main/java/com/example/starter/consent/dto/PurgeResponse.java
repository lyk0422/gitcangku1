package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;

/**
 * 清除结果响应。
 *
 * @param subjectKey      主体标识
 * @param purpose         用途
 * @param epoch           被清除的授权代次
 * @param purgedRecords   本次物理删除的记录数
 * @param retainedRecords 因生效冻结仍保留的记录数
 * @param remainingRecords 清除后该 epoch 剩余记录数（保留中）
 */
public record PurgeResponse(
        String subjectKey,
        Purpose purpose,
        int epoch,
        int purgedRecords,
        int retainedRecords,
        int remainingRecords) {
}
