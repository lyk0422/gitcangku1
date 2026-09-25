package com.example.starter.batch.dto;

import java.util.List;

/**
 * 供应商当前滑动评分明细。score 为查询时按最近 20 个终态批次实时计算的派生值，
 * 裁剪在 [-100, 100]；window 为参与计算的批次清单（按批次标识排序）及各自贡献。
 */
public record SupplierScoreResponse(String supplierId, int score, int windowSize,
                                    List<ScoreContribution> window) {
}
