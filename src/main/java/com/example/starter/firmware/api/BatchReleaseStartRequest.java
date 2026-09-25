package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量启动发布请求：先按最终冻结范围整体预校验，任一冲突或例外不全则 422、全部不写入。
 * emergencyGrant 可空；命中冻结窗口时必须提供且两名确认人均已登记、彼此不同。
 */
public record BatchReleaseStartRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotEmpty @Valid List<Item> items,
        EmergencyGrant emergencyGrant) {

    /**
     * 批量启动中的单个发布单规格（不含 requestId，批量整体共用一个 requestId）。
     */
    public record Item(
            @NotBlank @Size(max = 64) String model,
            @NotBlank @Size(max = 64) String fromVersion,
            @NotBlank @Size(max = 64) String toVersion,
            @Min(0) @Max(100) int ratio,
            @Min(2) @Max(100) Integer sampleFloor,
            @Min(1) @Max(100) Integer failureThresholdPercent) {

        public int effectiveSampleFloor() {
            return sampleFloor == null ? 2 : sampleFloor;
        }

        public int effectiveFailureThresholdPercent() {
            return failureThresholdPercent == null ? 100 : failureThresholdPercent;
        }
    }
}
