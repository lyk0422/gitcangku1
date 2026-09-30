package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 人工复核请求。
 *
 * @param requestId 原靠泊申请的幂等键
 * @param action    APPROVE 通过（按当前最新潮高版本重新校核后才可确认）；REJECT 驳回
 */
public record ReviewRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @Pattern(regexp = "APPROVE|REJECT") String action) {
}
