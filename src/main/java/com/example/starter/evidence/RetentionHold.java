package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 保留冻结实体，对应 retention_hold 表。记录只追加；解除仅写一次解除链字段。
 * 冻结窗口为 UTC 左闭右开 [createdAt, expireAt)：createdAt &lt;= now &lt; expireAt 且未解除时阻断销毁。
 *
 * @param id            主键
 * @param holdKey       冻结业务键，全局唯一
 * @param scope         冻结级别：CASE 案件级 / EVIDENCE 证物级
 * @param caseKey       所属案件键（证物级取证件所属案件）
 * @param evidenceKey   证物业务键；NULL 表示案件级冻结
 * @param createdBy     创建冻结的操作人
 * @param reason        创建原因，非空
 * @param expireAt      UTC 到期时刻；恰好到期后不再阻断新销毁申请
 * @param releasedBy    解除操作人（须为不同于创建人的保管人）；NULL 表示未解除
 * @param releaseReason 解除签明原因；NULL 表示未解除
 * @param releasedAt    UTC 解除时刻；NULL 表示未解除（解除只影响后续销毁申请）
 * @param createdAt     UTC 冻结窗口起点（创建生效时刻）
 */
public record RetentionHold(
        Long id,
        String holdKey,
        HoldScope scope,
        String caseKey,
        String evidenceKey,
        String createdBy,
        String reason,
        LocalDateTime expireAt,
        String releasedBy,
        String releaseReason,
        LocalDateTime releasedAt,
        LocalDateTime createdAt) {
}
