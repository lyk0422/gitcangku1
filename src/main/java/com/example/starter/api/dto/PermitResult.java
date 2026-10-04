package com.example.starter.api.dto;

/**
 * 运动间隔许可结果。
 *
 * @param permitId        许可唯一标识
 * @param routeId         引用的航线标识
 * @param routeVersion    许可引用的航线版本
 * @param airspaceVersion 许可审查通过时的空域版本
 * @param startEpochSec   有效起点（含），UTC epoch 整秒
 * @param endEpochSec     有效终点（不含），UTC epoch 整秒
 * @param status          ACTIVE / REVOKED
 * @param revokedEpochSec 撤销生效时刻（UTC epoch 秒）；未撤销为 null
 */
public record PermitResult(String permitId, String routeId, int routeVersion, long airspaceVersion,
                           long startEpochSec, long endEpochSec,
                           String status, Long revokedEpochSec) {
}