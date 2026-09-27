package com.example.starter.sampling;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 冲突簇人工裁决请求：指定采用的观测内容，裁决后簇不再重算。
 *
 * @param operator       裁决操作者标识
 * @param winningContent 裁决采用的观测内容
 */
public record AdjudicateClusterRequest(
        @NotBlank @Size(max = 128) String operator,
        @NotNull @Size(max = 1024) String winningContent) {
}
