package com.example.starter.api.dto;

import java.util.List;

/**
 * 容量转配激活结果。
 *
 * @param transferKey 转配单标识
 * @param routes      参与航线版本变化（按 routeId 升序）
 * @param createdAt   激活时间，epoch 毫秒（UTC）
 */
public record TransferResultDto(String transferKey, List<TransferRouteResultDto> routes,
                                long createdAt) {
}
