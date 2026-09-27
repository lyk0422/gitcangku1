package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 单条弃用窗口设置项：被弃用版本与同制品替代版本、生效 UTC 时刻及说明。
 *
 * @param effectiveAt 弃用生效 UTC 时刻，不得早于服务端当前时钟
 * @param note        说明，可空（归一化为空串），最长 512 字符
 */
public record DeprecationSpec(
        @NotBlank String name,
        @Positive int version,
        @Positive int replacementVersion,
        @NotNull Instant effectiveAt,
        @Size(max = 512) String note) {

    public DeprecationSpec {
        if (note == null) {
            note = "";
        }
    }
}
