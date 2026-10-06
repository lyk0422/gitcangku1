package com.example.starter.evidence;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 剂量账本实体，对应 dose_account 表。每件证物至多一本；
 * 上限首次登记后不可变，累计剂量只增不减，保管权交接不清账。
 *
 * @param id                   主键
 * @param evidenceKey          关联证物业务键，全局唯一
 * @param capLuxMinutes        不可逆光照上限（勒克斯分钟），正值最多3位小数，登记后不可变
 * @param cumulativeLuxMinutes 累计剂量（勒克斯分钟），照度乘精确分钟累加，不舍入
 * @param version              账本版本：登记上限为1，每次剂量归还+1
 * @param registeredBy         登记上限时的当前保管人
 * @param createdAt            账本创建时间（Asia/Shanghai）
 * @param updatedAt            最近一次累计剂量变更时间（Asia/Shanghai）
 */
public record DoseAccount(
        Long id,
        String evidenceKey,
        BigDecimal capLuxMinutes,
        BigDecimal cumulativeLuxMinutes,
        int version,
        String registeredBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /**
     * 累计剂量是否已达到上限（达到即视为 DOSE_BLOCKED，禁止新增借出）。
     */
    public boolean doseBlocked() {
        return cumulativeLuxMinutes.compareTo(capLuxMinutes) >= 0;
    }
}
