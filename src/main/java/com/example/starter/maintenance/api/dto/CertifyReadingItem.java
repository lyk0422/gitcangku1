package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 批次认证中的单条读数认证项。
 *
 * @param equipmentId  设备标识
 * @param readingId    读数标识
 * @param revisionNo   待认证的读数修订号，与当前修订号不一致时整批 422 回滚
 */
public record CertifyReadingItem(
        @NotBlank String equipmentId,
        @NotBlank String readingId,
        @NotNull @Positive Integer revisionNo) {
}
