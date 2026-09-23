package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 队列迁移单个设备项：运营提交设备当前 cohortId、assignmentVersion 与目标 cohortId。
 */
public record MigrationItemInput(
        @NotBlank @Size(max = 64) String deviceId,
        @Min(1) long currentCohortId,
        @Min(1) int assignmentVersion,
        @Min(1) long targetCohortId) {

    /**
     * 迁移预览请求：按完整后态计算队列规模与配额，不写数据。
     */
    public record Preview(
            @NotBlank @Size(max = 64) String migrationKey,
            @Min(1) long releaseId,
            @NotEmpty @Size(min = 2, max = 500) @Valid List<MigrationItemInput> items) {
    }

    /**
     * 迁移激活请求：整单原子提交；requestId 同参（设备项换序视为同参）重放首次快照，异参 409。
     */
    public record Activate(
            @NotBlank @Size(max = 64) String requestId,
            @NotBlank @Size(max = 64) String migrationKey,
            @Min(1) long releaseId,
            @NotEmpty @Size(min = 2, max = 500) @Valid List<MigrationItemInput> items) {
    }
}
