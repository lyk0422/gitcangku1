package com.example.starter.batch.dto;

import java.util.List;

/**
 * 供应商历史评分轨迹：按批次创建时刻升序，每个终态批次落定后的滑动评分快照。
 */
public record SupplierScoreHistoryResponse(String supplierId,
                                           List<ScoreTrajectoryPoint> trajectory) {
}
