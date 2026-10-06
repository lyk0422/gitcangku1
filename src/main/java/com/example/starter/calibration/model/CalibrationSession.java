package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 迟滞校准会话。固定仪器与 HYSTERESIS 证书，顺序追加 1～20 条原始值；
 * version 等于已追加条目数，关闭后冻结全部方向与精确结果。
 *
 * @param id            会话 ID（自增）
 * @param sessionKey    业务会话键，全局唯一
 * @param instrumentId  仪器 ID（创建时固定）
 * @param certificateId 固定的 HYSTERESIS 证书 ID
 * @param lowerLimit    合格下限（含端点），作用于每条校正值
 * @param upperLimit    合格上限（含端点），作用于每条校正值
 * @param status        状态：OPEN / CLOSED
 * @param version       当前版本，等于已追加条目数（0～20）
 * @param createdBy     会话创建人
 * @param createdAt     创建时间（UTC）
 * @param closedAt      关闭时间（UTC），未关闭为 null
 */
public record CalibrationSession(
        long id,
        String sessionKey,
        String instrumentId,
        long certificateId,
        BigDecimal lowerLimit,
        BigDecimal upperLimit,
        SessionStatus status,
        int version,
        String createdBy,
        Instant createdAt,
        Instant closedAt) {
}
