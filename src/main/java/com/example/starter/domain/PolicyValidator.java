package com.example.starter.domain;

import com.example.starter.api.dto.PublishPolicyRequest;

import java.util.List;

/**
 * 替代策略发布前的结构校验：
 * <ul>
 *   <li>原坐标与替代坐标不能相同（规则的精确原模式命中其自身替代坐标）；</li>
 *   <li>同一策略内规则不得重叠命中（同平台且原坐标模式存在共同命中坐标）。</li>
 * </ul>
 */
public final class PolicyValidator {

    private PolicyValidator() {
    }

    /**
     * 校验规则集合，违例抛出 {@link IllegalArgumentException}（由服务层转 422）。
     */
    public static void validate(List<PublishPolicyRequest.RuleSpec> rules) {
        for (int i = 0; i < rules.size(); i++) {
            PublishPolicyRequest.RuleSpec rule = rules.get(i);
            CoordinatePattern pattern;
            try {
                pattern = CoordinatePattern.parse(rule.sourcePattern());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("规则 " + i + " 原坐标模式非法: " + e.getMessage());
            }
            for (PublishPolicyRequest.AlternativeSpec alternative : rule.alternatives()) {
                Coordinate coordinate = new Coordinate(alternative.name().trim(), alternative.version());
                if (pattern.matches(coordinate)) {
                    throw new IllegalArgumentException(
                            "规则 " + i + " 的原坐标与替代坐标相同: "
                                    + coordinate.name() + ":" + coordinate.version());
                }
            }
        }

        for (int i = 0; i < rules.size(); i++) {
            for (int j = i + 1; j < rules.size(); j++) {
                PublishPolicyRequest.RuleSpec a = rules.get(i);
                PublishPolicyRequest.RuleSpec b = rules.get(j);
                if (!a.targetPlatform().trim().equals(b.targetPlatform().trim())) {
                    continue;
                }
                if (CoordinatePattern.overlap(a.sourcePattern(), b.sourcePattern())) {
                    throw new IllegalArgumentException(
                            "规则 " + i + " 与规则 " + j + " 在平台 "
                                    + a.targetPlatform().trim() + " 上原坐标模式重叠命中");
                }
            }
        }
    }
}
