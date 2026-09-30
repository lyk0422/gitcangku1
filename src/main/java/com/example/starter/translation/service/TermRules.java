package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.TermRuleRow;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 术语规则归一化与违规判定工具，供普通术语更新与解冻后整单校验复用。
 */
final class TermRules {

    private TermRules() {
    }

    /**
     * 归一化并校验规则集：语言须在文档目标语言中、sourceTerm 与语言组合唯一，不符返回 422。
     * 不校验条数上限与字段非空（由 Bean Validation 在入口处完成）。
     */
    static List<TermRuleRow> normalize(List<ApiDtos.TermRuleInput> inputs, List<String> targetLanguages) {
        List<TermRuleRow> rules = new ArrayList<>();
        Set<List<String>> seen = new HashSet<>();
        for (ApiDtos.TermRuleInput input : inputs) {
            String language = input.language().trim().toLowerCase(Locale.ROOT);
            if (!targetLanguages.contains(language)) {
                throw ApiException.unprocessable("术语规则语言不在文档目标语言中: " + language);
            }
            if (!seen.add(List.of(input.sourceTerm(), language))) {
                throw ApiException.unprocessable("术语规则重复: " + input.sourceTerm() + "/" + language);
            }
            rules.add(new TermRuleRow(input.sourceTerm(), language, input.requiredTranslation()));
        }
        return rules;
    }

    /**
     * 术语违规判定：源文按 Unicode 原文、区分大小写做连续子串匹配；仅源文命中 sourceTerm 的规则参与校验，
     * 译文正文（同样区分大小写）不含 requiredTranslation 即为违规。返回全部违规规则。
     */
    static List<ApiDtos.TermRuleView> findViolations(String sourceText, String language, String content,
                                                     List<TermRuleRow> rules) {
        List<ApiDtos.TermRuleView> violations = new ArrayList<>();
        for (TermRuleRow rule : rules) {
            if (rule.language().equals(language) && sourceText.contains(rule.sourceTerm())
                    && !content.contains(rule.requiredTranslation())) {
                violations.add(toView(rule));
            }
        }
        return violations;
    }

    static ApiDtos.TermRuleView toView(TermRuleRow rule) {
        return new ApiDtos.TermRuleView(rule.sourceTerm(), rule.language(), rule.requiredTranslation());
    }
}
