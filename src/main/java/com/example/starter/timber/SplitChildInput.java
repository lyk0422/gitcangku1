package com.example.starter.timber;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 拆分请求中的单个子批次入参。
 *
 * @param batchNo 子批次编号，全局唯一，非空
 * @param weightKg 子批次重量，单位公斤，必须为正整数
 */
public record SplitChildInput(@NotBlank String batchNo, @Positive int weightKg) {
}