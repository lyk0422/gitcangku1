package com.example.starter.repo;

/**
 * 跑道持久化记录。
 *
 * @param runwayId        跑道唯一标识
 * @param version         当前跑道版本（从 1 开始，每次关闭窗口变更加一）
 * @param capacityPerHour 每小时起降容量（架次）
 */
public record RunwayPo(String runwayId, int version, int capacityPerHour) {
}
