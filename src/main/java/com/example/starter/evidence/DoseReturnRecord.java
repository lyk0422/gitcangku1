package com.example.starter.evidence;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 剂量归还记录实体，对应 dose_return 表。记录只追加；
 * 每次归还冻结当次剂量与归还后累计剂量，历史不可覆盖。
 *
 * @param id                   主键
 * @param evidenceKey          关联证物业务键
 * @param loanKey              本次归还对应的借出业务键
 * @param returnMilliLuxNanos  当次归还剂量，毫勒克斯×纳秒，冻结不可变
 * @param cumMilliLuxNanos     本次归还后的累计剂量，毫勒克斯×纳秒，冻结不可变
 * @param returnedAt           实际归还时刻（UTC），即最后一段区间结束时刻
 * @param createdAt            记录创建时间（Asia/Shanghai）
 */
public record DoseReturnRecord(
        Long id,
        String evidenceKey,
        String loanKey,
        BigDecimal returnMilliLuxNanos,
        BigDecimal cumMilliLuxNanos,
        LocalDateTime returnedAt,
        LocalDateTime createdAt) {
}
