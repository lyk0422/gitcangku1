package com.example.starter.api.dto;

/**
 * 许可创建/撤销结果。
 *
 * @param permitId         许可唯一标识
 * @param routeId          引用的航线标识
 * @param routeVersion     许可锁定的航线版本
 * @param airspaceVersion  许可审查时读取的一致空域版本
 * @param operator         申请操作者
 * @param startEpochSecond 生效起始时刻，UTC epoch 整秒（含）
 * @param endEpochSecond   生效结束时刻，UTC epoch 整秒（不含）
 * @param status           操作后状态：ACTIVE / REVOKED
 */
public record PermitResult(String permitId, String routeId, int routeVersion,
                           long airspaceVersion, String operator,
                           long startEpochSecond, long endEpochSecond, String status) {
}