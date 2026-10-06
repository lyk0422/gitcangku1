package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 迟滞校准会话条目。只允许顺序追加，方向与未舍入校正值在追加时固化。
 *
 * @param id             条目 ID（自增）
 * @param sessionId      所属会话 ID
 * @param seq            会话内序号，从 1 开始连续递增
 * @param rawValue       原始值，最多 6 位小数
 * @param direction      方向 UP/DOWN（首条显式，后续推导、相同继承）
 * @param correctedValue 未舍入校正值 斜率×原值+方向偏移
 * @param lowerLimit     合格下限（含端点）
 * @param upperLimit     合格上限（含端点）
 * @param passed         是否合格（基于未舍入值，含端点）
 * @param submittedBy    条目提交人
 * @param measurementId  关联测量记录 ID（会话成员）
 * @param createdAt      追加时间（UTC）
 */
public record SessionEntry(
        long id,
        long sessionId,
        int seq,
        BigDecimal rawValue,
        Direction direction,
        BigDecimal correctedValue,
        BigDecimal lowerLimit,
        BigDecimal upperLimit,
        boolean passed,
        String submittedBy,
        long measurementId,
        Instant createdAt) {

    /**
     * 显示值：未舍入校正值按 HALF_UP 保留 4 位小数；仅用于展示，不参与合格判定。
     */
    public BigDecimal displayValue() {
        return correctedValue.setScale(4, java.math.RoundingMode.HALF_UP);
    }
}
