package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;

/**
 * 最小群体汇总响应：只含群体总量，不返回逐主体值或名单。
 *
 * @param purpose     用途，固定为 RESEARCH 研究
 * @param memberCount 参与汇总的主体人数
 * @param totalValue  成员数值记录总和
 */
public record CohortAggregateResponse(Purpose purpose, int memberCount, long totalValue) {
}
