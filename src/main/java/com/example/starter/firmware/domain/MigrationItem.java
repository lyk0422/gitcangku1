package com.example.starter.firmware.domain;

/**
 * 迁移单设备明细，记录迁移前后队列与指令代次。
 *
 * @param id                   迁移明细ID
 * @param migrationId          所属迁移单ID
 * @param deviceId             设备ID
 * @param fromCohortId         迁移前队列ID
 * @param toCohortId           迁移后队列ID
 * @param fromGeneration       迁移前指令代次
 * @param toGeneration         迁移后指令代次（fromGeneration+1）
 * @param supersededCommandId  被废弃的未决指令ID，无未决指令为 null
 * @param newCommandId         为目标队列新签发的未决指令ID，无未决指令时为 null
 */
public record MigrationItem(long id, long migrationId, String deviceId,
                            long fromCohortId, long toCohortId,
                            int fromGeneration, int toGeneration,
                            Long supersededCommandId, Long newCommandId) {
}
