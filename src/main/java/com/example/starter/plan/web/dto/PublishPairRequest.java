package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 夜间计划对联合发布请求。requestKey 为幂等键；
 * expectedFirstVersion / expectedSecondVersion 分别对首计划（运营日 D 的夜间计划）
 * 与次日计划（运营日 D+1）做乐观版本校验。
 */
public record PublishPairRequest(
        @NotBlank String requestKey,
        @NotBlank String nightPairKey,
        @NotNull Integer expectedFirstVersion,
        @NotNull Integer expectedSecondVersion) {
}
