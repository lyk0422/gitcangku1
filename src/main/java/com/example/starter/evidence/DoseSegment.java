package com.example.starter.evidence;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 剂量段实体，对应 dose_segment 表。只追加、不可变；
 * 一次归还1至20段，区间互不重叠且完整覆盖实际借出至归还。
 *
 * @param id             主键
 * @param evidenceKey    关联证物业务键
 * @param loanKey        本次归还对应的借出业务键
 * @param seq            段序号，从1开始按时间顺序排列
 * @param startAt        段区间起点（UTC，含）
 * @param endAt          段区间终点（UTC，不含），须晚于起点
 * @param lux            照度（勒克斯），非负，最多3位小数
 * @param doseLuxMinutes 本段剂量=照度×精确分钟（勒克斯分钟），不舍入
 * @param createdAt      记录创建时间（Asia/Shanghai）
 */
public record DoseSegment(
        Long id,
        String evidenceKey,
        String loanKey,
        int seq,
        LocalDateTime startAt,
        LocalDateTime endAt,
        BigDecimal lux,
        BigDecimal doseLuxMinutes,
        LocalDateTime createdAt) {
}
