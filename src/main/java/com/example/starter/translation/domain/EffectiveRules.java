package com.example.starter.translation.domain;

import com.example.starter.translation.domain.Rows.GlobalTermRuleRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 文档生效术语规则集的合成逻辑。
 * 文档同时引用一个全局术语库版本和自身术语版本：同一 sourceTerm 与目标语言并存时
 * 文档规则整条覆盖全局规则；文档规则声明 suppressed 时取消对应全局规则（该术语不再参与校验）。
 * 生效集按 sourceTerm 与目标语言升序稳定排序，逐条标明来源 GLOBAL、DOCUMENT 或 SUPPRESSED。
 */
public final class EffectiveRules {

    private EffectiveRules() {
    }

    /** 生效规则来源。 */
    public enum Origin {
        /** 来自全局术语库，未被文档规则覆盖或抑制。 */
        GLOBAL,
        /** 来自文档自身术语版本，整条覆盖同键全局规则。 */
        DOCUMENT,
        /** 文档规则声明抑制，取消对应全局规则，该术语不再参与校验。 */
        SUPPRESSED
    }

    /**
     * 一条生效规则。
     *
     * @param sourceTerm          源文术语，Unicode 原文、区分大小写
     * @param language            目标语言码，小写
     * @param requiredTranslation 必译文本；SUPPRESSED 条目不参与校验，仅保留占位值
     * @param origin              来源：GLOBAL、DOCUMENT 或 SUPPRESSED
     */
    public record EffectiveRule(String sourceTerm, String language, String requiredTranslation,
                                Origin origin) {
    }

    /**
     * 合成文档当前生效规则集：全局规则仅保留文档目标语言内的条目，
     * 文档规则按 sourceTerm+语言整条覆盖（suppressed 时改为抑制标记）。
     * 返回按 sourceTerm 与目标语言升序稳定排序的完整列表（含 SUPPRESSED 条目）。
     */
    public static List<EffectiveRule> compose(List<GlobalTermRuleRow> globalRules,
                                              List<TermRuleRow> documentRules,
                                              List<String> targetLanguages) {
        Map<String, EffectiveRule> merged = new TreeMap<>();
        for (GlobalTermRuleRow rule : globalRules) {
            if (!targetLanguages.contains(rule.language())) {
                continue;
            }
            merged.put(key(rule.sourceTerm(), rule.language()), new EffectiveRule(
                    rule.sourceTerm(), rule.language(), rule.requiredTranslation(), Origin.GLOBAL));
        }
        for (TermRuleRow rule : documentRules) {
            Origin origin = rule.suppressed() ? Origin.SUPPRESSED : Origin.DOCUMENT;
            merged.put(key(rule.sourceTerm(), rule.language()), new EffectiveRule(
                    rule.sourceTerm(), rule.language(), rule.requiredTranslation(), origin));
        }
        return List.copyOf(merged.values());
    }

    /** 过滤出实际参与校验的规则（剔除 SUPPRESSED 条目），保持原有排序。 */
    public static List<EffectiveRule> activeRules(List<EffectiveRule> rules) {
        return rules.stream().filter(rule -> rule.origin() != Origin.SUPPRESSED).toList();
    }

    private static String key(String sourceTerm, String language) {
        return sourceTerm + " " + language;
    }
}
