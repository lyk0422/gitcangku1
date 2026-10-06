package com.example.starter.repo;

/**
 * 圆形障碍档案当前版本记录。
 *
 * @param obstacleId 障碍唯一标识
 * @param version    当前档案版本（从 1 开始，修订/撤销加一）
 * @param centerX    圆心 X 坐标（平面米坐标）
 * @param centerY    圆心 Y 坐标（平面米坐标）
 * @param radius     障碍半径（非负整数米）
 * @param status     ACTIVE / REVOKED
 */
public record ObstaclePo(String obstacleId, int version, int centerX, int centerY,
                         int radius, String status) {
}
