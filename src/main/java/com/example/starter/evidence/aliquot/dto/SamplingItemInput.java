package com.example.starter.evidence.aliquot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 联合取样申请中的单个母样取用项。
 *
 * @param sampleKey 母样业务键
 * @param qty       从该母样取用数量，正整数
 */
public record SamplingItemInput(
        @NotBlank @Size(max = 64) String sampleKey,
        @NotNull @Positive Long qty) {
}
