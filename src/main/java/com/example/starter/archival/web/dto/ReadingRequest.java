package com.example.starter.archival.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

/**
 * 处理读数登记请求（追加一个处理轮次，不覆盖旧轮次）。
 *
 * @param batchNo     批次号
 * @param accessionNo 馆藏号
 * @param phBefore    处理前 pH，无量纲；null 表示读数暂缺，封存时按缺读数拒绝
 * @param phAfter     处理后 pH，无量纲；null 表示读数暂缺；允许区间 6.50-8.50 且不得低于处理前
 * @param reagentLot  药剂批号（药剂来源）
 */
public record ReadingRequest(
        @NotBlank(message = "批次号不能为空") String batchNo,
        @NotBlank(message = "馆藏号不能为空") String accessionNo,
        BigDecimal phBefore,
        BigDecimal phAfter,
        @NotBlank(message = "药剂批号不能为空") String reagentLot) {
}
