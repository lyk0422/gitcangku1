package com.example.starter.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 新建来源策略版本请求：每次调用追加一个不可改写的新版本。
 */
public record CreatePolicyRequest(
        @Positive int minLevel,
        @NotEmpty @Size(max = 10) List<@NotBlank String> allowedRepos) {
}
