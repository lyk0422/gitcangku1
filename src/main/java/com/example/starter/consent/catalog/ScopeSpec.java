package com.example.starter.consent.catalog;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 用途处理范围：由记录属性取值构成的闭集，全部取值为合成字符串。
 *
 * <p>范围守恒规则：每个新用途范围必须是旧用途范围的子集，且全部新范围并集
 * 不得扩大旧范围（本系统要求拆分不扩容，即并集必须恰好等于旧范围）。
 * 记录属性命中范围时，唯一目标用途要求该属性恰好被一个新范围覆盖。
 */
public record ScopeSpec(Set<String> values) {

    public ScopeSpec {
        values = Set.copyOf(values);
    }

    /**
     * 由属性取值集合构造范围；空集合不合法，由请求校验拦截。
     */
    public static ScopeSpec of(Collection<String> values) {
        return new ScopeSpec(new LinkedHashSet<>(values));
    }

    /**
     * 本范围是否为 other 的子集（空语义上也算子集，但空范围由校验拒绝）。
     */
    public boolean isSubsetOf(ScopeSpec other) {
        return other.values.containsAll(values);
    }

    /**
     * 两个范围是否存在交集；拆分时新用途范围两两不得重叠，否则记录可能出现多个目标。
     */
    public boolean overlaps(ScopeSpec other) {
        for (String value : values) {
            if (other.values.contains(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 多范围并集，结果稳定排序。
     */
    public static ScopeSpec union(Collection<ScopeSpec> scopes) {
        Set<String> all = new TreeSet<>();
        for (ScopeSpec scope : scopes) {
            all.addAll(scope.values);
        }
        return new ScopeSpec(all);
    }

    /**
     * 记录属性值是否落在本范围内。
     */
    public boolean covers(String attributeValue) {
        return values.contains(attributeValue);
    }

    /**
     * 规范化签名：排序后逗号连接，用于参数指纹与持久化。
     */
    public String canonical() {
        return String.join(",", new TreeSet<>(values));
    }

    /**
     * 反解析规范化签名。
     */
    public static ScopeSpec fromCanonical(String canonical) {
        if (canonical == null || canonical.isEmpty()) {
            return new ScopeSpec(Set.of());
        }
        return new ScopeSpec(new TreeSet<>(List.of(canonical.split(",", -1))));
    }
}
