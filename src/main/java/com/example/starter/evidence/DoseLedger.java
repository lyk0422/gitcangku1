package com.example.starter.evidence;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 光照剂量账本实体，对应 dose_ledger 表。每件证物至多一本，随首次登记创建；
 * 上限登记后不可变，累计剂量精确累加不舍入，保管权交接不清账。
 *
 * @param id               主键
 * @param evidenceKey      关联证物业务键，一证物一本账
 * @param limitLuxMin      不可逆光照上限（勒克斯分钟），正值最多3位小数
 * @param cumulativeLuxMin 累计光照剂量（勒克斯分钟），精确累加
 * @param doseBlocked      是否达到上限：true 表示 DOSE_BLOCKED，新增借出 422，交接不受影响
 * @param registeredBy     首次登记上限的保管人
 * @param version          账本版本：登记为 1，每次剂量归还 +1；写操作须携带相等的期望版本
 * @param createdAt        账本登记时间（Asia/Shanghai）
 * @param updatedAt        最近一次剂量变动时间（Asia/Shanghai）
 */
public record DoseLedger(
        Long id,
        String evidenceKey,
        BigDecimal limitLuxMin,
        BigDecimal cumulativeLuxMin,
        boolean doseBlocked,
        String registeredBy,
        int version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
