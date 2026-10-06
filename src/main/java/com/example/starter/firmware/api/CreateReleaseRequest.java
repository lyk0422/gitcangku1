package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建发布单请求。sampleFloor 与 failureThresholdPercent 为可选，
 * 缺省分别为 2 与 100（即仅当本轮全部失败且样本达标时才自动暂停），旧客户端不传仍可创建。
 * releaseType 缺省 GRAY；TOPOLOGY 类型必须携带 topologyVersion、devices（1~10个）与 edges（无向邻接边）。
 */
public record CreateReleaseRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String fromVersion,
        @NotBlank @Size(max = 64) String toVersion,
        @Min(0) @Max(100) int ratio,
        @Min(2) @Max(100) Integer sampleFloor,
        @Min(1) @Max(100) Integer failureThresholdPercent,
        ReleaseType releaseType,
        @Min(1) Integer topologyVersion,
        @Size(max = 10) List<@NotBlank @Size(max = 64) String> devices,
        List<DeviceEdge> edges) {

    public static final int DEFAULT_SAMPLE_FLOOR = 2;
    public static final int DEFAULT_FAILURE_THRESHOLD_PERCENT = 100;

    /**
     * 兼容旧调用方：不配置监控参数时使用默认值。
     */
    public CreateReleaseRequest(String requestId, String model, String fromVersion, String toVersion, int ratio) {
        this(requestId, model, fromVersion, toVersion, ratio, null, null);
    }

    /**
     * 兼容旧调用方：GRAY 发布单，不携带拓扑字段。
     */
    public CreateReleaseRequest(String requestId, String model, String fromVersion, String toVersion, int ratio,
                                Integer sampleFloor, Integer failureThresholdPercent) {
        this(requestId, model, fromVersion, toVersion, ratio, sampleFloor, failureThresholdPercent,
                null, null, null, null);
    }

    public int effectiveSampleFloor() {
        return sampleFloor == null ? DEFAULT_SAMPLE_FLOOR : sampleFloor;
    }

    public int effectiveFailureThresholdPercent() {
        return failureThresholdPercent == null ? DEFAULT_FAILURE_THRESHOLD_PERCENT : failureThresholdPercent;
    }

    public ReleaseType effectiveReleaseType() {
        return releaseType == null ? ReleaseType.GRAY : releaseType;
    }
}
