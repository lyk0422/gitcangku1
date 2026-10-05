package com.example.starter.maintenance.api.dto;

/**
 * 部件视图（登记/写操作响应）。
 *
 * @param componentId      部件合成编号
 * @param lifetimeMinutes  寿命上限（分钟）
 * @param usedMinutes      已累计寿命（分钟）
 * @param version          操作后的部件版本号
 */
public record ComponentResponse(
        String componentId,
        long lifetimeMinutes,
        long usedMinutes,
        long version) {
}
