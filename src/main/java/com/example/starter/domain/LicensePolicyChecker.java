package com.example.starter.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 许可证策略检查器：在依赖解析成功后，对完整解析闭包逐版本校验命名空间策略。
 *
 * <p>纯函数：输入为解析结果、各制品版本已登记许可证（键为 "name:version"，
 * 缺省即 UNKNOWN）以及命名空间策略；输出为按（名称, 版本）稳定排序的违规列表。
 */
public final class LicensePolicyChecker {

    private LicensePolicyChecker() {
    }

    /**
     * 校验解析闭包中每个版本的许可证。
     *
     * @param solution 名称 -> 精确版本（完整依赖闭包，含根）
     * @param licenses "name:version" -> 已登记许可证；缺失视为 UNKNOWN
     * @param policies 命名空间 -> 策略；无策略的命名空间不做限制
     * @return 稳定排序的违规列表；空列表表示全部通过
     */
    public static List<LicenseViolation> check(Map<String, Integer> solution,
                                               Map<String, String> licenses,
                                               Map<String, NamespacePolicy> policies) {
        List<LicenseViolation> violations = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : solution.entrySet()) {
            String name = entry.getKey();
            int version = entry.getValue();
            NamespacePolicy policy = policies.get(name);
            if (policy == null) {
                continue;
            }
            String license = licenses.get(name + ":" + version);
            String reason = policy.check(license);
            if (reason != null) {
                violations.add(new LicenseViolation(name, version, license, reason));
            }
        }
        violations.sort(Comparator.comparing(LicenseViolation::name)
                .thenComparingInt(LicenseViolation::version));
        return List.copyOf(violations);
    }
}
