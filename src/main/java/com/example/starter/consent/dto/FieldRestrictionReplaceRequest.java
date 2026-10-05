package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 字段级限制替换请求：整体替换当前有效代次的禁止字段清单，版本从 1 开始递增。
 *
 * @param requestId                  幂等请求标识，同一 requestId 相同参数重试返回原结果
 * @param subjectKey                 主体标识（合成字符串）
 * @param purpose                    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param expectedRestrictionVersion 期望的当前限制清单版本，0 表示尚无清单；不匹配返回 409
 * @param restrictedFields           新的禁止字段名集合，可为空（表示不禁止任何字段）；
 *                                   字段名为英文字母、数字、下划线（1-64 字符）
 */
public record FieldRestrictionReplaceRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotNull @Min(0) Integer expectedRestrictionVersion,
        @NotNull List<@NotBlank @Size(max = 64) String> restrictedFields) {
}
