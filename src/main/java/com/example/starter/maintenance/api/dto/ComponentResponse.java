package com.example.starter.maintenance.api.dto;

/**
 * 部件状态视图。
 *
 * @param componentId         部件合成编号
 * @param lifespanMinutes     寿命上限（分钟）
 * @param accumulatedMinutes  已结算累计分钟（各已拆卸区间之和）
 * @param currentEquipmentId  当前在装设备标识；未在装为 null
 * @param exhausted           累计是否已达到寿命上限（达到后禁止再安装，已安装者仍可拆卸）
 * @param version             部件当前版本号
 */
public record ComponentResponse(
        String componentId,
        long lifespanMinutes,
        long accumulatedMinutes,
        String currentEquipmentId,
        boolean exhausted,
        long version) {
}