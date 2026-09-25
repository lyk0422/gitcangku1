package com.example.starter.api;

import com.example.starter.api.dto.AttestationRequest;
import com.example.starter.api.dto.AttestationResponse;
import com.example.starter.api.dto.CreatePolicyRequest;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.MigrationCheckRequest;
import com.example.starter.api.dto.MigrationCheckResponse;
import com.example.starter.api.dto.PolicyResponse;
import com.example.starter.api.dto.ProvenanceResponse;
import com.example.starter.api.dto.PublishDiagnosticResponse;
import com.example.starter.api.dto.PublishRequest;
import com.example.starter.api.dto.PublishResponse;
import com.example.starter.api.dto.RegisterArtifactRequest;
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
 * 制品来源证明策略的 H2（MODE=MySQL）数据库与业务测试：
 * 覆盖证明作用域、锁定图解析门禁、策略版本、发布快照固化、撤销语义、
 * 批量迁移预校验以及并发与幂等边界。
 */
@SpringBootTest
class ProvenanceServiceH2Test {

    private static final String DIGEST_APP = "a".repeat(64);
    private static final String DIGEST_LIB = "b".repeat(64);
    private static final String DIGEST_UTIL = "c".repeat(64);
    private static final String DIGEST_OTHER = "d".repeat(64);

    @Autowired
    private ArtifactService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM publish_entry");
        jdbcTemplate.update("DELETE FROM publish_record");
        jdbcTemplate.update("DELETE FROM attestation");
        jdbcTemplate.update("DELETE FROM provenance_policy_repo");
        jdbcTemplate.update("DELETE FROM provenance_policy");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }

    private static DependencySpec dep(String name, int min, int max) {
        return new DependencySpec(name, min, max);
    }

    /** 登记 app:1 -> lib[1,2]、util[1,1]；lib:1/lib:2；util:1，均带构建摘要。仓库版本推进到 4。 */
    private void registerGraph() {
        service.registerArtifact(requestId(), new RegisterArtifactRequest(
                "app", 1, List.of(dep("lib", 1, 2), dep("util", 1, 1)), DIGEST_APP));
        service.registerArtifact(requestId(), new RegisterArtifactRequest(
                "lib", 1, List.of(), DIGEST_LIB));
        service.registerArtifact(requestId(), new RegisterArtifactRequest(
                "lib", 2, List.of(), DIGEST_LIB));
        service.registerArtifact(requestId(), new RegisterArtifactRequest(
                "util", 1, List.of(), DIGEST_UTIL));
    }

    private PolicyResponse createPolicy(int minLevel, String... repos) {
        return service.createPolicyVersion(requestId(),
                new CreatePolicyRequest(minLevel, List.of(repos)));
    }

    private AttestationResponse attest(String name, int version, String repo, String digest, int level) {
        return service.attest(requestId(), new AttestationRequest(name, version, repo, digest, level));
    }

    /** 为解析命中坐标（app:1、lib:2、util:1）登记满足策略的证明。 */
    private void attestSolution(String repo, int level) {
        attest("app", 1, repo, DIGEST_APP, level);
        attest("lib", 2, repo, DIGEST_LIB, level);
        attest("util", 1, repo, DIGEST_UTIL, level);
    }

    private LockFileResponse lockGraph() {
        return service.createLock(requestId(), new LockRequest("app", 1, 4L));
    }

    // ------------------------------------------------------------------
    // 策略版本
    // ------------------------------------------------------------------

    @Test
    void policyVersionsAreAppendOnlyAndCanonicallySorted() {
        PolicyResponse v1 = service.createPolicyVersion(requestId(),
                new CreatePolicyRequest(1, List.of("central", "backup", "central")));
        assertThat(v1.version()).isEqualTo(1);
        // 制品集合规范排序并去重。
        assertThat(v1.allowedRepos()).containsExactly("backup", "central");

        PolicyResponse v2 = createPolicy(3, "central");
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.minLevel()).isEqualTo(3);

        // 历史版本不被新版本原地改写。
        List<PolicyResponse> policies = service.listPolicies();
        assertThat(policies).extracting("version", "minLevel")
                .containsExactly(tuple(1, 1), tuple(2, 3));
        assertThat(policies.get(0).allowedRepos()).containsExactly("backup", "central");
    }

    @Test
    void policyCreationIsIdempotentAndRejectsParamMismatch() {
        String rid = requestId();
        PolicyResponse first = service.createPolicyVersion(rid, new CreatePolicyRequest(1, List.of("central")));
        PolicyResponse replay = service.createPolicyVersion(rid, new CreatePolicyRequest(1, List.of("central")));
        assertThat(replay.version()).isEqualTo(first.version());
        assertThat(service.listPolicies()).hasSize(1);

        assertThatThrownBy(() -> service.createPolicyVersion(rid, new CreatePolicyRequest(2, List.of("central"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    // ------------------------------------------------------------------
    // 证明作用域与解析门禁
    // ------------------------------------------------------------------

    @Test
    void lockResolutionPassesWhenAllHitCoordinatesSatisfyPolicy() {
        registerGraph();
        createPolicy(2, "central");
        // lib:1 不在最终命中集合内，无需证明。
        attestSolution("central", 2);

        LockFileResponse lock = lockGraph();
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 2), tuple("util", 1));
    }

    @Test
    void missingAttestationBlocksLockAndListsFullPath() {
        registerGraph();
        createPolicy(2, "central");
        attest("app", 1, "central", DIGEST_APP, 2);
        attest("util", 1, "central", DIGEST_UTIL, 2);
        // lib:2 缺证明。

        assertThatThrownBy(this::lockGraph)
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("POLICY_VIOLATION");
                    assertThat(e.getMessage()).contains("MISSING_ATTESTATION")
                            .contains("app:1>lib:2");
                });
        // 整次解析快照不写入。
        assertThat(service.listLocks()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_entry", Integer.class)).isZero();
    }

    @Test
    void digestMismatchBlocksLockWithDistinguishableCode() {
        registerGraph();
        createPolicy(2, "central");
        attest("app", 1, "central", DIGEST_APP, 2);
        attest("util", 1, "central", DIGEST_UTIL, 2);
        // lib:2 证明摘要与登记摘要不一致。
        attest("lib", 2, "central", DIGEST_OTHER, 2);

        assertThatThrownBy(this::lockGraph)
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("DIGEST_MISMATCH").contains("lib:2");
                });
        assertThat(service.listLocks()).isEmpty();
    }

    @Test
    void insufficientLevelAndDisallowedRepoBlockLock() {
        registerGraph();
        createPolicy(3, "central");
        attest("app", 1, "central", DIGEST_APP, 3);
        attest("util", 1, "central", DIGEST_UTIL, 3);
        // 等级不足。
        attest("lib", 2, "central", DIGEST_LIB, 1);

        assertThatThrownBy(this::lockGraph)
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("LEVEL_INSUFFICIENT");
                });

        // 来源仓不在允许集合内（等级足够）。
        service.revokeAttestation(requestId(), "lib", 2);
        attest("lib", 2, "mirror", DIGEST_LIB, 5);
        assertThatThrownBy(this::lockGraph)
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("REPO_NOT_ALLOWED");
                });
        assertThat(service.listLocks()).isEmpty();
    }

    @Test
    void transitiveMissingAttestationListsDeepestPath() {
        // util:1 -> lib[2,2]；app:1 -> util[1,1]：lib:2 是传递依赖。
        service.registerArtifact(requestId(), new RegisterArtifactRequest(
                "app", 1, List.of(dep("util", 1, 1)), DIGEST_APP));
        service.registerArtifact(requestId(), new RegisterArtifactRequest(
                "util", 1, List.of(dep("lib", 2, 2)), DIGEST_UTIL));
        service.registerArtifact(requestId(), new RegisterArtifactRequest(
                "lib", 2, List.of(), DIGEST_LIB));
        createPolicy(1, "central");
        attest("app", 1, "central", DIGEST_APP, 1);
        attest("util", 1, "central", DIGEST_UTIL, 1);

        assertThatThrownBy(() -> service.createLock(requestId(), new LockRequest("app", 1, 3L)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("app:1>util:1>lib:2");
                });
    }

    // ------------------------------------------------------------------
    // 证明版本与撤销
    // ------------------------------------------------------------------

    @Test
    void attestationVersionsIncrementAndRevokeRequiresReAttestation() {
        registerGraph();
        AttestationResponse first = attest("lib", 2, "central", DIGEST_LIB, 1);
        assertThat(first.attestationVersion()).isEqualTo(1);
        AttestationResponse second = attest("lib", 2, "central", DIGEST_LIB, 2);
        assertThat(second.attestationVersion()).isEqualTo(2);

        AttestationResponse revoked = service.revokeAttestation(requestId(), "lib", 2);
        assertThat(revoked.revoked()).isTrue();
        assertThat(revoked.attestationVersion()).isEqualTo(2);

        // 重复撤销 409；撤销不存在的证明 404。
        assertThatThrownBy(() -> service.revokeAttestation(requestId(), "lib", 2))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThatThrownBy(() -> service.revokeAttestation(requestId(), "ghost", 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));

        // 同坐标须重新证明：新版本号递增。
        AttestationResponse third = attest("lib", 2, "central", DIGEST_LIB, 3);
        assertThat(third.attestationVersion()).isEqualTo(3);
        assertThat(third.revoked()).isFalse();
    }

    @Test
    void attestRequiresExistingArtifactAndValidDigest() {
        assertThatThrownBy(() -> attest("ghost", 1, "central", DIGEST_APP, 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
        service.registerArtifact(requestId(), new RegisterArtifactRequest("app", 1, List.of()));
        assertThatThrownBy(() -> attest("app", 1, "central", "not-a-digest", 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        assertThatThrownBy(() -> service.registerArtifact(requestId(),
                new RegisterArtifactRequest("bad", 1, List.of(), "xyz")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void revokedAttestationBlocksUnpublishedLockButNotPublishedSnapshot() {
        registerGraph();
        createPolicy(2, "central");
        attestSolution("central", 2);
        LockFileResponse published = lockGraph();
        PublishResponse publish = service.publishLock(published.id(), new PublishRequest("op-1"));
        assertThat(publish.policyVersion()).isEqualTo(1);

        LockFileResponse unpublished = service.createLock(requestId(), new LockRequest("app", 1, 4L));

        // 撤销 lib:2 证明：未发布锁定图不可发布，重新解析也被阻断。
        service.revokeAttestation(requestId(), "lib", 2);
        assertThatThrownBy(() -> service.publishLock(unpublished.id(), new PublishRequest("op-2")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("ATTESTATION_REVOKED").contains("lib:2");
                });
        assertThatThrownBy(() -> service.createLock(requestId(), new LockRequest("app", 1, 4L)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("ATTESTATION_REVOKED");
                });

        // 已发布快照不倒改：来源查询仍返回冻结数据。
        ProvenanceResponse frozen = service.getProvenance(published.id());
        assertThat(frozen.published()).isTrue();
        assertThat(frozen.policyVersion()).isEqualTo(1);
        assertThat(frozen.entries()).allSatisfy(entry -> {
            assertThat(entry.status()).isEqualTo("OK");
            assertThat(entry.attestationVersion()).isNotNull();
        });
        // 未发布锁定图反映当前状态。
        ProvenanceResponse current = service.getProvenance(unpublished.id());
        assertThat(current.published()).isFalse();
        assertThat(current.entries())
                .filteredOn(entry -> entry.name().equals("lib"))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.status()).isEqualTo("ATTESTATION_REVOKED");
                    assertThat(entry.revoked()).isTrue();
                });
    }

    // ------------------------------------------------------------------
    // 策略收紧与发布快照
    // ------------------------------------------------------------------

    @Test
    void policyTighteningAffectsOnlyLaterResolutionAndPublish() {
        registerGraph();
        createPolicy(1, "central");
        attestSolution("central", 1);
        LockFileResponse lock = lockGraph();
        PublishResponse publish = service.publishLock(lock.id(), new PublishRequest("op-1"));
        assertThat(publish.policyVersion()).isEqualTo(1);

        // 收紧策略：等级下限提高到 5。
        createPolicy(5, "central");

        // 后续解析被阻断。
        assertThatThrownBy(() -> service.createLock(requestId(), new LockRequest("app", 1, 4L)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("LEVEL_INSUFFICIENT");
                });
        // 已发布快照固化策略版本 1，不倒改。
        ProvenanceResponse frozen = service.getProvenance(lock.id());
        assertThat(frozen.published()).isTrue();
        assertThat(frozen.policyVersion()).isEqualTo(1);
        // 发布阻断诊断按当前策略版本评估，列出可区分原因。
        PublishDiagnosticResponse diagnostic = service.getPublishDiagnostic(lock.id());
        assertThat(diagnostic.published()).isTrue();
        assertThat(diagnostic.currentPolicyVersion()).isEqualTo(2);
        assertThat(diagnostic.violations()).isNotEmpty()
                .allSatisfy(v -> assertThat(v.code()).isEqualTo("LEVEL_INSUFFICIENT"));
    }

    @Test
    void publishFreezesAttestationVersionsInSnapshot() {
        registerGraph();
        createPolicy(1, "central");
        attestSolution("central", 1);
        LockFileResponse lock = lockGraph();

        PublishResponse publish = service.publishLock(lock.id(), new PublishRequest("op-1"));
        assertThat(publish.entries()).extracting("name", "attestationVersion")
                .containsExactly(tuple("app", 1), tuple("lib", 1), tuple("util", 1));

        // 重新证明（证明版本前进）不影响已发布快照。
        attest("lib", 2, "central", DIGEST_LIB, 1);
        ProvenanceResponse frozen = service.getProvenance(lock.id());
        assertThat(frozen.entries())
                .filteredOn(entry -> entry.name().equals("lib"))
                .singleElement()
                .satisfies(entry -> assertThat(entry.attestationVersion()).isEqualTo(1));
    }

    // ------------------------------------------------------------------
    // 发布幂等与并发
    // ------------------------------------------------------------------

    @Test
    void publishSameProvenanceKeyReplaysAndFailureConsumesNoKey() {
        registerGraph();
        createPolicy(2, "central");
        attestSolution("central", 2);
        LockFileResponse lock = lockGraph();

        // 收紧策略使发布失败：失败不占键。
        createPolicy(5, "central");
        assertThatThrownBy(() -> service.publishLock(lock.id(), new PublishRequest("op-1")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM publish_record", Integer.class))
                .isZero();

        // 提升证明等级满足新策略后，同操作者发布成功。
        attestSolution("central", 5);
        PublishResponse first = service.publishLock(lock.id(), new PublishRequest("op-1"));
        PublishResponse replay = service.publishLock(lock.id(), new PublishRequest("op-1"));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.provenanceKey()).isEqualTo(first.provenanceKey());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM publish_record", Integer.class))
                .isEqualTo(1);

        // 不同操作者产生不同指纹，是新的发布。
        PublishResponse other = service.publishLock(lock.id(), new PublishRequest("op-2"));
        assertThat(other.provenanceKey()).isNotEqualTo(first.provenanceKey());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM publish_record", Integer.class))
                .isEqualTo(2);
    }

    private LockFileResponse lockGraphWithoutAttestations() {
        return service.createLock(requestId(), new LockRequest("app", 1, 4L));
    }

    @Test
    void publishWithoutPolicyReturns422() {
        registerGraph();
        LockFileResponse lock = lockGraphWithoutAttestations();
        assertThatThrownBy(() -> service.publishLock(lock.id(), new PublishRequest("op-1")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("尚未定义来源策略");
                });
    }

    @Test
    void publishOnMissingLockReturns404() {
        assertThatThrownBy(() -> service.publishLock(9999L, new PublishRequest("op-1")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
        assertThatThrownBy(() -> service.getProvenance(9999L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
        assertThatThrownBy(() -> service.getPublishDiagnostic(9999L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void concurrentPublishSameKeyResolvesToSingleRecord() throws Exception {
        registerGraph();
        createPolicy(2, "central");
        attestSolution("central", 2);
        LockFileResponse lock = lockGraph();

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<PublishResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.publishLock(lock.id(), new PublishRequest("op-1"));
            }));
        }
        pool.shutdown();

        PublishResponse first = futures.get(0).get(30, TimeUnit.SECONDS);
        for (Future<PublishResponse> future : futures) {
            PublishResponse response = future.get(30, TimeUnit.SECONDS);
            assertThat(response.id()).isEqualTo(first.id());
            assertThat(response.provenanceKey()).isEqualTo(first.provenanceKey());
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM publish_record", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM publish_entry", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void concurrentAttestSameRequestIdResolvesToSingleVersion() throws Exception {
        registerGraph();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        String rid = requestId();
        AttestationRequest request = new AttestationRequest("lib", 2, "central", DIGEST_LIB, 1);

        List<Future<AttestationResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.attest(rid, request);
            }));
        }
        pool.shutdown();

        AttestationResponse first = futures.get(0).get(30, TimeUnit.SECONDS);
        for (Future<AttestationResponse> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS).attestationVersion())
                    .isEqualTo(first.attestationVersion());
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM attestation WHERE name = 'lib' AND version = 2", Integer.class))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 批量迁移预校验与诊断查询
    // ------------------------------------------------------------------

    @Test
    void migrationCheckEvaluatesAllLocksWithoutWrites() {
        registerGraph();
        createPolicy(1, "central");
        attestSolution("central", 1);
        LockFileResponse lock = lockGraph();
        service.publishLock(lock.id(), new PublishRequest("op-1"));
        long publishCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM publish_record", Long.class);
        long policyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM provenance_policy", Long.class);

        // 候选策略：提高等级下限并排除 central 仓。
        MigrationCheckResponse response = service.checkMigration(
                new MigrationCheckRequest(9, List.of("other-repo")));

        assertThat(response.minLevel()).isEqualTo(9);
        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).lockFileId()).isEqualTo(lock.id());
        // 当前证明等级 1 < 9 且仓库 central 不被允许：每个坐标都有违规，原因可区分。
        assertThat(response.results().get(0).violations())
                .extracting("code")
                .containsOnly("REPO_NOT_ALLOWED");
        assertThat(response.results().get(0).violations())
                .allSatisfy(v -> assertThat(v.path()).startsWith("app:1"));

        // 预校验只读：不产生任何写入。
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM publish_record", Long.class))
                .isEqualTo(publishCount);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM provenance_policy", Long.class))
                .isEqualTo(policyCount);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM idempotent_request "
                + "WHERE operation = 'CREATE_POLICY'", Long.class)).isEqualTo(1L);
    }

    @Test
    void publishDiagnosticListsDistinguishableReasonsPerCoordinate() {
        registerGraph();
        createPolicy(2, "central");
        // 先让全部坐标满足策略完成锁定。
        attest("app", 1, "central", DIGEST_APP, 2);
        attest("lib", 2, "central", DIGEST_OTHER, 2);
        attest("util", 1, "central", DIGEST_UTIL, 2);
        service.revokeAttestation(requestId(), "lib", 2);
        attest("lib", 2, "central", DIGEST_LIB, 2);
        LockFileResponse lock = lockGraph();

        // 制造违规：撤销 app 证明；lib 重新证明为错误摘要；收紧等级到 5。
        service.revokeAttestation(requestId(), "app", 1);
        service.revokeAttestation(requestId(), "lib", 2);
        attest("lib", 2, "central", DIGEST_OTHER, 2);
        createPolicy(5, "central");

        PublishDiagnosticResponse diagnostic = service.getPublishDiagnostic(lock.id());
        assertThat(diagnostic.published()).isFalse();
        assertThat(diagnostic.currentPolicyVersion()).isEqualTo(2);
        assertThat(diagnostic.violations()).extracting("code", "coordinate")
                .containsExactlyInAnyOrder(
                        tuple("ATTESTATION_REVOKED", "app:1"),
                        tuple("DIGEST_MISMATCH", "lib:2"),
                        tuple("LEVEL_INSUFFICIENT", "util:1"));
        assertThat(diagnostic.violations())
                .allSatisfy(v -> assertThat(v.path()).startsWith("app:1"));
    }

    @Test
    void provenanceQueryWithoutPolicyReturnsCurrentState() {
        registerGraph();
        LockFileResponse lock = lockGraphWithoutAttestations();
        ProvenanceResponse response = service.getProvenance(lock.id());
        assertThat(response.published()).isFalse();
        assertThat(response.policyVersion()).isNull();
        assertThat(response.entries()).hasSize(3)
                .allSatisfy(entry -> {
                    assertThat(entry.status()).isEqualTo("OK");
                    assertThat(entry.attestationVersion()).isNull();
                    assertThat(entry.path()).isNotBlank();
                });
        assertThat(response.entries())
                .filteredOn(entry -> entry.name().equals("util"))
                .singleElement()
                .satisfies(entry -> assertThat(entry.path()).isEqualTo("app:1>util:1"));
    }
}
