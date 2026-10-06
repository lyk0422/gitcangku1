package com.example.starter.evidence.dto;

import com.example.starter.evidence.DoseStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * 剂量账本视图。仅当前保管人可查；累计与当次剂量为展示值
 * （勒克斯分钟，HALF_UP 四位），超限判定使用内部精确值，不以展示值为准。
 *
 * @param evidenceKey          关联证物业务键
 * @param limitLuxMinutes      不可变剂量上限（勒克斯分钟）
 * @param cumulativeLuxMinutes 累计剂量展示值（勒克斯分钟，HALF_UP 四位）
 * @param status               账本状态：OK / DOSE_BLOCKED（累计达到或超过上限）
 * @param version              账本版本：登记为1，每次剂量归还递增
 * @param returns              历史剂量归还记录（按发生顺序，冻结不可变）
 */
public record DoseLedgerView(
        String evidenceKey,
        BigDecimal limitLuxMinutes,
        BigDecimal cumulativeLuxMinutes,
        DoseStatus status,
        long version,
        List<DoseReturnView> returns) {
}
