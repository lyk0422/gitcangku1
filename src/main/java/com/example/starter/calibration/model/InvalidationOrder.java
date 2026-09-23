package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 标准器失效单。创建时固化首次闭包快照与请求参数规范串；
 * 两名不同质量人员确认后激活，激活在单事务内重算闭包，任一漂移整单 409。
 *
 * @param id               失效单 ID（自增）
 * @param invalidationKey  失效单业务键，全局唯一
 * @param requestId        幂等请求键；同参重放返回首次闭包快照，异参 409，失败不占键
 * @param rootStandardId   失效根标准器版本业务键
 * @param invalidFrom      失效起始时刻（UTC），该时刻及以后受影响
 * @param expectedVersion  创建时根标准器版本号；激活时不一致整单 409
 * @param reason           失效原因
 * @param createdBy        创建人（质量负责人）
 * @param status           状态：PENDING_CONFIRMATION 待双人确认 / ACTIVATED 已激活
 * @param impactVersion    激活后生成的单一影响版本号；未激活为 null
 * @param closureSnapshot  首次闭包快照（规范串）
 * @param requestParams    首次请求参数规范串，用于 requestId 同参判断
 * @param createdAt        创建时间（UTC）
 * @param activatedAt      激活时间（UTC）；未激活为 null
 */
public record InvalidationOrder(
        long id,
        String invalidationKey,
        String requestId,
        String rootStandardId,
        Instant invalidFrom,
        int expectedVersion,
        String reason,
        String createdBy,
        InvalidationStatus status,
        String impactVersion,
        String closureSnapshot,
        String requestParams,
        Instant createdAt,
        Instant activatedAt) {
}
