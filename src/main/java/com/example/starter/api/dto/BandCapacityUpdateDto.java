package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 高度带容量上调项。新容量必须大于当前容量（只允许上调）。
 *
 * @param bandId   已有高度带标识
 * @param capacity 新的同时容量，1~50，必须大于当前容量
 */
public record BandCapacityUpdateDto(
        @NotBlank @Size(max = 64) String bandId,
        @NotNull @Min(1) @Max(50) Integer capacity) {
}
