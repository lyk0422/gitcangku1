package com.example.starter.water.dto;

/**
 * 限供记录视图。cancelledAt 在未取消时为 null。
 */
public record RestrictionView(
        long id,
        long windowId,
        String limitVolume,
        String status,
        String createdAt,
        String cancelledAt) {
}
