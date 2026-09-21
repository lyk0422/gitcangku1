package com.example.starter.water.dto;

/**
 * 配水申请视图。
 */
public record AllocationView(
        long id,
        String allocationKey,
        long windowId,
        String userId,
        String volume,
        String applicant,
        String status,
        String createdAt,
        String updatedAt) {
}
