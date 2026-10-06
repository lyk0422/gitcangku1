package com.example.starter.domain;

/**
 * 一次水平包络命中记录：某条航线线段的包络与某个圆形障碍相交（含接触）。
 *
 * @param obstacleId      命中的圆形障碍标识
 * @param obstacleVersion 审核时命中的障碍版本（障碍修订/撤销会生成新版本）
 * @param segmentIndex    命中的航线线段序号（从 0 开始，连接航点 i 与 i+1）
 * @param routeRadius     审核时航线登记的水平包络半径（米）
 * @param obstacleRadius  审核时障碍登记的半径（米）
 */
public record EnvelopeHit(String obstacleId, int obstacleVersion, int segmentIndex,
                          int routeRadius, int obstacleRadius) {
}
