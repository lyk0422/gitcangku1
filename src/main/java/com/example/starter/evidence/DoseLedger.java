package com.example.starter.evidence;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 剂量账本实体，对应 dose_ledger 表。每件证物至多一条；
 * 上限首次登记后不可变，累计只增不减，保管权交接不清账，重新借出不清零。
 *
 * @param id                主键
 * @param evidenceKey       关联证物业务键
 * @param limitLuxMinutes   不可变剂量上限，单位勒克斯分钟，正值最多3位小数
 * @param cumMilliLuxNanos  累计剂量，内部精确单位：毫勒克斯×纳秒（照度×1000×持续纳秒）
 * @param version           账本版本：登记时为1，每次剂量归还递增
 * @param createdAt         上限登记时间（Asia/Shanghai）
 * @param updatedAt         最近一次累计变更时间（Asia/Shanghai）
 */
public record DoseLedger(
        Long id,
        String evidenceKey,
        BigDecimal limitLuxMinutes,
        BigDecimal cumMilliLuxNanos,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
