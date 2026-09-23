package com.example.starter.firmware.domain;

/**
 * 设备在活动内的队列分配，同设备同活动至多一条。
 *
 * @param campaignId           所属投放活动ID
 * @param deviceId             设备ID
 * @param cohortId             当前所属队列ID
 * @param assignmentVersion    分配版本号，从1开始，每次迁移成功加一；迁移单按此做乐观校验
 * @param assignmentGeneration 指令代次，从1开始，每次迁移成功加一；回执按代次隔离结算
 * @param installStatus        安装状态：NONE 未确认，SUCCESS 已确认安装成功（不可再迁移）
 * @param settledGeneration    已结算的最高回执代次，0表示尚未结算；同代次回执只结算一次
 * @param settledResult        已结算代次的回执结果，未结算为 null
 */
public record DeviceAssignment(long campaignId, String deviceId, long cohortId,
                               int assignmentVersion, int assignmentGeneration,
                               InstallStatus installStatus, int settledGeneration,
                               ReceiptResult settledResult) {
}
