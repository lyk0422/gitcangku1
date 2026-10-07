package com.example.starter.firmware.service;

import com.example.starter.firmware.api.MigrationRuleInput;
import com.example.starter.firmware.domain.MigrationRule;
import com.example.starter.firmware.domain.MigrationRuleType;
import com.example.starter.firmware.error.ApiException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置迁移引擎：校验发布单规则、对设备配置做纯内存预演。
 * 规则有序执行，顺序是发布单身份一部分；重命名目标键不得已存在（不覆盖同名目标），
 * 乘数 1~10，所有值运算结果越界（0~1000000）即拒绝，不截断。预演失败抛 422，不产生任何落库。
 */
@Service
public class ConfigMigrationService {

    /**
     * 单设备配置键数上限。
     */
    public static final int MAX_KEYS = 10;

    /**
     * 配置值下限（含）。
     */
    public static final int MIN_VALUE = 0;

    /**
     * 配置值上限（含）。
     */
    public static final int MAX_VALUE = 1000000;

    /**
     * 校验并规整创建发布单请求中的迁移规则；不合法抛 400。
     */
    public List<MigrationRule> validateRules(List<MigrationRuleInput> inputs) {
        if (inputs.size() > 5) {
            throw ApiException.badRequest("TOO_MANY_RULES", "迁移规则至多 5 条，实际: " + inputs.size());
        }
        List<MigrationRule> rules = new ArrayList<>();
        for (MigrationRuleInput input : inputs) {
            rules.add(switch (input.type()) {
                case RENAME -> {
                    if (input.targetKey() == null || input.targetKey().isBlank()) {
                        throw ApiException.badRequest("RULE_TARGET_REQUIRED", "RENAME 规则必须提供 targetKey");
                    }
                    if (input.key().equals(input.targetKey())) {
                        throw ApiException.badRequest("RULE_TARGET_SAME", "RENAME 目标键不得与源键相同: " + input.key());
                    }
                    yield new MigrationRule(MigrationRuleType.RENAME, input.key(), input.targetKey(), null);
                }
                case MULTIPLY -> {
                    if (input.factor() == null) {
                        throw ApiException.badRequest("RULE_FACTOR_REQUIRED", "MULTIPLY 规则必须提供 factor");
                    }
                    yield new MigrationRule(MigrationRuleType.MULTIPLY, input.key(), null, input.factor());
                }
            });
        }
        return rules;
    }

    /**
     * 对给定配置按序预演迁移规则，返回迁移后的完整配置（输入不被修改）。
     * 源键不存在的规则跳过；目标键冲突或值越界抛 422。
     */
    public Map<String, Integer> preview(Map<String, Integer> config, List<MigrationRule> rules) {
        Map<String, Integer> result = new LinkedHashMap<>(config);
        for (MigrationRule rule : rules) {
            switch (rule.type()) {
                case RENAME -> {
                    Integer value = result.get(rule.key());
                    if (value == null) {
                        continue;
                    }
                    if (result.containsKey(rule.targetKey())) {
                        throw ApiException.unprocessable("CONFIG_TARGET_EXISTS",
                                "重命名目标键已存在: " + rule.targetKey());
                    }
                    result.remove(rule.key());
                    result.put(rule.targetKey(), value);
                }
                case MULTIPLY -> {
                    Integer value = result.get(rule.key());
                    if (value == null) {
                        continue;
                    }
                    long multiplied = (long) value * rule.factor();
                    if (multiplied < MIN_VALUE || multiplied > MAX_VALUE) {
                        throw ApiException.unprocessable("CONFIG_VALUE_OUT_OF_RANGE",
                                "键 " + rule.key() + " 乘 " + rule.factor() + " 后越界: " + multiplied);
                    }
                    result.put(rule.key(), (int) multiplied);
                }
            }
        }
        return result;
    }
}
