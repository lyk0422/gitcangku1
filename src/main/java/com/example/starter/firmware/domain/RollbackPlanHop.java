package com.example.starter.firmware.domain;

/**
 * 回退计划内单设备单跳的冻结路径行。创建计划时由设备成功投放历史反向构造，此后不可修改。
 *
 * @param id               行ID
 * @param planId           所属计划ID
 * @param deviceId         设备ID
 * @param hopIndex         跳号，从0开始
 * @param expectedVersion  该跳开始时设备应处的版本，冻结
 * @param toVersion        该跳目标版本，冻结
 * @param sourceReleaseId  该跳反向对应的正向投放发布单ID，冻结
 * @param sourceTaskId     该跳反向对应的正向投放任务ID，冻结
 */
public record RollbackPlanHop(long id, long planId, String deviceId, int hopIndex,
                              String expectedVersion, String toVersion,
                              long sourceReleaseId, long sourceTaskId) {
}
