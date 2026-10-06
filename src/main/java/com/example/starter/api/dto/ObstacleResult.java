package com.example.starter.api.dto;

/**
 * 圆形障碍登记/修订/撤销结果。
 *
 * @param obstacleId      障碍标识
 * @param status          操作后状态：ACTIVE / REVOKED
 * @param version         操作后的障碍档案版本
 * @param airspaceVersion 操作后生效的全局空域版本
 */
public record ObstacleResult(String obstacleId, String status, int version, long airspaceVersion) {
}
