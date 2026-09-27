package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.domain.Rows.TermRuleRow;

import java.util.ArrayList;
import java.util.List;

/**
 * 术语违规判定（译文提交、批量修订与发布复核共用）：
 * 源文按 Unicode 原文、区分大小写做连续子串匹配；仅源文命中 sourceTerm 的规则参与校验，
 * 译文正文（同样区分大小写）不含 requiredTranslation 即为违规。
 */
public final class TermValidation {

    private TermValidation() {
    }

    public static List<ApiDtos.TermRuleView> findViolations(String sourceText, String language, String content,
                                                            List<TermRuleRow> rules) {
        List<ApiDtos.TermRuleView> violations = new ArrayList<>();
        for (TermRuleRow rule : rules) {
            if (rule.language().equals(language) && sourceText.contains(rule.sourceTerm())
                    && !content.contains(rule.requiredTranslation())) {
                violations.add(new ApiDtos.TermRuleView(rule.sourceTerm(), rule.language(),
                        rule.requiredTranslation()));
            }
        }
        return violations;
    }
}
