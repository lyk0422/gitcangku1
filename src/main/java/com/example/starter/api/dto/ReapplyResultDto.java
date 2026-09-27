package com.example.starter.api.dto;

/**
 * 让渡航线显式重新申请结果。
 *
 * @param oldOccupancyId 原被让渡占用标识（保持 YIELDED 不变）
 * @param newOccupancyId 新建占用标识
 * @param routeId        航线标识
 * @param corridorId     原让渡走廊标识
 * @param reviewId       重新审查的不可变审查标识
 * @param result         处置结果：APPROVED
 */
public record ReapplyResultDto(String oldOccupancyId, String newOccupancyId, String routeId,
                               String corridorId, String reviewId, String result) {
}
