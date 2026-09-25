package com.example.starter.repo;

/**
 * 跑道记录。
 *
 * @param runwayId       跑道唯一标识
 * @param version        当前跑道版本（初始 0，每次关闭窗口登记成功后加一）
 * @param hourlyCapacity 每 UTC 小时起降容量（起飞与落地各计一次）
 */
public record RunwayPo(String runwayId, int version, int hourlyCapacity) {
}
