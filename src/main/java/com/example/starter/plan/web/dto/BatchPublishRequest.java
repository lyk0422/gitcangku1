package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 整批发布请求：同批草稿在同一事务内统一校验时隙冲突与车底交路衔接，
 * 任一段不合法整单回滚。requestKey 为幂等键。
 */
public record BatchPublishRequest(
        @NotBlank String requestKey,
        @NotEmpty List<@NotBlank String> scheduleKeys) {
}
