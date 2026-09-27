package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 移交令版本诊断项。生效区间左闭右开 [validFrom, validTo)。
 *
 * @param orderNo        移交令编号
 * @param version        移交令版本
 * @param status         版本状态；版本不存在时为 null
 * @param validFrom      UTC 生效起始时刻（含）
 * @param validTo        UTC 生效结束时刻（不含）；null 表示长期有效
 * @param effective      当前 UTC 时刻是否生效且案件匹配
 * @param reason         不生效原因；null 表示生效
 * @param orderFromCase  令版本载明的来源案件；版本不存在时为 null
 * @param orderToCase    令版本载明的目标案件；版本不存在时为 null
 * @param caseMatches    令版本载明双案是否与请求一致
 */
public record OrderStatusView(
        String orderNo,
        int version,
        String status,
        LocalDateTime validFrom,
        LocalDateTime validTo,
        boolean effective,
        String reason,
        String orderFromCase,
        String orderToCase,
        Boolean caseMatches) {
}
