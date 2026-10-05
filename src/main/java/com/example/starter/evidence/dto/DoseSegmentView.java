package com.example.starter.evidence.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 冻结的剂量段视图（剂量明细的一部分）。
 *
 * @param segmentNo      段序号，按时间顺序从 1 开始
 * @param startAt        段起始时刻（UTC）
 * @param endAt          段结束时刻（UTC）
 * @param illuminanceLux 照度（勒克斯）
 * @param doseLuxMin     本段剂量（勒克斯分钟），精确值不舍入
 * @param doseDisplay    本段剂量显示值，HALF_UP 保留4位小数，仅展示不参与判定
 */
public record DoseSegmentView(
        int segmentNo,
        LocalDateTime startAt,
        LocalDateTime endAt,
        BigDecimal illuminanceLux,
        BigDecimal doseLuxMin,
        String doseDisplay) {
}
