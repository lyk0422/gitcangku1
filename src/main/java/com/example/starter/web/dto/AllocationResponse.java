package com.example.starter.web.dto;

import com.example.starter.domain.Allocation;
import com.example.starter.domain.Berth;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 靠泊申请详情，包含原决策快照所用潮高版本、最低潮高、可用深度、余量及拒绝原因。
 */
public record AllocationResponse(
        Long id,
        String requestId,
        String vesselName,
        String berthCode,
        BigDecimal draftM,
        BigDecimal safetyMarginM,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime,
        String status,
        Long tideVersionId,
        BigDecimal minTideHeightM,
        BigDecimal staticDepthM,
        BigDecimal availableDepthM,
        BigDecimal requiredDepthM,
        BigDecimal clearanceM,
        Long reviewTideVersionId,
        BigDecimal reviewMinTideHeightM,
        BigDecimal reviewAvailableDepthM,
        BigDecimal reviewClearanceM,
        String rejectReason,
        Integer reviewCount,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime decidedAt) {

    /**
     * 由实体与泊位信息组装响应。
     *
     * @param allocation 申请实体
     * @param berth      对应泊位
     * @return 响应对象
     */
    public static AllocationResponse from(Allocation allocation, Berth berth) {
        return new AllocationResponse(
                allocation.getId(),
                allocation.getRequestId(),
                allocation.getVesselName(),
                berth == null ? null : berth.code(),
                allocation.getDraftM(),
                allocation.getSafetyMarginM(),
                allocation.getStartTime(),
                allocation.getEndTime(),
                allocation.getStatus().name(),
                allocation.getTideVersionId(),
                allocation.getMinTideHeightM(),
                allocation.getStaticDepthM(),
                allocation.getAvailableDepthM(),
                allocation.getRequiredDepthM(),
                allocation.getClearanceM(),
                allocation.getReviewTideVersionId(),
                allocation.getReviewMinTideHeightM(),
                allocation.getReviewAvailableDepthM(),
                allocation.getReviewClearanceM(),
                allocation.getRejectReason(),
                allocation.getReviewCount(),
                allocation.getDecidedAt());
    }
}
