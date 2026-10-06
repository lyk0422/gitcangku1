package com.example.starter.domain;

/**
 * 审查命中的圆形障碍明细（不可变）。
 *
 * @param obstacleId          命中的障碍标识
 * @param obstacleVersion     命中时审查使用的障碍档案版本
 * @param segmentIndex        命中的航线线段序号（从 0 开始，连接第 i 与第 i+1 个航点）
 * @param obstacleRadius      命中时的障碍半径（米）
 * @param routeEnvelopeRadius 命中时的航线水平包络半径（米）
 */
public record ObstacleHit(String obstacleId, int obstacleVersion, int segmentIndex,
                          int obstacleRadius, int routeEnvelopeRadius) {
}
