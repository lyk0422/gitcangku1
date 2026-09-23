package com.example.starter.aliquot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 联合取样申请中的单件母样取用量。
 *
 * @param sampleKey 母样业务键
 * @param quantity  从该母样取用量，正整数
 */
public record AliquotItemInput(
        @NotBlank @Size(max = 128) String sampleKey,
        @Positive Long quantity) {
}
