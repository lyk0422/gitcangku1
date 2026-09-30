package com.example.starter.evidence.dto;

import com.example.starter.evidence.InspectionStatus;
import com.example.starter.evidence.SealResult;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 容器巡检详情视图：展示巡检人、封条版本与结论、巡检单状态、当时箱内清单（每件隔离状态）
 * 以及带时刻的复核结论。
 *
 * @param requestId        巡检请求幂等键
 * @param containerKey     被巡检容器业务键
 * @param sealVersion      两名见证人共同见证的封条版本快照
 * @param sealResult       封条结论
 * @param status           巡检单状态
 * @param witnesses        两名见证人（按提交顺序）；仅一人确认时只有一条
 * @param items            双人确认完成时冻结的当时箱内清单；仅一人确认时为空
 * @param review           复核结论；null 表示尚未复核
 * @param createdAt        第一见证人建单时间（Asia/Shanghai）
 * @param completedAt      双人确认完成时间（Asia/Shanghai）；null 表示仍仅一人确认
 */
public record ContainerInspectionView(
        String requestId,
        String containerKey,
        Integer sealVersion,
        SealResult sealResult,
        InspectionStatus status,
        List<InspectionWitnessView> witnesses,
        List<InspectionItemView> items,
        InspectionReviewView review,
        LocalDateTime createdAt,
        LocalDateTime completedAt) {
}
