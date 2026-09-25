package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * 锁定图批量发布请求：noticeKey 为发布幂等键，整批原子发布或整体失败。
 */
public record PublishRequest(
        @NotBlank String noticeKey,
        @NotEmpty List<@Positive Long> lockFileIds,
        @NotEmpty List<@NotBlank String> targetRegions) {
}
