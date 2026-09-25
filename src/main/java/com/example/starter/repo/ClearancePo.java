package com.example.starter.repo;

import java.util.List;

/**
 * 审查批件记录。
 *
 * @param clearanceId     批件唯一标识
 * @param routeId         航线标识
 * @param routeVersion    批准时航线版本
 * @param airspaceVersion 批准时空域版本
 * @param priority        NORMAL / EMERGENCY
 * @param eventNo         紧急事件编号；NORMAL 为 null
 * @param status          APPROVED / DEPARTED / DISPLACED / SUPERSEDED
 * @param reviewId        关联不可变审核记录
 * @param bucketsCanonical 规范化时空桶文本（字典序去重，分号拼接）
 * @param requestId       写操作请求标识
 * @param createdAt       批准时间（epoch 毫秒，UTC）
 * @param departedAt      起飞登记时间；null 未起飞
 * @param displacedAt     被置换时间；null 未被置换
 */
public record ClearancePo(
        String clearanceId,
        String routeId,
        int routeVersion,
        long airspaceVersion,
        String priority,
        String eventNo,
        String status,
        String reviewId,
        String bucketsCanonical,
        String requestId,
        long createdAt,
        Long departedAt,
        Long displacedAt) {

    /** 解码规范化时空桶文本。 */
    public List<String> bucketTokens() {
        if (bucketsCanonical == null || bucketsCanonical.isEmpty()) {
            return List.of();
        }
        return List.of(bucketsCanonical.split(";"));
    }
}
