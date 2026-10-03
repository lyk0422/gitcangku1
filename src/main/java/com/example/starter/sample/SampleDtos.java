package com.example.starter.sample;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;

/**
 * 样本相关传输对象。对应 sample 表：样本编号全项目唯一，采集时固化层位、坐标格与 UTC 时刻。
 */
public final class SampleDtos {

    private SampleDtos() {
    }

    /** 采集请求：requestId 为幂等键；层位由遗址编号+层位号定位；gridRef 为坐标格。 */
    public record CollectRequest(@NotBlank String requestId, @NotBlank String sampleNo, @NotBlank String siteCode,
            @NotBlank String stratumNo, @NotBlank String gridRef) {
    }

    /** 采集响应：collectedAt 为采集时刻（UTC），状态初始为 COLLECTED。 */
    public record SampleResponse(String sampleNo, String siteCode, String stratumNo, String gridRef,
            Instant collectedAt, String status) {
    }
}