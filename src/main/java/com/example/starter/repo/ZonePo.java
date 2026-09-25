package com.example.starter.repo;

/**
 * 禁飞区持久化记录。
 *
 * @param zoneId         禁飞区唯一标识
 * @param xMin           左边界（含），米
 * @param yMin           下边界（含），米
 * @param xMax           右边界（含），米
 * @param yMax           上边界（含），米
 * @param status         ACTIVE / REVOKED
 * @param createdVersion 创建后生效的全局空域版本
 * @param revokedVersion 撤销后生效的全局空域版本；null 表示仍有效
 * @param zoneVersion    区域配置版本（初始 1），每次高度带配置成功后加一
 */
public record ZonePo(String zoneId, int xMin, int yMin, int xMax, int yMax,
                     String status, long createdVersion, Long revokedVersion,
                     int zoneVersion) {
}
