package com.example.starter.firmware.domain;

/**
 * 回退计划设备成员：路径长度快照。计划非终态期间设备被占用，
 * 不得同时进入其他未终结回退计划，也不得被新投放创建任务。
 *
 * @param planId   所属回退计划ID
 * @param deviceId 设备ID
 * @param hopCount 该设备反向路径跳数，取值1~5，不同设备可不同
 */
public record RollbackPlanDevice(long planId, String deviceId, int hopCount) {
}
