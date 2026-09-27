package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建发布单请求。
 * fragments 必须按序号 0..n-1 规范登记（允许乱序提交，服务端按序号排序校验），
 * packageDigest 为完整包摘要，必须等于分片摘要按序号排序、换行拼接后的 SHA-256。
 * 重复序号、序号缺口或聚合摘要不匹配返回 422。
 */
public record CreateReleaseRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String fromVersion,
        @NotBlank @Size(max = 64) String toVersion,
        @Min(0) @Max(100) int ratio,
        @Min(2) @Max(100) Integer sampleFloor,
        @Min(1) @Max(100) Integer failureThresholdPercent,
        @NotEmpty @Valid @Size(max = 1024) List<FragmentInput> fragments,
        @NotBlank
        @Pattern(regexp = "[0-9a-f]{64}", message = "必须为64位小写十六进制SHA-256摘要")
        String packageDigest) {

    public static final int DEFAULT_SAMPLE_FLOOR = 2;
    public static final int DEFAULT_FAILURE_THRESHOLD_PERCENT = 100;

    /**
     * 兼容旧调用方：不配置监控参数时使用默认值。
     */
    public CreateReleaseRequest(String requestId, String model, String fromVersion, String toVersion,
                                int ratio, List<FragmentInput> fragments, String packageDigest) {
        this(requestId, model, fromVersion, toVersion, ratio, null, null, fragments, packageDigest);
    }

    public int effectiveSampleFloor() {
        return sampleFloor == null ? DEFAULT_SAMPLE_FLOOR : sampleFloor;
    }

    public int effectiveFailureThresholdPercent() {
        return failureThresholdPercent == null ? DEFAULT_FAILURE_THRESHOLD_PERCENT : failureThresholdPercent;
    }
}
