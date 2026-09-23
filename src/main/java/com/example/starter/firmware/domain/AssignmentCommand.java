package com.example.starter.firmware.domain;

/**
 * 按代次下发的设备指令。同设备同活动同代次至多一条。
 *
 * @param id           指令ID
 * @param releaseId    所属投放活动ID
 * @param deviceId     设备ID
 * @param cohortId     指令下发时所属队列ID
 * @param generation   指令代次
 * @param status       指令状态
 * @param firstResult  首次回执结果（SUCCESS/FAILED），未回执为 null
 * @param migrationId  生成或废弃该指令的迁移单ID，非迁移流程为 null
 */
public record AssignmentCommand(long id, long releaseId, String deviceId, long cohortId, int generation,
                                CommandStatus status, ReceiptResult firstResult, Long migrationId) {
}
