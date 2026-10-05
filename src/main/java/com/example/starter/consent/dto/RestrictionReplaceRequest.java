package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 字段级限制替换请求：整体替换当前有效代次的禁止字段清单，不撤回整代授权。
 *
 * @param requestId                  幂等请求标识
 * @param subjectKey                 主体标识（合成字符串）
 * @param purpose                    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param expectedRestrictionVersion 期望的当前限制清单版本（无限制清单为 0），不匹配返回 409
 * @param forbiddenFields            禁止字段名集合，可为空列表表示清空限制；字段名为英文字母数字下划线
 */
public record RestrictionReplaceRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotNull @Min(0) Integer expectedRestrictionVersion,
        @NotNull List<String> forbiddenFields) {
}
