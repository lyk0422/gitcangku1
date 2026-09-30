package com.example.starter.archival.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 单条处理轮次视图：展示某页在某批次的一次处理及该批次当前状态。
 *
 * @param batchNo    批次号
 * @param batchStatus 该批次当前状态（OPEN/REJECTED/SEALED）
 * @param roundNo    轮次序号
 * @param phBefore   处理前 pH，无量纲；null 表示读数缺失
 * @param phAfter    处理后 pH，无量纲；null 表示读数缺失
 * @param reagentLot 药剂批号（药剂来源）
 * @param createdAt  读数登记时间（Asia/Shanghai）
 */
public record RoundView(String batchNo, String batchStatus, int roundNo,
                        BigDecimal phBefore, BigDecimal phAfter,
                        String reagentLot, LocalDateTime createdAt) {
}
