package com.example.starter.firmware.api;

import java.util.List;

/**
 * 迁移激活成功结果（幂等重放的首次快照）。
 */
public record MigrationActivateResponse(long migrationId, String migrationKey, long releaseId,
                                        int deviceCount, String committedAt,
                                        List<ItemResult> items,
                                        List<MigrationPreviewResponse.CohortAfterState> cohorts) {

    /**
     * 单设备迁移结果：迁移前后队列与指令代次证据。
     */
    public record ItemResult(String deviceId, long fromCohortId, long toCohortId,
                             int oldGeneration, int newGeneration,
                             Long supersededCommandId, long newCommandId) {
    }
}
