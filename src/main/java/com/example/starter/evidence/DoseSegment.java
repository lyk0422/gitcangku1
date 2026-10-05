package com.example.starter.evidence;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 光照剂量段实体，对应 dose_segment 表。归还时按借出区间逐段冻结，只追加、不可变；
 * 区间互不重叠且完整覆盖实际借出至归还，剂量=照度×精确分钟数，不舍入。
 *
 * @param id             主键
 * @param evidenceKey    关联证物业务键
 * @param loanKey        关联借出业务键，归还时冻结
 * @param segmentNo      段序号，按时间顺序从 1 开始
 * @param startAt        段起始时刻（UTC）；首段等于实际借出时刻
 * @param endAt          段结束时刻（UTC）；末段等于实际归还时刻
 * @param illuminanceLux 照度（勒克斯），非负最多3位小数
 * @param doseLuxMin     本段剂量（勒克斯分钟）=照度×精确分钟数，不舍入
 * @param createdAt      记录创建时间（Asia/Shanghai）
 */
public record DoseSegment(
        Long id,
        String evidenceKey,
        String loanKey,
        int segmentNo,
        LocalDateTime startAt,
        LocalDateTime endAt,
        BigDecimal illuminanceLux,
        BigDecimal doseLuxMin,
        LocalDateTime createdAt) {
}
