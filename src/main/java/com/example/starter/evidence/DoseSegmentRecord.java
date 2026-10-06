package com.example.starter.evidence;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 剂量区间段实体，对应 dose_segment 表。记录只追加；
 * 一次归还1至20段，左闭右开、首尾相接、互不重叠，完整覆盖实际借出至归还时刻。
 *
 * @param id             主键
 * @param evidenceKey    关联证物业务键
 * @param loanKey        对应借出业务键
 * @param seq            段序号，从1开始，按时间顺序
 * @param startAt        段开始时刻（UTC，含）
 * @param endAt          段结束时刻（UTC，不含），须晚于开始时刻
 * @param lux            照度，单位勒克斯，非负最多3位小数
 * @param milliLuxNanos  本段剂量，毫勒克斯×纳秒，冻结不可变
 * @param createdAt      记录创建时间（Asia/Shanghai）
 */
public record DoseSegmentRecord(
        Long id,
        String evidenceKey,
        String loanKey,
        int seq,
        LocalDateTime startAt,
        LocalDateTime endAt,
        BigDecimal lux,
        BigDecimal milliLuxNanos,
        LocalDateTime createdAt) {
}
