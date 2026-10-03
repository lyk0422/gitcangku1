package com.example.starter.deacid;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 档案页处理轮次查询视图。
 *
 * @param roundNo        处理轮次，同一页在同一批次内从 1 递增
 * @param batchNo        批次编号
 * @param batchStatus    批次状态：IN_PROGRESS=进行中，SEALED=已封存
 * @param phBefore       处理前 pH，无量纲
 * @param phAfter        处理后 pH，无量纲，封存要求 6.5 至 8.5 且不低于处理前
 * @param reagentBatchNo 药剂批号，标识药剂来源
 * @param createdAt      记录时间，Asia/Shanghai
 */
public record TreatmentRoundView(int roundNo, String batchNo, String batchStatus,
                                 BigDecimal phBefore, BigDecimal phAfter,
                                 String reagentBatchNo, LocalDateTime createdAt) {
}