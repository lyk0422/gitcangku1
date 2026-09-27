package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 跨区域迁移请求：创建新的授权 epoch，将明确列出的对象原子复制到目标区域，同时撤回源 epoch。
 *
 * @param requestId   迁移幂等请求标识（同时作为迁移单号）
 * @param subjectKey  主体标识（合成字符串）
 * @param purpose     用途：RESEARCH / PERSONALIZATION
 * @param sourceEpoch 源授权代次，必须为当前有效代次
 * @param targetRegion 目标驻留区域代码，必须预先登记且不同于源区域
 * @param recordKeys  明确列出的待复制对象记录键；整批校验通过才提交，任一对象已清除/无授权/区域不符则整次 422
 */
public record MigrationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotNull @Positive Integer sourceEpoch,
        @NotBlank @Size(max = 32) @Pattern(regexp = "[A-Z][A-Z0-9_]*") String targetRegion,
        @NotEmpty(message = "迁移对象集合不能为空") List<@NotBlank @Size(max = 128) String> recordKeys) {
}
