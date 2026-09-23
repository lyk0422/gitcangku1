package com.example.starter.firmware.domain;

/**
 * 迁移单设备明细。
 *
 * @param id                        明细ID
 * @param migrationId               所属迁移单ID
 * @param deviceId                  迁移设备ID
 * @param fromCohortId              迁移前队列ID
 * @param toCohortId                目标队列ID
 * @param expectedAssignmentVersion 运营提交时设备的分配版本
 * @param oldGeneration             迁移前指令代次
 * @param newGeneration             迁移后新指令代次
 * @param oldCommandId              被废弃的旧未决指令ID，无未决指令时为 null
 * @param newCommandId              为目标队列生成的新代次指令ID
 */
public record MigrationItem(long id, long migrationId, String deviceId, long fromCohortId, long toCohortId,
                            int expectedAssignmentVersion, int oldGeneration, int newGeneration,
                            Long oldCommandId, long newCommandId) {
}
