package com.example.starter.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 发布签名信任策略请求。
 *
 * @param policyVersion 策略版本号（policyKey，全局唯一，单调递增正数）
 * @param keyIds        可信钥匙标识清单，1～10 个，不可重复
 * @param threshold     阈值 m，1 <= m <= keyId 数量
 * @param effectiveAt   生效时刻（UTC，ISO-8601），新版本激活即替代旧版本
 */
public record PublishPolicyRequest(
        @NotNull @Positive Long policyVersion,
        @NotEmpty @Size(min = 1, max = 10) List<@Size(min = 1, max = 128) String> keyIds,
        @Positive int threshold,
        @NotNull Instant effectiveAt) {

    public PublishPolicyRequest {
        if (keyIds == null) {
            keyIds = List.of();
        } else {
            keyIds = List.copyOf(keyIds);
        }
    }
}
