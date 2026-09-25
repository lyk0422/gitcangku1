package com.example.starter.api.dto;

/**
 * 违规明细（路径不连续、禁飞冲突、集合遗漏、容量超限、版本失效等）。
 *
 * @param type    违规类型：DISCONTINUOUS_PATH / NO_FLY_CONFLICT / ITEM_NOT_IN_PLAN /
 *                CAPACITY_EXCEEDED / VERSION_CONFLICT / ROUTE_INACTIVE
 * @param routeId 相关航线标识；桶级容量违规可为空
 * @param detail  人类可读的违规说明
 */
public record ViolationDto(
        String type,
        String routeId,
        String detail) {
}
