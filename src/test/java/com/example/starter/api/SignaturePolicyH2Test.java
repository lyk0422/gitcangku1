package com.example.starter.api;

import com.example.starter.api.dto.AddSignatureRequest;
import com.example.starter.api.dto.KeyResponse;
import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.PolicyResponse;
import com.example.starter.api.dto.PublishPolicyRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.SignatureResponse;
import com.example.starter.support.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * 签名信任策略、补签/撤销与锁图一致验证的 H2（MODE=MySQL）集成测试：
 * 覆盖主流程、阈值/摘要/撤回失败、事务整体回滚、幂等重放与并发一致性边界。
 */
@SpringBootTest
class SignaturePolicyH2Test {

    @Autowired
    private ArtifactService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_signature");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("DELETE FROM policy_key");
        jdbcTemplate.update("DELETE FROM signature_policy");
        jdbcTemplate.update("DELETE FROM trusted_key");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private long repositoryVersion() {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
    }

    private RegisterArtifactRequest artifact(String name, int version,
                                             com.example.starter.api.dto.DependencySpec... deps) {
        return new RegisterArtifactRequest(name, version, List.of(deps));
    }

    private static com.example.starter.api.dto.DependencySpec dep(String name, int min, int max) {
        return new com.example.starter.api.dto.DependencySpec(name, min, max);
    }

    private String digestOf(String name, int version) {
        return jdbcTemplate.queryForObject(
                "SELECT content_digest FROM artifact WHERE name = ? AND version = ?",
                String.class, name, version);
    }

    private PolicyResponse publishPolicy(long policyVersion, int threshold,
                                         Instant effectiveAt, String... keyIds) {
        return service.publishPolicy(rid(), new PublishPolicyRequest(
                policyVersion, List.of(keyIds), threshold, effectiveAt));
    }

    private void sign(String name, int version, String keyId, String digest) {
        service.addSignature(rid(), name, version, new AddSignatureRequest(keyId, digest));
    }

    // ------------------------------------------------------------------
    // 主流程：策略 + 闭包阈值验证 + 冻结证据
    // ------------------------------------------------------------------

    @Test
    void lockVerifiesEveryClosureNodeAndFreezesEvidence() {
        service.registerArtifact(rid(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(rid(), artifact("lib", 1));
        publishPolicy(1L, 2, Instant.now().minusSeconds(60), "k1", "k2", "k3");

        String appDigest = digestOf("app", 1);
        String libDigest = digestOf("lib", 1);
        // 三个签名、阈值 2：计入字典序最小的合格集合以外仍返回全部合格 keyId。
        sign("app", 1, "k3", appDigest);
        sign("app", 1, "k1", appDigest);
        sign("lib", 1, "k2", libDigest);
        sign("lib", 1, "k1", libDigest);

        long expectedVersion = repositoryVersion();
        LockFileResponse lock = service.createLock(rid(),
                new LockRequest("app", 1, expectedVersion));

        assertThat(lock.repositoryVersion()).isEqualTo(expectedVersion);
        assertThat(lock.entries()).hasSize(2);
        LockEntryResponse appEntry = lock.entries().stream()
                .filter(e -> e.name().equals("app")).findFirst().orElseThrow();
        LockEntryResponse libEntry = lock.entries().stream()
                .filter(e -> e.name().equals("lib")).findFirst().orElseThrow();
        assertThat(appEntry.contentDigest()).isEqualTo(appDigest);
        assertThat(appEntry.policyVersion()).isEqualTo(1L);
        assertThat(appEntry.signatureKeyIds()).containsExactly("k1", "k3");
        assertThat(libEntry.contentDigest()).isEqualTo(libDigest);
        assertThat(libEntry.policyVersion()).isEqualTo(1L);
        assertThat(libEntry.signatureKeyIds()).containsExactly("k1", "k2");
    }

    @Test
    void noEffectivePolicyFreezesDigestWithoutPolicyEvidence() {
        service.registerArtifact(rid(), artifact("app", 1));
        // 策略在未来生效：当前无生效策略。
        publishPolicy(1L, 1, Instant.now().plus(1, ChronoUnit.DAYS), "k1");

        LockFileResponse lock = service.createLock(rid(),
                new LockRequest("app", 1, repositoryVersion()));

        LockEntryResponse entry = lock.entries().getFirst();
        assertThat(entry.contentDigest()).isEqualTo(digestOf("app", 1));
        assertThat(entry.policyVersion()).isNull();
        assertThat(entry.signatureKeyIds()).isEmpty();
    }

    @Test
    void newActivePolicyReplacesOldPolicyForSubsequentLocks() {
        service.registerArtifact(rid(), artifact("app", 1));
        String digest = digestOf("app", 1);
        publishPolicy(1L, 1, Instant.now().minusSeconds(120), "old");
        sign("app", 1, "old", digest);

        LockFileResponse first = service.createLock(rid(),
                new LockRequest("app", 1, repositoryVersion()));
        assertThat(first.entries().getFirst().policyVersion()).isEqualTo(1L);

        // 新版本激活即替代旧版本：旧钥匙不再被信任，新钥匙满足阈值。
        publishPolicy(2L, 1, Instant.now().minusSeconds(30), "new");
        sign("app", 1, "new", digest);

        LockFileResponse second = service.createLock(rid(),
                new LockRequest("app", 1, repositoryVersion()));
        assertThat(second.entries().getFirst().policyVersion()).isEqualTo(2L);
        assertThat(second.entries().getFirst().signatureKeyIds()).containsExactly("new");
        // 历史锁文件不改写。
        assertThat(service.getLock(first.id()).entries().getFirst().policyVersion()).isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // 失败分支
    // ------------------------------------------------------------------

    @Test
    void closureNodeBelowThresholdAbortsWholeLockWith422AndPersistsNothing() {
        service.registerArtifact(rid(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(rid(), artifact("lib", 1));
        publishPolicy(1L, 2, Instant.now().minusSeconds(60), "k1", "k2");
        sign("app", 1, "k1", digestOf("app", 1));
        sign("app", 1, "k2", digestOf("app", 1));
        // lib 只有一把合格签名。
        sign("lib", 1, "k1", digestOf("lib", 1));

        long versionBefore = repositoryVersion();
        String lockRid = rid();
        assertThatThrownBy(() -> service.createLock(lockRid,
                new LockRequest("app", 1, versionBefore)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));

        assertThat(service.listLocks()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_entry", Integer.class))
                .isZero();
        assertThat(repositoryVersion()).isEqualTo(versionBefore);
        // 失败不占键：同 requestId 随后可成功用于另一次锁（补足签名后）。
        sign("lib", 1, "k2", digestOf("lib", 1));
        LockFileResponse retry = service.createLock(lockRid,
                new LockRequest("app", 1, repositoryVersion()));
        assertThat(retry.entries()).hasSize(2);
    }

    @Test
    void digestMismatchSignatureFailsLock422() {
        service.registerArtifact(rid(), artifact("app", 1));
        publishPolicy(1L, 1, Instant.now().minusSeconds(60), "k1");
        // 补签时 digest 与内容不符直接 422，无法写入。
        assertThatThrownBy(() -> sign("app", 1, "k1", "0".repeat(64)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_signature", Integer.class)).isZero();
    }

    @Test
    void revokedKeySignaturesRemainButDoNotCountForNewLocks() {
        service.registerArtifact(rid(), artifact("app", 1));
        publishPolicy(1L, 2, Instant.now().minusSeconds(60), "k1", "k2");
        sign("app", 1, "k1", digestOf("app", 1));

        service.revokeKey(rid(), "k2");
        // 撤销后仍允许追加历史签名（只强制同钥匙一份、摘要一致），但新锁定不得计入。
        SignatureResponse appended = service.addSignature(rid(), "app", 1,
                new AddSignatureRequest("k2", digestOf("app", 1)));
        assertThat(appended.keyId()).isEqualTo("k2");

        // 历史签名保留；k2 已撤销不计入阈值，合格签名仅 1 把，锁定 422。
        assertThat(service.listSignatures("app", 1)).hasSize(2);
        assertThatThrownBy(() -> service.createLock(rid(),
                new LockRequest("app", 1, repositoryVersion())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void withdrawnDependencyNodeFailsLockAndPersistsNothing() {
        service.registerArtifact(rid(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(rid(), artifact("lib", 1));
        publishPolicy(1L, 1, Instant.now().minusSeconds(60), "k1");
        sign("app", 1, "k1", digestOf("app", 1));
        sign("lib", 1, "k1", digestOf("lib", 1));
        service.withdrawArtifact(rid(), "lib", 1);

        // 闭包中的依赖版本已撤回：解析不可行（422）；根版本撤回时为 409。
        long versionBefore = repositoryVersion();
        assertThatThrownBy(() -> service.createLock(rid(),
                new LockRequest("app", 1, versionBefore)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listLocks()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_entry", Integer.class))
                .isZero();
        assertThat(repositoryVersion()).isEqualTo(versionBefore);
    }

    @Test
    void signatureFromKeyNeverTrustedIsKeptButIgnoredByLock() {
        service.registerArtifact(rid(), artifact("app", 1));
        publishPolicy(1L, 1, Instant.now().minusSeconds(60), "k1");
        // 未知名钥匙的签名可以追加，但不被任何策略信任，锁定时忽略。
        sign("app", 1, "stranger", digestOf("app", 1));
        assertThatThrownBy(() -> service.createLock(rid(),
                new LockRequest("app", 1, repositoryVersion())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));

        // 补足受信任钥匙签名后锁定成功，证据只计入 k1。
        sign("app", 1, "k1", digestOf("app", 1));
        LockFileResponse lock = service.createLock(rid(),
                new LockRequest("app", 1, repositoryVersion()));
        assertThat(lock.entries().getFirst().signatureKeyIds()).containsExactly("k1");
    }
    @Test
    void duplicateSignatureBySameKeyRejected409() {
        service.registerArtifact(rid(), artifact("app", 1));
        publishPolicy(1L, 1, Instant.now().minusSeconds(60), "k1");
        sign("app", 1, "k1", digestOf("app", 1));
        assertThatThrownBy(() -> sign("app", 1, "k1", digestOf("app", 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_signature", Integer.class)).isEqualTo(1);
    }

    @Test
    void invalidPolicyRequestsRejected() {
        assertThatThrownBy(() -> publishPolicy(1L, 2, Instant.now(), "k1"))
                .as("m 超过 key 数 → 400")
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        assertThatThrownBy(() -> publishPolicy(1L, 1, Instant.now(),
                "k1", "k2", "k3", "k4", "k5", "k6", "k7", "k8", "k9", "k10", "k11"))
                .as("超过 10 个 keyId → 400")
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        assertThatThrownBy(() -> publishPolicy(1L, 1, Instant.now(), "k1", "k1"))
                .as("keyId 重复 → 400")
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM signature_policy", Integer.class)).isZero();
    }

    @Test
    void duplicatePolicyVersionReturns409AndNonIncreasingVersion422() {
        publishPolicy(2L, 1, Instant.now().minusSeconds(60), "k1");
        assertThatThrownBy(() -> publishPolicy(2L, 1, Instant.now(), "k2"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThatThrownBy(() -> publishPolicy(1L, 1, Instant.now(), "k2"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void revokeUnknownKey404AndDoubleRevoke409() {
        assertThatThrownBy(() -> service.revokeKey(rid(), "ghost"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
        publishPolicy(1L, 1, Instant.now().minusSeconds(60), "k1");
        service.revokeKey(rid(), "k1");
        assertThatThrownBy(() -> service.revokeKey(rid(), "k1"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void policyAndSignatureOperationsReplayWithSameRequestId() {
        String policyRid = rid();
        PublishPolicyRequest policyRequest = new PublishPolicyRequest(
                1L, List.of("k1", "k2"), 2, Instant.now().minusSeconds(60));
        PolicyResponse first = service.publishPolicy(policyRid, policyRequest);
        PolicyResponse replay = service.publishPolicy(policyRid, policyRequest);
        assertThat(replay).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM signature_policy", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request", Integer.class)).isEqualTo(1);

        service.registerArtifact(rid(), artifact("app", 1));
        String signRid = rid();
        AddSignatureRequest signRequest =
                new AddSignatureRequest("k1", digestOf("app", 1));
        SignatureResponse firstSig = service.addSignature(signRid, "app", 1, signRequest);
        SignatureResponse replaySig = service.addSignature(signRid, "app", 1, signRequest);
        assertThat(replaySig).isEqualTo(firstSig);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_signature", Integer.class)).isEqualTo(1);
    }

    @Test
    void sameRequestIdDifferentPolicyParamsReturns409() {
        String r = rid();
        service.publishPolicy(r, new PublishPolicyRequest(
                1L, List.of("k1"), 1, Instant.now().minusSeconds(60)));
        assertThatThrownBy(() -> service.publishPolicy(r, new PublishPolicyRequest(
                2L, List.of("k1"), 1, Instant.now().minusSeconds(60))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void revokeReplaysOriginalResultAndFailedCallDoesNotConsumeKey() {
        publishPolicy(1L, 1, Instant.now().minusSeconds(60), "k1");
        String revokeRid = rid();
        KeyResponse first = service.revokeKey(revokeRid, "k1");
        assertThat(first.revoked()).isTrue();
        // 同参重放：即使钥匙已撤销，仍返回原结果而非 409，且不重复推进版本。
        long versionAfter = repositoryVersion();
        KeyResponse replay = service.revokeKey(revokeRid, "k1");
        assertThat(replay).isEqualTo(first);
        assertThat(repositoryVersion()).isEqualTo(versionAfter);

        // 补签失败（digest 与内容不符，422）不占用 requestId。
        service.registerArtifact(rid(), artifact("other", 1));
        String signRid = rid();
        assertThatThrownBy(() -> service.addSignature(signRid, "other", 1,
                new AddSignatureRequest("k1", "0".repeat(64))))
                .isInstanceOf(ApiException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, signRid)).isZero();
        // 同一 requestId 随后用于同参合法调用之外的成功请求。
        SignatureResponse later = service.addSignature(signRid, "other", 1,
                new AddSignatureRequest("k1", digestOf("other", 1)));
        assertThat(later.name()).isEqualTo("other");
    }

    @Test
    void lockReplayDoesNotReselectSignaturesAfterRevoke() {
        service.registerArtifact(rid(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(rid(), artifact("lib", 1));
        publishPolicy(1L, 1, Instant.now().minusSeconds(60), "k1");
        sign("app", 1, "k1", digestOf("app", 1));
        sign("lib", 1, "k1", digestOf("lib", 1));

        String lockRid = rid();
        LockFileResponse first = service.createLock(lockRid,
                new LockRequest("app", 1, repositoryVersion()));

        // 锁后撤销计入阈值的钥匙：现状已无法新建锁。
        service.revokeKey(rid(), "k1");
        assertThatThrownBy(() -> service.createLock(rid(),
                new LockRequest("app", 1, repositoryVersion())))
                .isInstanceOf(ApiException.class);

        // 同 requestId 重放不重新选签名，原样返回冻结证据。
        LockFileResponse replay = service.createLock(lockRid,
                new LockRequest("app", 1, first.repositoryVersion()));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.entries()).isEqualTo(first.entries());
        assertThat(replay.entries().getFirst().signatureKeyIds()).containsExactly("k1");
    }

    // ------------------------------------------------------------------
    // 并发：锁解析与撤销并发只能引用一个一致状态
    // ------------------------------------------------------------------

    @Test
    void concurrentLockAndRevokeResolveToOneConsistentState() throws Exception {
        service.registerArtifact(rid(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(rid(), artifact("lib", 1));
        publishPolicy(1L, 2, Instant.now().minusSeconds(60), "k1", "k2");
        sign("app", 1, "k1", digestOf("app", 1));
        sign("app", 1, "k2", digestOf("app", 1));
        sign("lib", 1, "k1", digestOf("lib", 1));
        sign("lib", 1, "k2", digestOf("lib", 1));

        long versionBefore = repositoryVersion();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        String lockRid = rid();

        Callable<Object> lockTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return service.createLock(lockRid,
                        new LockRequest("app", 1, versionBefore));
            } catch (ApiException e) {
                return e;
            }
        };
        Callable<Object> revokeTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.revokeKey(rid(), "k2");
        };

        List<Future<Object>> futures = pool.invokeAll(List.of(lockTask, revokeTask));
        pool.shutdown();
        Object lockOutcome = futures.get(0).get(30, TimeUnit.SECONDS);
        Object revokeOutcome = futures.get(1).get(30, TimeUnit.SECONDS);

        assertThat(revokeOutcome).isInstanceOf(KeyResponse.class);
        if (lockOutcome instanceof LockFileResponse lock) {
            // 锁先提交：冻结两把未撤销钥匙，撤销后锁文件不改写。
            assertThat(lock.entries()).allSatisfy(e ->
                    assertThat(e.signatureKeyIds()).containsExactly("k1", "k2"));
            assertThat(service.getLock(lock.id()).entries()).isEqualTo(lock.entries());
        } else {
            // 撤销先提交：仓库版本推进，期望版本过期 → 409，无半成品锁。
            assertThat(lockOutcome).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(409));
            assertThat(service.listLocks()).isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // 只读证据查询与数据库约束
    // ------------------------------------------------------------------

    @Test
    void readOnlyEvidenceQueriesExposePoliciesKeysAndSignatures() {
        publishPolicy(1L, 1, Instant.now().minusSeconds(60), "k1", "k2");
        service.registerArtifact(rid(), artifact("app", 1));
        sign("app", 1, "k1", digestOf("app", 1));

        List<PolicyResponse> policies = service.listPolicies();
        assertThat(policies).extracting("policyVersion", "thresholdM")
                .containsExactly(tuple(1L, 1));
        assertThat(policies.getFirst().keyIds()).containsExactly("k1", "k2");
        assertThat(service.getPolicy(1L).keyIds()).containsExactly("k1", "k2");
        assertThatThrownBy(() -> service.getPolicy(99L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));

        List<KeyResponse> keys = service.listKeys();
        assertThat(keys).extracting("keyId", "revoked")
                .containsExactly(tuple("k1", false), tuple("k2", false));
        assertThat(service.getKey("k1").createdAt()).isNotNull();
        assertThatThrownBy(() -> service.getKey("ghost"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));

        List<SignatureResponse> signatures = service.listSignatures("app", 1);
        assertThat(signatures).hasSize(1);
        assertThat(signatures.getFirst().keyId()).isEqualTo("k1");
        assertThat(signatures.getFirst().digest()).isEqualTo(digestOf("app", 1));
    }

    @Test
    void databaseEnforcesPolicyKeyAndSignatureUniqueness() {
        service.registerArtifact(rid(), artifact("app", 1));
        publishPolicy(1L, 1, Instant.now().minusSeconds(60), "k1");

        Long artifactId = jdbcTemplate.queryForObject(
                "SELECT id FROM artifact WHERE name = 'app' AND version = 1", Long.class);
        jdbcTemplate.update("INSERT INTO artifact_signature (artifact_id, key_id, digest, created_at) "
                + "VALUES (?, 'k1', ?, CURRENT_TIMESTAMP(6))", artifactId, digestOf("app", 1));
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO artifact_signature (artifact_id, key_id, digest, created_at) "
                        + "VALUES (?, 'k1', ?, CURRENT_TIMESTAMP(6))",
                artifactId, digestOf("app", 1)))
                .isInstanceOf(DuplicateKeyException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO policy_key (policy_version, key_id, ordinal) VALUES (1, 'k1', 9)"))
                .isInstanceOf(DuplicateKeyException.class);
    }
}
