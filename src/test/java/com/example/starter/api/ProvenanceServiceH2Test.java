package com.example.starter.api;

import com.example.starter.api.dto.AttestationResponse;
import com.example.starter.api.dto.DefinePolicyRequest;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.MigratePoliciesRequest;
import com.example.starter.api.dto.MigrationResponse;
import com.example.starter.api.dto.PolicyCoordinateSpec;
import com.example.starter.api.dto.PolicyResponse;
import com.example.starter.api.dto.ProvenanceDiagnosticResponse;
import com.example.starter.api.dto.PublishLockRequest;
import com.example.starter.api.dto.ReleaseSnapshotResponse;
import com.example.starter.api.dto.SubmitAttestationRequest;
import com.example.starter.support.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 基于嵌入式 H2（MODE=MySQL）的来源策略服务数据库与业务测试：
 * 证明作用域、锁定图解析门禁、策略版本、发布快照固化、撤销边界、
 * 批量迁移预校验与并发幂等。
 */
@SpringBootTest
class ProvenanceServiceH2Test {

    @Autowired
    private ProvenanceService provenanceService;

    @Autowired
    private ArtifactService artifactService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM release_snapshot_entry");
        jdbcTemplate.update("DELETE FROM release_snapshot");
        jdbcTemplate.update("DELETE FROM provenance_attestation");
        jdbcTemplate.update("DELETE FROM provenance_policy_coordinate");
        jdbcTemplate.update("DELETE FROM provenance_policy");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private static PolicyCoordinateSpec coord(String name, int level, String digest) {
        return new PolicyCoordinateSpec(name, level, digest == null ? "" : digest);
    }

    private void register(String name, int version, com.example.starter.api.dto.DependencySpec... deps) {
        artifactService.registerArtifact(rid(),
                new com.example.starter.api.dto.RegisterArtifactRequest(name, version, List.of(deps)));
    }

    private static com.example.starter.api.dto.DependencySpec dep(String name, int min, int max) {
        return new com.example.starter.api.dto.DependencySpec(name, min, max);
    }

    private PolicyResponse definePolicy(String graph, int baseVersion,
                                        PolicyCoordinateSpec... coordinates) {
        return provenanceService.definePolicy(rid(), "alice",
                new DefinePolicyRequest(graph, baseVersion, List.of(coordinates)));
    }

    private AttestationResponse attest(String name, int version, String repo,
                                       String digest, int level) {
        return provenanceService.submitAttestation(rid(), "alice",
                new SubmitAttestationRequest(name, version, repo, digest, level, null));
    }

    private long repositoryVersion() {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
    }

    /** 准备标准图：app:1 -> lib[2,2] -> util[1,1]，共 3 个制品、仓库版本 3。 */
    private void seedGraph() {
        register("app", 1, dep("lib", 2, 2));
        register("lib", 2, dep("util", 1, 1));
        register("util", 1);
    }

    // ------------------------------------------------------------------
    // 主流程：证明作用域 + 锁定解析门禁
    // ------------------------------------------------------------------

    @Test
    void lockWithSatisfyingPolicyAndAttestationsBindsCurrentPolicyVersion() {
        seedGraph();
        definePolicy("graph-a", 0,
                coord("app", 1, ""), coord("lib", 2, "sha-lib"), coord("util", 1, ""));
        attest("app", 1, "repo-a", "sha-app", 3);
        attest("lib", 2, "repo-a", "sha-lib", 5);
        attest("util", 1, "repo-a", "sha-util", 1);
        // 策略与 3 条证明各推进一次仓库版本：当前为 3 + 1 + 3 = 7。
        long expectedVersion = repositoryVersion();

        LockFileResponse lock = artifactService.createLock(rid(),
                new LockRequest("graph-a", "app", 1, expectedVersion));

        assertThat(lock.policyVersion()).isEqualTo(1);
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 2), tuple("util", 1));

        ProvenanceDiagnosticResponse diag = provenanceService.diagnose(lock.id());
        assertThat(diag.compliant()).isTrue();
        assertThat(diag.policyVersion()).isEqualTo(1);
        assertThat(diag.violations()).isEmpty();
        assertThat(diag.provenancePath()).extracting("name", "matched")
                .containsExactly(tuple("app", true), tuple("lib", true), tuple("util", true));
        // 来源路径：util 的完整传递路径。
        assertThat(diag.provenancePath().get(2).dependencyPath())
                .containsExactly("app:1", "lib:2", "util:1");
    }

    @Test
    void missingAttestationBlocksLockWith422FullPathAndWritesNothing() {
        seedGraph();
        definePolicy("graph-a", 0,
                coord("app", 1, ""), coord("lib", 1, ""), coord("util", 1, ""));
        attest("app", 1, "repo-a", "sha-app", 1);
        attest("lib", 2, "repo-a", "sha-lib", 1);
        // util 未证明。
        long expectedVersion = repositoryVersion();

        assertThatThrownBy(() -> artifactService.createLock(rid(),
                new LockRequest("graph-a", "app", 1, expectedVersion)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("PROVENANCE_POLICY_VIOLATION");
                    assertThat(e.getViolations()).hasSize(1);
                    assertThat(e.getViolations().get(0).reason())
                            .isEqualTo("MISSING_ATTESTATION");
                    assertThat(e.getViolations().get(0).path())
                            .containsExactly("app:1", "lib:2", "util:1");
                });

        // 整次解析快照不写入，且不推进仓库版本。
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_entry", Integer.class))
                .isZero();
        assertThat(repositoryVersion()).isEqualTo(expectedVersion);
    }

    @Test
    void digestMismatchAndInsufficientLevelReturnDistinctReasons() {
        seedGraph();
        definePolicy("graph-a", 0,
                coord("app", 1, "expected-app"),
                coord("lib", 5, ""),
                coord("util", 1, ""));
        attest("app", 1, "repo-a", "actual-app", 9);   // 摘要不匹配
        attest("lib", 2, "repo-a", "sha-lib", 2);       // 等级不足
        attest("util", 1, "repo-a", "sha-util", 1);
        long expectedVersion = repositoryVersion();

        assertThatThrownBy(() -> artifactService.createLock(rid(),
                new LockRequest("graph-a", "app", 1, expectedVersion)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getViolations()).extracting("reason")
                            .containsExactly("DIGEST_MISMATCH", "LEVEL_INSUFFICIENT");
                    assertThat(e.getViolations().get(0).path()).containsExactly("app:1");
                    assertThat(e.getViolations().get(1).path())
                            .containsExactly("app:1", "lib:2");
                });
    }

    @Test
    void lockingNamedGraphBeforePolicyExistsReturns422() {
        register("app", 1);
        assertThatThrownBy(() -> artifactService.createLock(rid(),
                new LockRequest("graph-x", "app", 1, 1L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void attestationForOneVersionDoesNotCoverAnotherVersion() {
        // app:1 -> lib[1,2]，仓库存在 lib1 与 lib2；解析取高版本 lib2。
        register("app", 1, dep("lib", 1, 2));
        register("lib", 1);
        register("lib", 2);
        definePolicy("graph-a", 0,
                coord("app", 1, ""), coord("lib", 1, ""));
        attest("app", 1, "repo", "d", 1);
        attest("lib", 1, "repo", "d", 1);
        long expectedVersion = repositoryVersion();

        // 只证明 lib1 不能覆盖解析命中的 lib2：同坐标新版本须重新证明。
        assertThatThrownBy(() -> artifactService.createLock(rid(),
                new LockRequest("graph-a", "app", 1, expectedVersion)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getViolations()).extracting("reason")
                            .containsExactly("MISSING_ATTESTATION");
                    assertThat(e.getViolations().get(0).path())
                            .containsExactly("app:1", "lib:2");
                });
    }

    // ------------------------------------------------------------------
    // 策略版本不可原地改写
    // ------------------------------------------------------------------

    @Test
    void policyVersionsAreAppendOnlyAndSortedCoordinates() {
        definePolicy("graph-a", 0,
                coord("util", 1, ""), coord("app", 1, ""), coord("lib", 1, ""));
        definePolicy("graph-a", 1,
                coord("app", 2, "sha"), coord("lib", 2, ""), coord("util", 2, ""));

        PolicyResponse v1 = provenanceService.getPolicy("graph-a", 1);
        PolicyResponse latest = provenanceService.getPolicy("graph-a", null);
        assertThat(v1.version()).isEqualTo(1);
        assertThat(v1.coordinates()).extracting("name")
                .containsExactly("app", "lib", "util");
        assertThat(latest.version()).isEqualTo(2);
        assertThat(latest.coordinates()).extracting("name", "requiredLevel", "requiredDigest")
                .containsExactly(tuple("app", 2, "sha"), tuple("lib", 2, ""), tuple("util", 2, ""));

        // 过期基线版本定义 → 409，不产生新版本。
        assertThatThrownBy(() -> provenanceService.definePolicy(rid(), "bob",
                new DefinePolicyRequest("graph-a", 1, List.of(coord("app", 1, "")))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(provenanceService.getPolicy("graph-a", null).version()).isEqualTo(2);
    }

    @Test
    void duplicateCoordinateNamesRejectedWith400() {
        assertThatThrownBy(() -> provenanceService.definePolicy(rid(), "alice",
                new DefinePolicyRequest("graph-a", 0,
                        List.of(coord("app", 1, ""), coord("app", 2, "")))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    // ------------------------------------------------------------------
    // 撤销作用域：未发布不可用；已发布不倒改；新版本重新证明
    // ------------------------------------------------------------------

    @Test
    void revocationBlocksUnpublishedLocksButNewVersionCanBeReattested() {
        seedGraph();
        definePolicy("graph-a", 0,
                coord("app", 1, ""), coord("lib", 1, ""), coord("util", 1, ""));
        AttestationResponse libAttestation = attest("lib", 2, "repo-a", "sha-lib", 2);
        attest("app", 1, "repo-a", "sha-app", 1);
        attest("util", 1, "repo-a", "sha-util", 1);

        provenanceService.revokeAttestation(rid(), libAttestation.id());

        long versionNow = repositoryVersion();
        assertThatThrownBy(() -> artifactService.createLock(rid(),
                new LockRequest("graph-a", "app", 1, versionNow)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getViolations()).extracting("reason")
                            .containsExactly("ATTESTATION_REVOKED");
                });

        // 撤销不区分版本以外的作用域：同坐标重新提交证明即可恢复（证明按提交顺序裁决最新一条）。
        attest("lib", 2, "repo-a", "sha-lib-2", 2);
        LockFileResponse lock = artifactService.createLock(rid(),
                new LockRequest("graph-a", "app", 1, repositoryVersion()));
        assertThat(lock.policyVersion()).isEqualTo(1);
    }

    @Test
    void doubleRevokeReturns409AndConsumesNoKey() {
        register("util", 1);
        AttestationResponse a = attest("util", 1, "repo", "d", 1);
        provenanceService.revokeAttestation(rid(), a.id());
        long versionAfter = repositoryVersion();

        assertThatThrownBy(() -> provenanceService.revokeAttestation(rid(), a.id()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(repositoryVersion()).isEqualTo(versionAfter);
    }

    // ------------------------------------------------------------------
    // 发布快照固化：收紧与撤销不倒改
    // ------------------------------------------------------------------

    @Test
    void publishedSnapshotFreezesPolicyAndAttestationsAgainstTighteningAndRevocation() {
        seedGraph();
        definePolicy("graph-a", 0,
                coord("app", 1, ""), coord("lib", 1, ""), coord("util", 1, ""));
        AttestationResponse appAtt = attest("app", 1, "repo-a", "sha-app", 1);
        AttestationResponse libAtt = attest("lib", 2, "repo-a", "sha-lib", 1);
        AttestationResponse utilAtt = attest("util", 1, "repo-a", "sha-util", 1);
        long afterAttestations = repositoryVersion();
        LockFileResponse lock = artifactService.createLock(rid(),
                new LockRequest("graph-a", "app", 1, afterAttestations));

        ReleaseSnapshotResponse release = provenanceService.publishLock(rid(), "alice",
                new PublishLockRequest(lock.id()));
        assertThat(release.policyVersion()).isEqualTo(1);
        assertThat(release.entries()).extracting("name", "attestationId")
                .containsExactly(tuple("app", appAtt.id()), tuple("lib", libAtt.id()),
                        tuple("util", utilAtt.id()));
        String key = release.provenanceKey();
        assertThat(key).hasSize(64).matches("[0-9a-f]{64}");

        // 收紧策略到等级 9，并撤销全部证明。
        definePolicy("graph-a", 1,
                coord("app", 9, ""), coord("lib", 9, ""), coord("util", 9, ""));
        provenanceService.revokeAttestation(rid(), appAtt.id());
        provenanceService.revokeAttestation(rid(), libAtt.id());
        provenanceService.revokeAttestation(rid(), utilAtt.id());

        // 已发布结果不倒改：同键（锁定图/策略/证明摘要/操作者）内容不变。
        ReleaseSnapshotResponse refetched = provenanceService.getRelease(lock.id());
        assertThat(refetched.provenanceKey()).isEqualTo(key);
        assertThat(refetched.entries()).usingRecursiveFieldByFieldElementComparator()
                .isEqualTo(release.entries());
        assertThat(refetched.policyVersion()).isEqualTo(1);

        // 未发布的新解析受收紧与撤销影响：阻断。
        ProvenanceDiagnosticResponse diag = provenanceService.diagnose(lock.id());
        assertThat(diag.policyVersion()).isEqualTo(1);
        assertThat(diag.currentPolicyVersion()).isEqualTo(2);
        assertThat(diag.compliant()).isFalse();
        assertThat(diag.violations()).extracting("reason")
                .contains("POLICY_VERSION_OUTDATED",
                        "ATTESTATION_REVOKED", "ATTESTATION_REVOKED", "ATTESTATION_REVOKED");
        // 锁定图绑定版本不变（收紧只影响后续解析），诊断当前证明状态显示撤销。
        assertThat(diag.provenancePath()).extracting("reason")
                .containsExactly("ATTESTATION_REVOKED", "ATTESTATION_REVOKED",
                        "ATTESTATION_REVOKED");
    }

    @Test
    void publishRequiresMigrationWhenPolicyAdvancedThenSucceedsAfterMigration() {
        seedGraph();
        definePolicy("graph-a", 0,
                coord("app", 1, ""), coord("lib", 1, ""), coord("util", 1, ""));
        attest("app", 1, "repo", "d", 1);
        attest("lib", 2, "repo", "d", 1);
        attest("util", 1, "repo", "d", 1);
        LockFileResponse lock = artifactService.createLock(rid(),
                new LockRequest("graph-a", "app", 1, repositoryVersion()));

        // 定义 v2（同样宽松）：已存在的锁定图仍绑定 v1，发布前必须迁移。
        definePolicy("graph-a", 1,
                coord("app", 1, ""), coord("lib", 1, ""), coord("util", 1, ""));
        assertThatThrownBy(() -> provenanceService.publishLock(rid(), "alice",
                new PublishLockRequest(lock.id())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getViolations()).extracting("reason")
                            .containsExactly("POLICY_VERSION_OUTDATED");
                });
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM release_snapshot", Integer.class))
                .isZero();

        provenanceService.migratePolicies(rid(), "alice", new MigratePoliciesRequest(null,
                List.of(new MigratePoliciesRequest.MigrationTarget(lock.id(), 2))));
        ReleaseSnapshotResponse release = provenanceService.publishLock(rid(), "alice",
                new PublishLockRequest(lock.id()));
        assertThat(release.policyVersion()).isEqualTo(2);
    }

    @Test
    void publishBlockedByCurrentPolicyListsViolationsAndCreatesNoSnapshot() {
        seedGraph();
        definePolicy("graph-a", 0,
                coord("app", 5, ""), coord("lib", 5, ""), coord("util", 5, ""));
        attest("app", 1, "repo-a", "sha-app", 5);
        attest("lib", 2, "repo-a", "sha-lib", 5);
        attest("util", 1, "repo-a", "sha-util", 5);
        LockFileResponse lock = artifactService.createLock(rid(),
                new LockRequest("graph-a", "app", 1, repositoryVersion()));

        // 对 util 提交一条更新但更弱的证明（等级 1）：证明按提交顺序裁决，未发布锁定图命中最新一条。
        attest("util", 1, "repo-a", "sha-util-weak", 1);

        assertThatThrownBy(() -> provenanceService.publishLock(rid(), "alice",
                new PublishLockRequest(lock.id())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getViolations()).extracting("reason")
                            .containsExactly("LEVEL_INSUFFICIENT");
                    assertThat(e.getViolations().get(0).path())
                            .containsExactly("app:1", "lib:2", "util:1");
                });
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM release_snapshot", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM release_snapshot_entry", Integer.class)).isZero();
        // 失败不推进仓库版本、不占幂等键。
        long versionNow = repositoryVersion();
        assertThatThrownBy(() -> provenanceService.publishLock(rid(), "alice",
                new PublishLockRequest(lock.id())))
                .isInstanceOf(ApiException.class);
        assertThat(repositoryVersion()).isEqualTo(versionNow);
    }

    @Test
    void publishWithoutPolicyReturns422() {
        register("app", 1);
        LockFileResponse lock = artifactService.createLock(rid(),
                new LockRequest(null, "app", 1, 1L));
        assertThatThrownBy(() -> provenanceService.publishLock(rid(), "alice",
                new PublishLockRequest(lock.id())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void submitAttestationForMissingArtifactReturns404() {
        assertThatThrownBy(() -> attest("ghost", 9, "repo", "d", 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void revokeMissingAttestationReturns404() {
        assertThatThrownBy(() -> provenanceService.revokeAttestation(rid(), 9999L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void policyTighteningAffectsOnlySubsequentLocks() {
        seedGraph();
        definePolicy("graph-a", 0,
                coord("app", 1, ""), coord("lib", 1, ""), coord("util", 1, ""));
        attest("app", 1, "repo", "d", 1);
        attest("lib", 2, "repo", "d", 1);
        attest("util", 1, "repo", "d", 1);
        LockFileResponse first = artifactService.createLock(rid(),
                new LockRequest("graph-a", "app", 1, repositoryVersion()));

        // 收紧到等级 5；旧锁定图仍绑定 v1，可查询、诊断版本滞后。
        definePolicy("graph-a", 1,
                coord("app", 5, ""), coord("lib", 5, ""), coord("util", 5, ""));
        LockFileResponse refetched = artifactService.getLock(first.id());
        assertThat(refetched.policyVersion()).isEqualTo(1);

        // 后续解析必须满足当前策略 v2：等级不足，422 且不写快照。
        assertThatThrownBy(() -> artifactService.createLock(rid(),
                new LockRequest("graph-a", "app", 1, repositoryVersion())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getViolations()).extracting("reason")
                            .containsOnly("LEVEL_INSUFFICIENT");
                });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file", Integer.class)).isEqualTo(1);
    }

    @Test
    void migrationToNonexistentLockOrPolicyReturns404Or422() {
        assertThatThrownBy(() -> provenanceService.migratePolicies(rid(), "alice",
                new MigratePoliciesRequest(null, List.of(
                        new MigratePoliciesRequest.MigrationTarget(9999L, 1)))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));

        register("app", 1);
        LockFileResponse legacy = artifactService.createLock(rid(),
                new LockRequest(null, "app", 1, 1L));
        // 旧锁定图无策略分组，不能迁移。
        assertThatThrownBy(() -> provenanceService.migratePolicies(rid(), "alice",
                new MigratePoliciesRequest(null, List.of(
                        new MigratePoliciesRequest.MigrationTarget(legacy.id(), 1)))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    // ------------------------------------------------------------------
    // 批量策略迁移：先预校验全部最终命中，原子生效
    // ------------------------------------------------------------------

    @Test
    void batchMigrationAppliesAtomicallyWhenAllTargetsComply() {
        seedGraph();
        definePolicy("graph-a", 0,
                coord("app", 1, ""), coord("lib", 1, ""), coord("util", 1, ""));
        attest("app", 1, "repo", "d", 1);
        attest("lib", 2, "repo", "d", 1);
        attest("util", 1, "repo", "d", 1);
        LockFileResponse lock1 = artifactService.createLock(rid(),
                new LockRequest("graph-a", "app", 1, repositoryVersion()));

        // v2 收紧到等级 5：在证明升级后才能迁移。
        definePolicy("graph-a", 1,
                coord("app", 5, ""), coord("lib", 5, ""), coord("util", 5, ""));
        // 第二张图（同制品集合，不同锁定图名）。
        register("other", 1, dep("app", 1, 1));
        definePolicy("graph-b", 0,
                coord("other", 1, ""), coord("app", 1, ""),
                coord("lib", 1, ""), coord("util", 1, ""));
        attest("other", 1, "repo", "d", 1);
        LockFileResponse lock2 = artifactService.createLock(rid(),
                new LockRequest("graph-b", "other", 1, repositoryVersion()));

        // 升级证明到等级 5。
        attest("app", 1, "repo", "d2", 5);
        attest("lib", 2, "repo", "d2", 5);
        attest("util", 1, "repo", "d2", 5);

        MigrationResponse response = provenanceService.migratePolicies(rid(), "alice",
                new MigratePoliciesRequest(null, List.of(
                        new MigratePoliciesRequest.MigrationTarget(lock1.id(), 2),
                        new MigratePoliciesRequest.MigrationTarget(lock2.id(), 1))));
        assertThat(response.results()).extracting("lockFileId", "policyVersion")
                .containsExactly(tuple(lock1.id(), 2), tuple(lock2.id(), 1));
        assertThat(provenanceService.diagnose(lock1.id()).policyVersion()).isEqualTo(2);
    }

    @Test
    void batchMigrationPrevalidateFailureWritesNoBindings() {
        seedGraph();
        definePolicy("graph-a", 0,
                coord("app", 1, ""), coord("lib", 1, ""), coord("util", 1, ""));
        attest("app", 1, "repo", "d", 1);
        attest("lib", 2, "repo", "d", 1);
        attest("util", 1, "repo", "d", 1);
        LockFileResponse lock1 = artifactService.createLock(rid(),
                new LockRequest("graph-a", "app", 1, repositoryVersion()));
        LockFileResponse lock2 = artifactService.createLock(rid(),
                new LockRequest("graph-a", "app", 1, repositoryVersion()));

        definePolicy("graph-a", 1,
                coord("app", 9, ""), coord("lib", 9, ""), coord("util", 9, ""));

        // 两个目标迁移到 v2 均不合规：整体 422，无绑定写入。
        assertThatThrownBy(() -> provenanceService.migratePolicies(rid(), "alice",
                new MigratePoliciesRequest(null, List.of(
                        new MigratePoliciesRequest.MigrationTarget(lock1.id(), 2),
                        new MigratePoliciesRequest.MigrationTarget(lock2.id(), 2)))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getViolations()).hasSize(6);
                });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT policy_version FROM lock_file WHERE id = ?", Integer.class, lock1.id()))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT policy_version FROM lock_file WHERE id = ?", Integer.class, lock2.id()))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 幂等与并发
    // ------------------------------------------------------------------

    @Test
    void failedPublishDoesNotConsumeRequestId() {
        seedGraph();
        definePolicy("graph-a", 0,
                coord("app", 1, ""), coord("lib", 1, ""), coord("util", 1, ""));
        attest("app", 1, "repo", "d", 1);
        attest("lib", 2, "repo", "d", 1);
        attest("util", 1, "repo", "d", 1);
        LockFileResponse lock = artifactService.createLock(rid(),
                new LockRequest("graph-a", "app", 1, repositoryVersion()));

        // 先让锁图不能发布：撤销 util 证明。
        Long utilId = jdbcTemplate.queryForObject(
                "SELECT id FROM provenance_attestation WHERE name = 'util' AND version = 1 "
                        + "ORDER BY id DESC FETCH FIRST 1 ROWS ONLY", Long.class);
        provenanceService.revokeAttestation(rid(), utilId);

        String publishRid = rid();
        assertThatThrownBy(() -> provenanceService.publishLock(publishRid, "alice",
                new PublishLockRequest(lock.id())))
                .isInstanceOf(ApiException.class);
        // 失败不占键：补回证明后同一 requestId 可成功。
        attest("util", 1, "repo", "d3", 1);
        ReleaseSnapshotResponse release = provenanceService.publishLock(publishRid, "alice",
                new PublishLockRequest(lock.id()));
        assertThat(release.lockFileId()).isEqualTo(lock.id());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM release_snapshot", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestIdPublishProducesSingleSnapshot() throws Exception {
        seedGraph();
        definePolicy("graph-a", 0,
                coord("app", 1, ""), coord("lib", 1, ""), coord("util", 1, ""));
        attest("app", 1, "repo", "d", 1);
        attest("lib", 2, "repo", "d", 1);
        attest("util", 1, "repo", "d", 1);
        LockFileResponse lock = artifactService.createLock(rid(),
                new LockRequest("graph-a", "app", 1, repositoryVersion()));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        String publishRid = rid();

        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Object> task = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return provenanceService.publishLock(publishRid, "alice",
                            new PublishLockRequest(lock.id()));
                } catch (ApiException e) {
                    return e;
                }
            };
            futures.add(pool.submit(task));
        }
        pool.shutdown();

        ReleaseSnapshotResponse first = null;
        for (Future<Object> future : futures) {
            Object outcome = future.get(30, TimeUnit.SECONDS);
            assertThat(outcome).isInstanceOf(ReleaseSnapshotResponse.class);
            ReleaseSnapshotResponse response = (ReleaseSnapshotResponse) outcome;
            if (first == null) {
                first = response;
            } else {
                assertThat(response.id()).isEqualTo(first.id());
                assertThat(response.provenanceKey()).isEqualTo(first.provenanceKey());
            }
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM release_snapshot", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM release_snapshot_entry", Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, publishRid)).isEqualTo(1);
    }

    @Test
    void replayPublishWithSameRequestIdReturnsFrozenSnapshot() {
        seedGraph();
        definePolicy("graph-a", 0,
                coord("app", 1, ""), coord("lib", 1, ""), coord("util", 1, ""));
        attest("app", 1, "repo", "d", 1);
        attest("lib", 2, "repo", "d", 1);
        attest("util", 1, "repo", "d", 1);
        LockFileResponse lock = artifactService.createLock(rid(),
                new LockRequest("graph-a", "app", 1, repositoryVersion()));

        String publishRid = rid();
        ReleaseSnapshotResponse first = provenanceService.publishLock(publishRid, "alice",
                new PublishLockRequest(lock.id()));
        ReleaseSnapshotResponse replay = provenanceService.publishLock(publishRid, "alice",
                new PublishLockRequest(lock.id()));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.provenanceKey()).isEqualTo(first.provenanceKey());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM release_snapshot", Integer.class)).isEqualTo(1);
    }

    @Test
    void provenanceKeyChangesWhenOperatorChangesAndReplayConflicts() {
        seedGraph();
        definePolicy("graph-a", 0,
                coord("app", 1, ""), coord("lib", 1, ""), coord("util", 1, ""));
        attest("app", 1, "repo", "d", 1);
        attest("lib", 2, "repo", "d", 1);
        attest("util", 1, "repo", "d", 1);
        LockFileResponse lock = artifactService.createLock(rid(),
                new LockRequest("graph-a", "app", 1, repositoryVersion()));

        ReleaseSnapshotResponse byAlice = provenanceService.publishLock(rid(), "alice",
                new PublishLockRequest(lock.id()));
        // 已发布锁定图换操作者再发布 → 409。
        assertThatThrownBy(() -> provenanceService.publishLock(rid(), "bob",
                new PublishLockRequest(lock.id())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        // 操作者纳入指纹。
        ReleaseSnapshotResponse byAliceAgainstOtherLock = byAlice;
        assertThat(byAliceAgainstOtherLock.operator()).isEqualTo("alice");
    }
}
