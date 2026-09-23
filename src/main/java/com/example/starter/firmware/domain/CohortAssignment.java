package com.example.starter.firmware.domain;

/**
 * 设备在投放活动内的队列分配，同一活动内每台设备至多一条。
 *
 * @param releaseId          所属投放活动ID
 * @param deviceId           设备ID
 * @param cohortId           设备当前所属队列ID
 * @param assignmentVersion  分配版本号，每次迁移成功加一
 * @param currentGeneration  当前指令代次，从1开始，每次迁移加一
 * @param installConfirmed   是否已确认安装成功；为 true 的设备不得迁移
 */
public record CohortAssignment(long releaseId, String deviceId, long cohortId, int assignmentVersion,
                               int currentGeneration, boolean installConfirmed) {
}
