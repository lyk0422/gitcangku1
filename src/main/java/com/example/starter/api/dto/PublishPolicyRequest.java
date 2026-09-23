package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 发布签名信任策略请求：policyVersion 唯一递增，1～10 个互不相同的可信 keyId，1≤m≤key 数。
 *
 * @param effectiveAt 生效时刻（UTC），到达后该版本成为当前生效策略
 */
public record PublishPolicyRequest(
        @Positive long policyVersion,
        @NotNull @Size(min = 1, max = 10) List<@NotBlank String> keyIds,
        @Positive int thresholdM,
        @NotNull Instant effectiveAt) {

    public PublishPolicyRequest {
        if (keyIds == null) {
            keyIds = List.of();
        } else {
            keyIds = List.copyOf(keyIds);
        }
    }
}
