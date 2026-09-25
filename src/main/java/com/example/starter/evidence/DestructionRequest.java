package com.example.starter.evidence;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 销毁申请实体，对应 destruction_request 表。
 * 一次申请覆盖规范化证物集合；阻断原因与冻结快照在首次阻断时写入，之后不可变。
 *
 * @param id              主键
 * @param requestKey      销毁申请业务键（兼幂等命令键），全局唯一
 * @param evidenceKeys    规范化证物键集合（去重、字典序排序）
 * @param requestedBy     申请操作人
 * @param reason          申请原因
 * @param status          申请状态
 * @param blockReason     阻断原因（不可变）；null 表示未被阻断
 * @param blockedHoldKeys 阻断时刻有效冻结键快照；null 表示未被阻断
 * @param createdAt       申请时间（Asia/Shanghai）
 * @param decidedAt       阻断或完成时间（Asia/Shanghai）；null 表示仍待审
 */
public record DestructionRequest(
        Long id,
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
