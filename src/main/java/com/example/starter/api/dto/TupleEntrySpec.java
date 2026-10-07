package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 允许版本元组中的一个成员版本条目。
 */
public record TupleEntrySpec(
        @NotBlank String name,
        @Positive int version) {
}
