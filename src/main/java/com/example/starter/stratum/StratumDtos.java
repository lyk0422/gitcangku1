package com.example.starter.stratum;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;

/**
 * 层位相关传输对象。对应 stratum 表：遗址编号+层位号唯一，createdAt 为 UTC 时刻。
 */
public final class StratumDtos {

    private StratumDtos() {
    }

    /** 层位登记请求：遗址编号与层位号联合唯一，description 可为空。 */
    public record RegisterRequest(@NotBlank String siteCode, @NotBlank String stratumNo, String description) {
    }

    /** 层位响应：createdAt 为登记时刻（UTC）。 */
    public record StratumResponse(long id, String siteCode, String stratumNo, String description, Instant createdAt) {
    }

    /** 层位引用：供样本采集与封存服务定位层位。 */
    public record StratumRef(long id, String siteCode, String stratumNo) {
    }
}