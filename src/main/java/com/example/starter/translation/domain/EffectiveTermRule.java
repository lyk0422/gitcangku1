package com.example.starter.translation.domain;

/**
 * 生效术语规则：由文档引用的全局术语库版本与文档自身术语版本合成，非数据库行。
 * 同一 sourceTerm 与目标语言并存时文档规则整条覆盖全局规则；
 * 文档规则声明 suppressed 时取消对应全局规则，该术语不再参与校验。
 *
 * @param sourceTerm          源文术语，Unicode 原文、区分大小写，按连续子串匹配
 * @param language            目标语言码，小写
 * @param requiredTranslation 该术语在目标语言中的必译文本；source 为 SUPPRESSED 时为 null
 * @param source              规则来源：GLOBAL（全局术语库）、DOCUMENT（文档术语）、SUPPRESSED（文档声明抑制）
 */
public record EffectiveTermRule(String sourceTerm, String language, String requiredTranslation,
                                String source) {

    public static final String SOURCE_GLOBAL = "GLOBAL";
    public static final String SOURCE_DOCUMENT = "DOCUMENT";
    public static final String SOURCE_SUPPRESSED = "SUPPRESSED";

    /** 是否参与译文校验：被抑制的规则不参与。 */
    public boolean enforced() {
        return !SOURCE_SUPPRESSED.equals(source);
    }
}
