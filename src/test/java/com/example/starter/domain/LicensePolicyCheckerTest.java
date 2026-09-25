package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 许可证策略检查器单元测试：闭包逐版本校验、UNKNOWN 语义、稳定排序与策略缺省放行。
 */
class LicensePolicyCheckerTest {

    private static Map<String, Integer> solution(Object... kv) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return map;
    }

    private static Map<String, String> licenses(Object... kv) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], (String) kv[i + 1]);
        }
        return map;
    }

    private static NamespacePolicy policy(String namespace, boolean rejectUnknown,
                                          String... allowed) {
        return new NamespacePolicy(namespace, 1, rejectUnknown, Set.of(allowed));
    }

    @Test
    void emptyPoliciesMeansEverythingAllowedIncludingUnknown() {
        Map<String, Integer> closure = solution("app", 1, "lib", 2);
        // lib:2 无登记许可证（UNKNOWN），但无策略命名空间：放行。
        List<LicenseViolation> violations =
                LicensePolicyChecker.check(closure, licenses("app:1", "MIT"), Map.of());
        assertThat(violations).isEmpty();
    }

    @Test
    void unregisteredLicenseIsUnknownAndAllowedByDefault() {
        Map<String, Integer> closure = solution("lib", 3);
        Map<String, NamespacePolicy> policies = Map.of(
                "lib", policy("lib", false, "MIT"));
        // UNKNOWN 且 rejectUnknown=false：放行。
        assertThat(LicensePolicyChecker.check(closure, Map.of(), policies)).isEmpty();
    }

    @Test
    void unknownLicenseRejectedWhenPolicyRejectsUnknown() {
        Map<String, Integer> closure = solution("lib", 3);
        Map<String, NamespacePolicy> policies = Map.of(
                "lib", policy("lib", true, "MIT"));
        List<LicenseViolation> violations =
                LicensePolicyChecker.check(closure, Map.of(), policies);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).name()).isEqualTo("lib");
        assertThat(violations.get(0).version()).isEqualTo(3);
        assertThat(violations.get(0).license()).isNull();
        assertThat(violations.get(0).reason())
                .isEqualTo(LicenseViolation.REASON_UNKNOWN_REJECTED);
    }

    @Test
    void registeredButDisallowedLicenseFails() {
        Map<String, Integer> closure = solution("lib", 3);
        Map<String, String> registered = licenses("lib:3", "GPL-3.0");
        Map<String, NamespacePolicy> policies = Map.of(
                "lib", policy("lib", false, "MIT", "Apache-2.0"));
        List<LicenseViolation> violations =
                LicensePolicyChecker.check(closure, registered, policies);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).reason())
                .isEqualTo(LicenseViolation.REASON_NOT_ALLOWED);
        assertThat(violations.get(0).license()).isEqualTo("GPL-3.0");
    }

    @Test
    void allowedLicensePassesEvenWhenUnknownRejected() {
        Map<String, Integer> closure = solution("lib", 3);
        Map<String, String> registered = licenses("lib:3", "Apache-2.0");
        Map<String, NamespacePolicy> policies = Map.of(
                "lib", policy("lib", true, "Apache-2.0"));
        assertThat(LicensePolicyChecker.check(closure, registered, policies)).isEmpty();
    }

    @Test
    void everyVersionInFullClosureIsCheckedIncludingIndirectDependencies() {
        // app -> b -> c：c 为间接依赖，其许可证不在允许集合，必须被发现。
        Map<String, Integer> closure = solution("app", 1, "b", 2, "c", 7);
        Map<String, String> registered = licenses(
                "app:1", "MIT", "b:2", "MIT", "c:7", "GPL-3.0");
        Map<String, NamespacePolicy> policies = Map.of(
                "app", policy("app", true, "MIT"),
                "b", policy("b", true, "MIT"),
                "c", policy("c", true, "MIT"));
        List<LicenseViolation> violations =
                LicensePolicyChecker.check(closure, registered, policies);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).name()).isEqualTo("c");
        assertThat(violations.get(0).version()).isEqualTo(7);
    }

    @Test
    void violationsAreStableSortedByNameThenVersion() {
        Map<String, Integer> closure = solution(
                "zeta", 1, "alpha", 2, "mid", 1, "alpha", 2);
        // alpha 只出现一次（Map），补充另一个违规：zeta 与 mid 也违规。
        Map<String, Integer> multi = solution("zeta", 1, "alpha", 2, "mid", 1);
        Map<String, NamespacePolicy> policies = new HashMap<>();
        policies.put("zeta", policy("zeta", true, "MIT"));
        policies.put("alpha", policy("alpha", true, "MIT"));
        policies.put("mid", policy("mid", true, "MIT"));
        List<LicenseViolation> violations =
                LicensePolicyChecker.check(multi, Map.of(), policies);
        assertThat(violations).extracting(LicenseViolation::name)
                .containsExactly("alpha", "mid", "zeta");
        // 传入的闭包参数不应被修改。
        assertThat(multi).containsEntry("zeta", 1);
        assertThat(closure).isNotEmpty();
    }

    @Test
    void mixedViolationReasonsForDifferentNamespaces() {
        Map<String, Integer> closure = solution(
                "known", 1, "mystery", 1, "ok", 1);
        Map<String, String> registered = licenses("known:1", "GPL-3.0", "ok:1", "MIT");
        Map<String, NamespacePolicy> policies = Map.of(
                "known", policy("known", false, "MIT"),
                "mystery", policy("mystery", true, "MIT"),
                "ok", policy("ok", true, "MIT"));
        List<LicenseViolation> violations =
                LicensePolicyChecker.check(closure, registered, policies);
        assertThat(violations).hasSize(2);
        assertThat(violations).extracting(LicenseViolation::name)
                .containsExactly("known", "mystery");
        assertThat(violations.get(0).reason())
                .isEqualTo(LicenseViolation.REASON_NOT_ALLOWED);
        assertThat(violations.get(1).reason())
                .isEqualTo(LicenseViolation.REASON_UNKNOWN_REJECTED);
    }

    @Test
    void sameNameDifferentVersionsCanHaveDifferentLicenses() {
        // 解析闭包中 lib 锁到 1（MIT 允许）；lib2 虽登记 GPL 但不在闭包内，不参与校验。
        Map<String, Integer> closure = solution("app", 1, "lib", 1);
        Map<String, String> registered = licenses(
                "app:1", "MIT", "lib:1", "MIT", "lib:2", "GPL-3.0");
        Map<String, NamespacePolicy> policies = Map.of(
                "lib", policy("lib", false, "MIT"));
        assertThat(LicensePolicyChecker.check(closure, registered, policies)).isEmpty();
    }
}
