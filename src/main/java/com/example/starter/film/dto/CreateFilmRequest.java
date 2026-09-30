package com.example.starter.film.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 建档请求：登记一部馆藏影片及其计划片段数。
 *
 * @param code            影片编号，业务唯一，3-64 位字母数字及 . _ : -
 * @param title           影片标题，非空，最长 200
 * @param plannedSegments 计划片段总数，至少 1
 */
public record CreateFilmRequest(
        @NotBlank
        @Size(min = 3, max = 64)
        String code,

        @NotBlank @Size(max = 200)
        String title,

        @NotNull @Min(1)
        Integer plannedSegments
) {
}
