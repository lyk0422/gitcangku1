package com.example.starter.api.dto;

/**
 * 转配校验违规明细。
 *
 * @param code    违规码，如 VERSION_CONFLICT / ROUTE_NOT_ACTIVE / OCCUPANCY_NOT_FOUND /
 *                PATH_NOT_CONTIGUOUS / NO_FLY_CONFLICT / CAPACITY_NOT_CONFIGURED /
 *                CAPACITY_EXCEEDED
 * @param message 违规描述
 * @param routeId 相关航线标识；桶级违规可能为 null
 */
public record TransferViolationDto(String code, String message, String routeId) {
}
