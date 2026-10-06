package com.example.starter.evidence.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 一次剂量归还的冻结视图。
 *
 * @param loanKey              对应借出业务键
 * @param returnedAt           实际归还时刻（UTC），即最后一段区间结束时刻
 * @param doseLuxMinutes       当次归还剂量展示值（勒克斯分钟，HALF_UP 四位）
 * @param cumulativeLuxMinutes 本次归还后的累计剂量展示值（勒克斯分钟，HALF_UP 四位）
 * @param segments             本次归还上报的光照区间（按时间顺序，冻结不可变）
 */
public record DoseReturnView(
        String loanKey,
        LocalDateTime returnedAt,
        BigDecimal doseLuxMinutes,
        BigDecimal cumulativeLuxMinutes,
        List<DoseSegmentView> segments) {
}
