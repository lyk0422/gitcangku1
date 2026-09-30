package com.example.starter.film.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 注册馆藏影片请求。
 *
 * @param code            影片业务编号
 * @param plannedSegments 计划片段总数，必须为正整数
 */
public record RegisterFilmRequest(
        @NotBlank String code,
        @NotNull @Positive Integer plannedSegments) {
}
