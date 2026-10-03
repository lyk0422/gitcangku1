package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 添加布卷请求：卷号批内唯一，每批最多 30 卷。 */
public record AddRollRequest(@NotBlank String rollNo) {
}