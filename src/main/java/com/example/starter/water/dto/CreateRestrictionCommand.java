package com.example.starter.water.dto;

/**
 * 创建限供命令。
 *
 * @param commandKey  幂等命令键
 * @param limitVolume 限供水量，十进制字符串，单位立方米，最多 3 位小数，大于 0 且不超过计划水量
 */
public record CreateRestrictionCommand(String commandKey, String limitVolume) {
}
