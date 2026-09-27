package com.example.starter.observation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * 离线三方合并纯规则（按字段比较基线 base、当前 current、候选 candidate），不访问数据库，可独立单测。
 *
 * <p>候选未改（等于基线）保留当前；当前未改接受候选；两边改为相同值也接受；否则该字段冲突。
 * 读数按数值比较（BigDecimal），地点与备注按原文比较。
 */
public final class MergeRules {

    private MergeRules() {
    }

    /**
     * 判断两个读数字符串是否数值相等；null 按对象相等处理。
     */
    public static boolean readingEquals(String left, String right) {
        if (left == null || right == null) {
            return Objects.equals(left, right);
        }
        return new BigDecimal(left).compareTo(new BigDecimal(right)) == 0;
    }

    /**
     * 普通/读数字段值相等判定：numeric=true 按数值，否则按原文。
     */
    public static boolean valueEquals(String left, String right, boolean numeric) {
        return numeric ? readingEquals(left, right) : Objects.equals(left, right);
    }

    /**
     * 判断单字段是否冲突：候选未改或任一侧未改或两边改为相同值均不冲突，否则冲突。
     */
    public static boolean fieldConflicts(String base, String current, String candidate, boolean numeric) {
        if (valueEquals(candidate, base, numeric)) {
            return false;
        }
        return !valueEquals(current, base, numeric) && !valueEquals(candidate, current, numeric);
    }

    /**
     * 计算单字段合并结果：候选未改保留当前；当前未改或两边相同接受候选；冲突时记录字段名并保留当前。
     *
     * @param fieldName       字段名，冲突时加入 conflictFields
     * @param conflictFields  收集冲突字段名的列表（固定顺序 location/reading/note）
     */
    public static String mergeField(String base, String current, String candidate, boolean numeric,
                                    String fieldName, List<String> conflictFields) {
        if (valueEquals(candidate, base, numeric)) {
            return current;
        }
        if (valueEquals(current, base, numeric) || valueEquals(candidate, current, numeric)) {
            return candidate;
        }
        conflictFields.add(fieldName);
        return current;
    }
}
