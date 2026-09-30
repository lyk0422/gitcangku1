package com.example.starter.archival.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一条处理轮次（读数）记录。只追加，不修改；后读数不合格时追加新一轮复处理，原值保留。
 *
 * @param id         主键
 * @param batchId    批次ID
 * @param pageId     档案页ID
 * @param roundNo    该页在该批次内的轮次序号，从 1 递增
 * @param phBefore   处理前 pH，无量纲；允许区间 6.50-8.50；null 表示读数缺失
 * @param phAfter    处理后 pH，无量纲；允许区间 6.50-8.50 且不得低于处理前；null 表示读数缺失
 * @param reagentLot 药剂批号（药剂来源）
 * @param createdAt  读数登记时间（Asia/Shanghai）
 */
public record TreatmentRound(Long id, Long batchId, Long pageId, int roundNo,
                             BigDecimal phBefore, BigDecimal phAfter,
                             String reagentLot, LocalDateTime createdAt) {
}
