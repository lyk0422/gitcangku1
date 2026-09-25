package com.example.starter.api.dto;

import java.util.List;

/**
 * 航班批量审查结果。任一拒绝则 approved=false 且整批无任何航班状态变更。
 *
 * @param reviewId  批量审查记录唯一标识（不可变）
 * @param approved  整批是否批准
 * @param items     逐航线明细（按 flightId 升序）
 */
public record FlightBatchReviewResult(String reviewId, boolean approved,
                                      List<FlightReviewItemDto> items) {
}
