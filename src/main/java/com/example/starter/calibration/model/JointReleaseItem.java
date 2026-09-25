package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 联合放行批次测量明细（不可变快照）。每条测量一行，固化放行时使用的证书与计算值。
 *
 * @param id             明细自增主键
 * @param jointBatchId   关联 {@link JointReleaseBatch#id()}
 * @param jointBatchKey  联合批次业务键
 * @param measurementId  测量记录 ID
 * @param measurementKey 测量业务键快照
 * @param instrumentId   仪器 ID 快照（允许跨仪器混合）
 * @param certificateId  本次放行使用的校准证书 ID 快照
 * @param computedValue  放行时固化的未舍入计算值 a×读数+b 快照
 * @param releasedBy     放行人（X-Actor-Id）
 * @param releasedAt     放行时刻（UTC），整批一致
 */
public record JointReleaseItem(
        long id,
        long jointBatchId,
        String jointBatchKey,
        long measurementId,
        String measurementKey,
        String instrumentId,
        long certificateId,
        BigDecimal computedValue,
        String releasedBy,
        Instant releasedAt) {
}
