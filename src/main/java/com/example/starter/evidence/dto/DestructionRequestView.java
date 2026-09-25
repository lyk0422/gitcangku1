package com.example.starter.evidence.dto;

import com.example.starter.evidence.DestructionStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 销毁申请视图。
 *
 * @param requestKey      销毁申请业务键
 * @param evidenceKeys    规范化证物键集合
 * @param requestedBy     申请操作人
 * @param reason          申请原因
 * @param status          申请状态
 * @param blockReason     阻断原因（不可变）；null 表示未被阻断
 * @param blockedHoldKeys 阻断时刻有效冻结键快照；null 表示未被阻断
 * @param createdAt       申请时间（Asia/Shanghai）
 * @param decidedAt       阻断或完成时间（Asia/Shanghai）；null 表示仍待审
 */
public record DestructionRequestView(
        String requestKey,
        List<String> evidenceKeys,
        String requestedBy,
        String reason,
        DestructionStatus status,
        String blockReason,
        List<String> blockedHoldKeys,
        LocalDateTime createdAt,
        LocalDateTime decidedAt) {
}
