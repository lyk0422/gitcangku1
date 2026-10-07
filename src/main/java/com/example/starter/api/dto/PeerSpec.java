package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * peer 依赖声明：闭区间 [minimumVersion, maximumVersion]，版本均为正整数。
 *
 * <p>peer 不建立可达边，仅约束普通依赖图已经可达的同名宿主制品；
 * peer 名称不能与所属制品版本自身同名（由 Service 层校验）。
 */
public record PeerSpec(
        @NotBlank String name,
        @Positive int minimumVersion,
        @Positive int maximumVersion) {
}
