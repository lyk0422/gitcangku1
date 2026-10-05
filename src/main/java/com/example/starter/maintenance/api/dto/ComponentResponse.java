package com.example.starter.maintenance.api.dto;

/**
 * 部件视图（登记响应与查询共用字段）。
 *
 * @param componentId         部件合成编号
 * @param lifetimeMinutes     寿命上限（分钟）
 * @param accumulatedMinutes  已累计寿命（分钟）
 * @param version             部件版本号
 */
public record ComponentResponse(
        String componentId,
        long lifetimeMinutes,
        long accumulatedMinutes,
        long version) {
}
