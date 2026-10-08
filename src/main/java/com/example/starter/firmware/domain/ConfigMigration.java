package com.example.starter.firmware.domain;

import com.example.starter.firmware.error.ApiException;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 配置迁移预演：对完整配置按声明顺序应用规则，产出迁移后的完整配置。
 * 重命名目标已存在或乘法结果越界（0~1000000）时拒绝（422），不截断、不覆盖同名目标；
 * 源键不存在时该规则为空操作。纯函数，不落库。
 */
public final class ConfigMigration {

    /**
     * 配置值上限（含），下限为0。
     */
    public static final int MAX_VALUE = 1_000_000;

    private ConfigMigration() {
    }

    /**
     * 按顺序应用规则，返回键升序的迁移后完整配置；任一规则越界即抛出 422。
     */
    public static Map<String, Integer> preview(Map<String, Integer> config, List<MigrationRule> rules) {
        Map<String, Integer> result = new TreeMap<>(config);
        for (MigrationRule rule : rules) {
            switch (rule.type()) {
                case RENAME -> {
                    Integer value = result.get(rule.key());
                    if (value != null) {
                        if (result.containsKey(rule.targetKey())) {
                            throw ApiException.unprocessable("CONFIG_RENAME_TARGET_EXISTS",
                                    "重命名目标键已存在: " + rule.targetKey());
                        }
                        result.remove(rule.key());
                        result.put(rule.targetKey(), value);
                    }
                }
                case MULTIPLY -> {
                    Integer value = result.get(rule.key());
                    if (value != null) {
                        long newValue = (long) value * rule.multiplier();
                        if (newValue > MAX_VALUE) {
                            throw ApiException.unprocessable("CONFIG_VALUE_OVERFLOW",
                                    "键 " + rule.key() + " 乘 " + rule.multiplier() + " 后越界: " + newValue);
                        }
                        result.put(rule.key(), (int) newValue);
                    }
                }
            }
        }
        return result;
    }
}
