package com.example.starter.evidence;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 剂量归还记录实体，对应 dose_return 表。只追加；
 * 冻结当次归还剂量与归还后累计剂量，历史不随后续操作变化。
 *
 * @param id                   主键
 * @param evidenceKey          关联证物业务键
 * @param loanKey              本次归还对应的借出业务键
 * @param doseLuxMinutes       当次归还剂量（勒克斯分钟），各段剂量之和，不舍入
 * @param cumulativeLuxMinutes 归还后累计剂量（勒克斯分钟），历史冻结
 * @param createdAt            记录创建时间（Asia/Shanghai）
 */
public record DoseReturnRecord(
        Long id,
        String evidenceKey,
        String loanKey,
        BigDecimal doseLuxMinutes,
        BigDecimal cumulativeLuxMinutes,
        LocalDateTime createdAt) {
}
