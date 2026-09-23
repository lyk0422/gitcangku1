package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.MigrationItem;

/**
 * 迁移单设备明细视图：迁移前后队列、指令代次与指令变更证据。
 */
public record MigrationItemView(String deviceId, long fromCohortId, long toCohortId,
                                int fromGeneration, int toGeneration,
                                Long supersededCommandId, Long newCommandId) {

    public static MigrationItemView of(MigrationItem item) {
        return new MigrationItemView(item.deviceId(), item.fromCohortId(), item.toCohortId(),
                item.fromGeneration(), item.toGeneration(),
                item.supersededCommandId(), item.newCommandId());
    }
}
