package com.example.starter.evidence.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 一次剂量归还的冻结视图：归还时刻、当次剂量、归还后累计与全部剂量段。
 *
 * @param loanKey           关联借出业务键
 * @param returnedAt        实际归还时刻（UTC）
 * @param doseThisReturn    当次归还剂量（勒克斯分钟），精确值不舍入
 * @param cumulativeAfter   归还后累计剂量（勒克斯分钟），精确值不舍入
 * @param cumulativeDisplay 归还后累计显示值，HALF_UP 保留4位小数，仅展示不参与判定
 * @param segments          本次归还冻结的剂量段，按段序号顺序
 */
public record DoseReturnView(
        String loanKey,
        LocalDateTime returnedAt,
        BigDecimal doseThisReturn,
        BigDecimal cumulativeAfter,
        String cumulativeDisplay,
        List<DoseSegmentView> segments) {
}
