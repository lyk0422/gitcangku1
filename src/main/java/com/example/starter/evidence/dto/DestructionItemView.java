package com.example.starter.evidence.dto;

import com.example.starter.evidence.EvidenceStatus;

import java.time.LocalDateTime;

/**
 * 销毁令单件证物视图。
 *
 * @param evidenceKey    证物业务键
 * @param includedStatus 入列时证物状态快照
 * @param currentStatus  当前证物状态（执行重查后为 DESTROYED）
 * @param forcedBroken  入列时是否封条异常且经显式强制放行
 * @param createdAt     入列时间（Asia/Shanghai）
 */
public record DestructionItemView(
        String evidenceKey,
        EvidenceStatus includedStatus,
        EvidenceStatus currentStatus,
        boolean forcedBroken,
        LocalDateTime createdAt) {
}
