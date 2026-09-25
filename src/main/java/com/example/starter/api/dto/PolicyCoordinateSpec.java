package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 策略对单个坐标的要求。
 *
 * @param name           制品名称
 * @param requiredLevel  最低证明等级（含），非负整数
 * @param requiredDigest 要求的构建摘要；空串表示不校验摘要
 */
public record PolicyCoordinateSpec(
        @NotBlank String name,
        @PositiveOrZero int requiredLevel,
        String requiredDigest) {

    public PolicyCoordinateSpec {
        if (requiredDigest == null) {
            requiredDigest = "";
        }
    }
}
