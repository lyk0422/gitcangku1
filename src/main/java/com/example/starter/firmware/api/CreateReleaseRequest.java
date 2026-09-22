package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建发布单请求。sampleFloor 与 failureThresholdPercent 可缺省（保守默认，见 ReleaseService），
 * 旧客户端不传这两个字段时行为与历史版本一致。
 */
public record CreateReleaseRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String fromVersion,
        @NotBlank @Size(max = 64) String toVersion,
        @Min(0) @Max(100) int ratio,
        @Min(2) @Max(100) Integer sampleFloor,
        @Min(1) @Max(100) Integer failureThresholdPercent) {

    /**
     * 兼容旧客户端的五参构造：监控参数缺省，由服务端应用保守默认值。
     */
    public CreateReleaseRequest(String requestId, String model, String fromVersion, String toVersion,
                                int ratio) {
        this(requestId, model, fromVersion, toVersion, ratio, null, null);
    }
}
