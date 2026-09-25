package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 许可证门禁评估器单元测试：策略作用域优先、提交顺序裁决、文本状态、
 * 地区覆盖与缺失原因码。
 */
class LicenseGateTest {

    private LicenseGate.GateArtifact artifact(String name, int version, boolean direct,
                                              String... path) {
        return new LicenseGate.GateArtifact(name, version, direct, List.of(path));
    }

    private LicenseGate.GatePolicy policy(long id, String scope, Long lockId,
                                          String name, Integer version,
                                          String license, String action) {
        return new LicenseGate.GatePolicy(id, scope, lockId, name, version, license, action);
    }

    private LicenseGate.GateBinding binding(long id, String scope, Long lockId,
                                            String name, Integer version,
                                            String license, String noticeKey, int noticeVersion) {
        return new LicenseGate.GateBinding(id, scope, lockId, name, version, license,
                noticeKey, noticeVersion);
    }

    private LicenseGate.GateNotice notice(String key, int version, String license,
                                          String status, String... regions) {
        return new LicenseGate.GateNotice(key, version, license, status, Set.of(regions));
    }

    private Map<String, LicenseGate.GateNotice> notices(LicenseGate.GateNotice... items) {
        Map<String, LicenseGate.GateNotice> map = new java.util.HashMap<>();
        for (LicenseGate.GateNotice item : items) {
            map.put(item.noticeKey() + "#" + item.version(), item);
        }
        return map;
    }

    @Test
    void noPolicyMeansNoHitAndCompliant() {
        LicenseGate.GateResult result = LicenseGate.evaluate(1L,
                List.of(artifact("app", 1, true, "app:1")), List.of(), List.of(),
                Map.of(), Set.of("CN"));
        assertThat(result.compliant()).isTrue();
        assertThat(result.hits()).isEmpty();
        assertThat(result.missing()).isEmpty();
    }

    @Test
    void noticeRequiredWithoutBindingIsMissing() {
        LicenseGate.GateResult result = LicenseGate.evaluate(1L,
                List.of(artifact("lib", 2, false, "app:1", "lib:2")),
                List.of(policy(1, "COORDINATE", null, "lib", null, "GPL-3.0",
                        LicenseGate.ACTION_NOTICE_REQUIRED)),
                List.of(), Map.of(), Set.of("CN"));
        assertThat(result.compliant()).isFalse();
        assertThat(result.hits()).hasSize(1);
        assertThat(result.missing()).hasSize(1);
        assertThat(result.missing().get(0).reason())
                .isEqualTo(LicenseGate.REASON_NOTICE_MISSING);
        assertThat(result.missing().get(0).name()).isEqualTo("lib");
        assertThat(result.missing().get(0).direct()).isFalse();
        assertThat(result.missing().get(0).path()).containsExactly("app:1", "lib:2");
    }

    @Test
    void draftTextIsNotApprovedAndWithdrawnTextRejected() {
        LicenseGate.GateArtifact lib = artifact("lib", 1, true, "app:1", "lib:1");
        List<LicenseGate.GatePolicy> policies = List.of(policy(1, "COORDINATE", null,
                "lib", null, "GPL-3.0", LicenseGate.ACTION_NOTICE_REQUIRED));

        LicenseGate.GateResult draft = LicenseGate.evaluate(1L, List.of(lib), policies,
                List.of(binding(1, "COORDINATE", null, "lib", null, "GPL-3.0", "n1", 1)),
                notices(notice("n1", 1, "GPL-3.0", LicenseGate.STATUS_DRAFT, "CN")),
                Set.of("CN"));
        assertThat(draft.compliant()).isFalse();
        assertThat(draft.missing().get(0).reason())
                .isEqualTo(LicenseGate.REASON_TEXT_NOT_APPROVED);

        LicenseGate.GateResult withdrawn = LicenseGate.evaluate(1L, List.of(lib), policies,
                List.of(binding(1, "COORDINATE", null, "lib", null, "GPL-3.0", "n1", 1)),
                notices(notice("n1", 1, "GPL-3.0", LicenseGate.STATUS_WITHDRAWN, "CN")),
                Set.of("CN"));
        assertThat(withdrawn.missing().get(0).reason())
                .isEqualTo(LicenseGate.REASON_TEXT_WITHDRAWN);
    }

    @Test
    void approvedTextMustCoverAllRequiredRegions() {
        List<LicenseGate.GatePolicy> policies = List.of(policy(1, "COORDINATE", null,
                "lib", null, "MIT", LicenseGate.ACTION_NOTICE_REQUIRED));
        List<LicenseGate.GateBinding> bindings = List.of(binding(1, "COORDINATE", null,
                "lib", null, "MIT", "n1", 1));

        LicenseGate.GateResult covered = LicenseGate.evaluate(1L,
                List.of(artifact("lib", 1, true, "lib:1")), policies, bindings,
                notices(notice("n1", 1, "MIT", LicenseGate.STATUS_APPROVED, "CN", "US")),
                Set.of("CN"));
        assertThat(covered.compliant()).isTrue();

        LicenseGate.GateResult uncovered = LicenseGate.evaluate(1L,
                List.of(artifact("lib", 1, true, "lib:1")), policies, bindings,
                notices(notice("n1", 1, "MIT", LicenseGate.STATUS_APPROVED, "US")),
                Set.of("CN", "US"));
        assertThat(uncovered.compliant()).isFalse();
        assertThat(uncovered.missing().get(0).reason())
                .isEqualTo(LicenseGate.REASON_REGION_NOT_COVERED);
        assertThat(uncovered.missing().get(0).detail()).contains("CN");
    }

    @Test
    void lockScopeOverridesCoordinateScopeAndLatestWins() {
        // 同一制品两条策略：LOCK + ALLOWED（新）与 COORDINATE + NOTICE_REQUIRED（旧）。
        List<LicenseGate.GatePolicy> policies = List.of(
                policy(1, "COORDINATE", null, "lib", null, "GPL-3.0",
                        LicenseGate.ACTION_NOTICE_REQUIRED),
                policy(2, "LOCK", 1L, "lib", null, "MIT", LicenseGate.ACTION_ALLOWED));
        LicenseGate.GateResult forLock1 = LicenseGate.evaluate(1L,
                List.of(artifact("lib", 1, false, "lib:1")), policies, List.of(),
                Map.of(), Set.of("CN"));
        assertThat(forLock1.compliant()).isTrue();
        assertThat(forLock1.hits().get(0).scopeType()).isEqualTo("LOCK");
        assertThat(forLock1.hits().get(0).action()).isEqualTo(LicenseGate.ACTION_ALLOWED);
        assertThat(forLock1.hits().get(0).licenseId()).isEqualTo("MIT");

        // 另一张锁图上 LOCK 策略不生效，回退到 COORDINATE 策略。
        LicenseGate.GateResult forLock2 = LicenseGate.evaluate(2L,
                List.of(artifact("lib", 1, false, "lib:1")), policies, List.of(),
                Map.of(), Set.of("CN"));
        assertThat(forLock2.compliant()).isFalse();
        assertThat(forLock2.hits().get(0).scopeType()).isEqualTo("COORDINATE");

        // 同作用域内 ID 最大（最新提交）者胜：COORDINATE 新策略改为 ALLOWED。
        List<LicenseGate.GatePolicy> newer = new java.util.ArrayList<>(policies);
        newer.add(policy(3, "COORDINATE", null, "lib", null, "GPL-3.0",
                LicenseGate.ACTION_ALLOWED));
        LicenseGate.GateResult latestWins = LicenseGate.evaluate(2L,
                List.of(artifact("lib", 1, false, "lib:1")), newer, List.of(),
                Map.of(), Set.of("CN"));
        assertThat(latestWins.compliant()).isTrue();
        assertThat(latestWins.hits().get(0).action()).isEqualTo(LicenseGate.ACTION_ALLOWED);
    }

    @Test
    void exactCoordinateVersionPolicyDoesNotHitOtherVersions() {
        List<LicenseGate.GatePolicy> policies = List.of(policy(1, "COORDINATE", null,
                "lib", 2, "GPL-3.0", LicenseGate.ACTION_NOTICE_REQUIRED));
        LicenseGate.GateResult result = LicenseGate.evaluate(1L,
                List.of(artifact("lib", 1, true, "lib:1")), policies, List.of(),
                Map.of(), Set.of("CN"));
        assertThat(result.hits()).isEmpty();
        assertThat(result.compliant()).isTrue();
    }

    @Test
    void bindingWithMismatchedLicenseIsReportedMissing() {
        List<LicenseGate.GatePolicy> policies = List.of(policy(1, "COORDINATE", null,
                "lib", null, "GPL-3.0", LicenseGate.ACTION_NOTICE_REQUIRED));
        List<LicenseGate.GateBinding> bindings = List.of(binding(1, "COORDINATE", null,
                "lib", null, "GPL-3.0", "n1", 1));
        LicenseGate.GateResult result = LicenseGate.evaluate(1L,
                List.of(artifact("lib", 1, true, "lib:1")), policies, bindings,
                notices(notice("n1", 1, "MIT", LicenseGate.STATUS_APPROVED, "CN")),
                Set.of("CN"));
        assertThat(result.compliant()).isFalse();
        assertThat(result.missing().get(0).reason())
                .isEqualTo(LicenseGate.REASON_NOTICE_MISSING);
        assertThat(result.missing().get(0).detail()).contains("不一致");
    }
}
