package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 标准器失效单。创建时保存闭包快照与期望领域版本；激活需两名不同质量人员确认，
 * 并在同一事务内重算闭包比对，任一血缘、窗口、标准器版本或结果状态变化则整单 409。
 *
 * @param id              失效单 ID（自增）
 * @param requestId       请求幂等键：同参重放返回首次闭包快照，异参 409，失败不占键
 * @param invalidationKey 失效单业务键，全局唯一
 * @param rootVersionId   失效根标准器版本 ID
 * @param invalidFrom     失效起始时刻（UTC，含该时刻）
 * @param expectedVersion 创建时客户端期望的领域版本号
 * @param reason          失效原因
 * @param createdBy       创建人（质量负责人）
 * @param status          状态：PENDING 待激活 / ACTIVATED 已激活
 * @param impactVersion   激活生成的唯一影响版本号；未激活为 null
 * @param createdAt       创建时间（UTC）
 * @param activatedAt     激活时间（UTC）；未激活为 null
 */
public record InvalidationOrder(
        long id,
        String requestId,
        String invalidationKey,
        long rootVersionId,
        Instant invalidFrom,
        long expectedVersion,
        String reason,
        String createdBy,
        InvalidationStatus status,
        String impactVersion,
        Instant createdAt,
        Instant activatedAt) {
}
