package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 迟滞校准会话。固定仪器与 HYSTERESIS 证书；version 等于已提交条目数（1～20 条上限由服务层强制）。
 *
 * @param id            会话 ID（自增）
 * @param instrumentId  仪器 ID，与证书仪器一致
 * @param certificateId 固定的 HYSTERESIS 证书 ID
 * @param status        状态：OPEN 追加中 / CLOSED 已关闭 / RELEASED 已放行
 * @param version       当前版本=已提交条目数；追加须期望版本相等，成功后加一
 * @param lastDirection 最近一条方向；无条目为 null，相同读数继承该方向
 * @param lastRaw       最近一条原始值；无条目为 null
 * @param createdAt     创建时间（UTC）
 * @param closedAt      关闭时间（UTC），未关闭为 null
 */
public record CalibrationSession(
        long id,
        String instrumentId,
        long certificateId,
        SessionStatus status,
        int version,
        Direction lastDirection,
        BigDecimal lastRaw,
        Instant createdAt,
        Instant closedAt) {
}
