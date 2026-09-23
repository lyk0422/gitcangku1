package com.example.starter.api;

import com.example.starter.api.dto.AddSignatureRequest;
import com.example.starter.api.dto.DependencySpec;
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
 * 签名信任策略、补签、钥匙撤销与锁图一致验证的 H2（MODE=MySQL）集成测试：
 * 覆盖主流程、失败分支、整体回滚、幂等重放及真实并发边界。
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
        jdbcTemplate.update("DELETE FROM signing_policy_key");
        jdbcTemplate.update("DELETE FROM signing_policy");
        jdbcTemplate.update("DELETE FROM signing_key");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }

    private static DependencySpec dep(String name, int min, int max) {
        return new DependencySpec(name, min, max);
    }

    private RegisterArtifactRequest artifact(String name, int version, DependencySpec... deps) {
        return new RegisterArtifactRequest(name, version, null, List.of(deps));
    }

    private RegisterArtifactRequest artifactWithDigest(String name, int version, String digest,
                                                       DependencySpec... deps) {
        return new RegisterArtifactRequest(name, version, digest, List.of(deps));
    }

    private String digestOf(String name, int version) {
        return jdbcTemplate.queryForObject(
                "SELECT content_digest FROM artifact WHERE name = ? AND version = ?",
                String.class, name, version);
    }

    private long repositoryVersion() {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
    }

    private PublishPolicyRequest policy(long policyVersion, int threshold, Instant effectiveAt,
                                        String... keyIds) {
        return new PublishPolicyRequest(policyVersion, List.of(keyIds), threshold, effectiveAt);
    }

    private PublishPolicyRequest activePolicy(long policyVersion, int threshold, String... keyIds) {
        return policy(policyVersion, threshold, Instant.now().minusSeconds(60), keyIds);
    }

    private void publishActivePolicy(long policyVersion, int threshold, String... keyIds) {
        service.publishPolicy(requestId(), activePolicy(policyVersion, threshold, keyIds));
    }

    private SignatureResponse sign(String name, int version, String keyId) {
        return service.addSignature(requestId(), name, version,
                new AddSignatureRequest(keyId, digestOf(name, version)));
    }

    // ------------------------------------------------------------------
    // 策略发布主流程与失败分支
    // ------------------------------------------------------------------

    @Test
    void publishPolicyRegistersKeysAndAdvancesRepositoryVersion() {
        long before = repositoryVersion();
        PolicyResponse response = service.publishPolicy(requestId(),
                activePolicy(1L, 2, "k3", "k1", "k2"));

        assertThat(response.policyVersion()).isEqualTo(1L);
        assertThat(response.threshold()).isEqualTo(2);
        // keyIds 规范化为字典序升序去重。
        assertThat(response.keyIds()).containsExactly("k1", "k2", "k3");
        assertThat(repositoryVersion()).isEqualTo(before + 1);
        // 随策略发布自动登记钥匙，初始未撤销。
        assertThat(service.getKey("k1").revoked()).isFalse();
        assertThat(service.listPolicies()).hasSize(1);
    }

    @Test
    void effectivePolicyPicksHighestVersionWhoseEffectiveTimeHasArrived() {
        service.publishPolicy(requestId(),
                policy(1L, 1, Instant.now().minusSeconds(3600), "k1"));
        // v2 尚未生效，当前生效策略仍是 v1。
        service.publishPolicy(requestId(),
                policy(2L, 2, Instant.now().plusSeconds(3600), "k1", "k2"));
        assertThat(service.getEffectivePolicy().policyVersion()).isEqualTo(1L);

        // v3 立即生效，替代旧版本。
        service.publishPolicy(requestId(), activePolicy(3L, 1, "k9"));
        assertThat(service.getEffectivePolicy().policyVersion()).isEqualTo(3L);
    }

    @Test
    void duplicatePolicyVersionReturns409() {
        service.publishPolicy(requestId(), activePolicy(1L, 1, "k1"));
        assertThatThrownBy(() -> service.publishPolicy(requestId(), activePolicy(1L, 1, "k2")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void nonMonotonicPolicyVersionReturns422AndPersistsNothing() {
        service.publishPolicy(requestId(), activePolicy(2L, 1, "k1"));
        long versionBefore = repositoryVersion();
        assertThatThrownBy(() -> service.publishPolicy(requestId(), activePolicy(1L, 1, "k2")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        // 整体回滚：新钥匙未登记，仓库版本不动。
        assertThatThrownBy(() -> service.getKey("k2"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
        assertThat(repositoryVersion()).isEqualTo(versionBefore);
        assertThat(service.listPolicies()).hasSize(1);
    }

    @Test
    void thresholdGreaterThanKeyCountReturns400() {
        assertThatThrownBy(() -> service.publishPolicy(requestId(), activePolicy(1L, 3, "k1", "k2")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void duplicateOrEmptyKeyIdsReturn400() {
        assertThatThrownBy(() -> service.publishPolicy(requestId(), activePolicy(1L, 1, "k1", "k1")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        assertThatThrownBy(() -> service.publishPolicy(requestId(),
                new PublishPolicyRequest(1L, List.of(), 1, Instant.now())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        assertThatThrownBy(() -> service.publishPolicy(requestId(),
                new PublishPolicyRequest(1L, List.of("  "), 1, Instant.now())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void policyVersionUniqueConstraintEnforcedByDatabase() {
        service.publishPolicy(requestId(), activePolicy(5L, 1, "k1"));
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO signing_policy (policy_version, threshold, effective_at, request_id, created_at) "
                        + "VALUES (5, 1, CURRENT_TIMESTAMP(6), 'rid-other', CURRENT_TIMESTAMP(6))"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    // ------------------------------------------------------------------
    // 钥匙撤销
    // ------------------------------------------------------------------

    @Test
    void revokeKeyMarksRevokedAndAdvancesVersion() {
        service.publishPolicy(requestId(), activePolicy(1L, 1, "k1"));
        long before = repositoryVersion();
        KeyResponse response = service.revokeKey(requestId(), "k1");
        assertThat(response.revoked()).isTrue();
        assertThat(response.revokedAt()).isNotNull();
        assertThat(repositoryVersion()).isEqualTo(before + 1);
        assertThat(service.getKey("k1").revoked()).isTrue();
    }

    @Test
    void revokeUnknownKeyReturns404() {
        assertThatThrownBy(() -> service.revokeKey(requestId(), "ghost"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void doubleRevokeReturns409AndDoesNotAdvanceVersion() {
        service.publishPolicy(requestId(), activePolicy(1L, 1, "k1"));
        service.revokeKey(requestId(), "k1");
        long versionAfterFirst = repositoryVersion();
        assertThatThrownBy(() -> service.revokeKey(requestId(), "k1"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(repositoryVersion()).isEqualTo(versionAfterFirst);
    }

    // ------------------------------------------------------------------
    // 补签主流程与失败分支
    // ------------------------------------------------------------------

    @Test
    void addSignatureWithMatchingDigestPersistsAndIsQueryable() {
        service.registerArtifact(requestId(), artifact("app", 1));
        service.publishPolicy(requestId(), activePolicy(1L, 1, "k1"));
        long before = repositoryVersion();

        SignatureResponse response = sign("app", 1, "k1");
        assertThat(response.keyId()).isEqualTo("k1");
        assertThat(response.digest()).isEqualTo(digestOf("app", 1));
        assertThat(repositoryVersion()).isEqualTo(before + 1);

        List<SignatureResponse> evidence = service.listSignatures("app", 1);
        assertThat(evidence).extracting("name", "version", "keyId")
                .containsExactly(tuple("app", 1, "k1"));
    }

    @Test
    void signatureDigestMismatchReturns422AndPersistsNothing() {
        service.registerArtifact(requestId(), artifact("app", 1));
        service.publishPolicy(requestId(), activePolicy(1L, 1, "k1"));
        long before = repositoryVersion();

        assertThatThrownBy(() -> service.addSignature(requestId(), "app", 1,
                new AddSignatureRequest("k1", "f".repeat(64))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listSignatures("app", 1)).isEmpty();
        assertThat(repositoryVersion()).isEqualTo(before);
    }

    @Test
    void signingWithUnknownArtifactOrKeyReturns404() {
        service.registerArtifact(requestId(), artifact("app", 1));
        service.publishPolicy(requestId(), activePolicy(1L, 1, "k1"));
        String digest = digestOf("app", 1);

        assertThatThrownBy(() -> service.addSignature(requestId(), "ghost", 1,
                new AddSignatureRequest("k1", digest)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
        assertThatThrownBy(() -> service.addSignature(requestId(), "app", 1,
                new AddSignatureRequest("ghost-key", digest)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void signingWithRevokedKeyReturns409() {
        service.registerArtifact(requestId(), artifact("app", 1));
        service.publishPolicy(requestId(), activePolicy(1L, 1, "k1"));
        service.revokeKey(requestId(), "k1");

        assertThatThrownBy(() -> sign("app", 1, "k1"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(service.listSignatures("app", 1)).isEmpty();
    }

    @Test
    void sameKeyCanOnlySignArtifactVersionOnceReturns409() {
        service.registerArtifact(requestId(), artifact("app", 1));
        service.publishPolicy(requestId(), activePolicy(1L, 1, "k1"));
        sign("app", 1, "k1");

        assertThatThrownBy(() -> sign("app", 1, "k1"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(service.listSignatures("app", 1)).hasSize(1);
    }

    @Test
    void signatureUniqueConstraintsEnforcedByDatabase() {
        service.registerArtifact(requestId(), artifact("app", 1));
        service.publishPolicy(requestId(), activePolicy(1L, 1, "k1", "k2"));
        sign("app", 1, "k1");
        long artifactId = jdbcTemplate.queryForObject(
                "SELECT id FROM artifact WHERE name = 'app' AND version = 1", Long.class);
        String digest = digestOf("app", 1);

        // signatureKey 全局唯一。
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO artifact_signature (artifact_id, signature_key, key_id, digest, request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, 'rid-x', CURRENT_TIMESTAMP(6))",
                artifactId, "app/1/k1", "k2", digest))
                .isInstanceOf(DuplicateKeyException.class);
        // 同制品同钥匙唯一（构造不同 signatureKey 触发第二条唯一索引）。
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO artifact_signature (artifact_id, signature_key, key_id, digest, request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, 'rid-y', CURRENT_TIMESTAMP(6))",
                artifactId, "other-composite-key", "k1", digest))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void listSignaturesOnUnknownArtifactReturns404() {
        assertThatThrownBy(() -> service.listSignatures("ghost", 9))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    // ------------------------------------------------------------------
    // 锁图签名验证主流程
    // ------------------------------------------------------------------

    @Test
    void lockFreezesDigestPolicyVersionAndSortedSignerKeyIdsForEveryClosureNode() {
        // app:1 -> lib[1,1] -> core[1,1]，共三个节点。
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1, dep("core", 1, 1)));
        service.registerArtifact(requestId(), artifact("core", 1));
        service.publishPolicy(requestId(), activePolicy(1L, 2, "k1", "k2", "k3"));
        sign("app", 1, "k3");
        sign("app", 1, "k1");
        sign("lib", 1, "k2");
        sign("lib", 1, "k1");
        sign("core", 1, "k1");
        sign("core", 1, "k2");

        long expectedVersion = repositoryVersion();
        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("app", 1, expectedVersion));

        assertThat(lock.entries()).hasSize(3);
        LockEntryResponse appEntry = findEntry(lock, "app");
        assertThat(appEntry.policyVersion()).isEqualTo(1L);
        assertThat(appEntry.digest()).isEqualTo(digestOf("app", 1));
        assertThat(appEntry.signerKeyIds()).containsExactly("k1", "k3");
        LockEntryResponse libEntry = findEntry(lock, "lib");
        assertThat(libEntry.policyVersion()).isEqualTo(1L);
        assertThat(libEntry.signerKeyIds()).containsExactly("k1", "k2");
        LockEntryResponse coreEntry = findEntry(lock, "core");
        assertThat(coreEntry.signerKeyIds()).containsExactly("k1", "k2");

        // 证据持久化：重新查询锁文件内容一致。
        LockFileResponse reloaded = service.getLock(lock.id());
        assertThat(reloaded.entries()).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(lock.entries());
    }

    @Test
    void lockFailsWhenRootHasInsufficientSignatures() {
        service.registerArtifact(requestId(), artifact("app", 1));
        service.publishPolicy(requestId(), activePolicy(1L, 2, "k1", "k2"));
        sign("app", 1, "k1");

        long expectedVersion = repositoryVersion();
        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, expectedVersion)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listLocks()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_entry", Integer.class))
                .isZero();
    }

    @Test
    void lockFailsWhenAnyDependencyNodeHasInsufficientSignatures() {
        // 根满足 2-of-2，依赖 lib 只有 1 个有效签名 -> 整次 422 且不生成锁。
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.publishPolicy(requestId(), activePolicy(1L, 2, "k1", "k2"));
        sign("app", 1, "k1");
        sign("app", 1, "k2");
        sign("lib", 1, "k1");

        long expectedVersion = repositoryVersion();
        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, expectedVersion)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> {
                            assertThat(e.getStatus()).isEqualTo(422);
                            assertThat(e.getMessage()).contains("lib:1");
                        });
        assertThat(service.listLocks()).isEmpty();
    }

    @Test
    void revokedKeySignatureIsExcludedFromThresholdButHistoricalLockStaysFrozen() {
        service.registerArtifact(requestId(), artifact("app", 1));
        service.publishPolicy(requestId(), activePolicy(1L, 2, "k1", "k2"));
        sign("app", 1, "k1");
        sign("app", 1, "k2");

        long versionWhenSigned = repositoryVersion();
        LockFileResponse first = service.createLock(requestId(),
                new LockRequest("app", 1, versionWhenSigned));
        assertThat(findEntry(first, "app").signerKeyIds()).containsExactly("k1", "k2");

        // 撤销 k1：历史签名保留、锁文件不改写。
        service.revokeKey(requestId(), "k1");
        LockFileResponse reloaded = service.getLock(first.id());
        assertThat(findEntry(reloaded, "app").signerKeyIds()).containsExactly("k1", "k2");
        assertThat(findEntry(reloaded, "app").policyVersion()).isEqualTo(1L);
        assertThat(service.listSignatures("app", 1)).hasSize(2);

        // 撤销后不得用于新锁定：有效签名仅剩 k2，不足 m=2 -> 422。
        long currentVersion = repositoryVersion();
        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, currentVersion)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        // 原锁仍是唯一一把锁。
        assertThat(service.listLocks()).hasSize(1);
    }

    @Test
    void lockFails422WhenTrustedSignatureDigestDoesNotMatchContent() {
        // 显式登记内容摘要，随后直接构造一条摘要不符的历史坏签名（绕过补签接口校验）。
        String digest = "a".repeat(64);
        service.registerArtifact(requestId(), artifactWithDigest("app", 1, digest));
        service.publishPolicy(requestId(), activePolicy(1L, 1, "k1", "k2"));
        sign("app", 1, "k2");
        long artifactId = jdbcTemplate.queryForObject(
                "SELECT id FROM artifact WHERE name = 'app' AND version = 1", Long.class);
        jdbcTemplate.update(
                "INSERT INTO artifact_signature (artifact_id, signature_key, key_id, digest, request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, 'bad-sig', CURRENT_TIMESTAMP(6))",
                artifactId, "app/1/k1", "k1", "f".repeat(64));

        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, repositoryVersion())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listLocks()).isEmpty();
    }

    @Test
    void newActivePolicyReplacesOldPolicyForResolution() {
        service.registerArtifact(requestId(), artifact("app", 1));
        // v1：m=1，k1 即可锁定。
        service.publishPolicy(requestId(), activePolicy(1L, 1, "k1"));
        sign("app", 1, "k1");
        long v1Version = repositoryVersion();
        assertThat(service.createLock(requestId(), new LockRequest("app", 1, v1Version)))
                .isNotNull();

        // v2 激活：m=2，仅有 k1 签名已不足。
        service.publishPolicy(requestId(), activePolicy(2L, 2, "k1", "k2"));
        long v2Version = repositoryVersion();
        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, v2Version)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));

        // 补 k2 后按 v2 锁定，冻结 policyVersion=2。
        sign("app", 1, "k2");
        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("app", 1, repositoryVersion()));
        assertThat(findEntry(lock, "app").policyVersion()).isEqualTo(2L);
        assertThat(findEntry(lock, "app").signerKeyIds()).containsExactly("k1", "k2");
    }

    @Test
    void futurePolicyDoesNotAffectLocksAndLegacyLockFreezesNoSignatureEvidence() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        // 策略一小时后才生效：当前无生效策略，沿用无签名旧语义。
        service.publishPolicy(requestId(),
                policy(1L, 2, Instant.now().plus(1, ChronoUnit.HOURS), "k1", "k2"));

        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("app", 1, repositoryVersion()));
        assertThat(findEntry(lock, "app").policyVersion()).isNull();
        assertThat(findEntry(lock, "app").digest()).isNull();
        assertThat(findEntry(lock, "app").signerKeyIds()).isNull();
        assertThat(findEntry(lock, "lib").policyVersion()).isNull();
    }

    @Test
    void sharedDependencyNodeIsVerifiedOnceInDiamondClosure() {
        // app -> a、b；a -> lib；b -> lib，lib 在闭包中只出现一次。
        service.registerArtifact(requestId(), artifact("app", 1,
                dep("a", 1, 1), dep("b", 1, 1)));
        service.registerArtifact(requestId(), artifact("a", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("b", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.publishPolicy(requestId(), activePolicy(1L, 1, "k1"));
        for (String name : new String[]{"app", "a", "b", "lib"}) {
            sign(name, 1, "k1");
        }

        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("app", 1, repositoryVersion()));
        assertThat(lock.entries()).extracting("name")
                .containsExactly("a", "app", "b", "lib");
        LockEntryResponse libEntry = findEntry(lock, "lib");
        // 计入集合无重复钥匙。
        assertThat(libEntry.signerKeyIds()).containsExactly("k1");
    }

    @Test
    void cycleStillFails422UnderActivePolicy() {
        // app:1 -> a[1,1]，a1 -> app[2,2]：环冲突不可行，签名验证前即失败。
        service.registerArtifact(requestId(), artifact("app", 1, dep("a", 1, 1)));
        service.registerArtifact(requestId(),
                new RegisterArtifactRequest("a", 1, null,
                        List.of(dep("app", 2, 2))));
        service.publishPolicy(requestId(), activePolicy(1L, 1, "k1"));

        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, repositoryVersion())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listLocks()).isEmpty();
    }

    @Test
    void withdrawnDependencyMakesClosureInfeasibleAndNoLockIsCreated() {
        // 契约允许版本撤回整体 422/409：依赖版本撤回导致闭包不可行时为 422，
        // 撤回根或期望版本过期为 409；两者均不得生成锁。
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.publishPolicy(requestId(), activePolicy(1L, 1, "k1"));
        sign("app", 1, "k1");
        sign("lib", 1, "k1");
        service.withdrawArtifact(requestId(), "lib", 1);

        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, repositoryVersion())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isIn(409, 422));
        assertThat(service.listLocks()).isEmpty();
    }

    // ------------------------------------------------------------------
    // 幂等：策略/撤销/补签/锁重放
    // ------------------------------------------------------------------

    @Test
    void policyPublishReplaysSameRequestIdAndRejectsDifferentParams() {
        String rid = requestId();
        PublishPolicyRequest request = activePolicy(1L, 1, "k1");
        PolicyResponse first = service.publishPolicy(rid, request);
        PolicyResponse replay = service.publishPolicy(rid, request);
        assertThat(replay.createdAt()).isEqualTo(first.createdAt());
        assertThat(replay.policyVersion()).isEqualTo(first.policyVersion());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM signing_policy", Integer.class)).isEqualTo(1);

        assertThatThrownBy(() -> service.publishPolicy(rid, activePolicy(1L, 2, "k1", "k2")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void revokeReplaysSameRequestIdAndCrossOperationConflicts() {
        service.publishPolicy(requestId(), activePolicy(1L, 1, "k1"));
        String rid = requestId();
        KeyResponse first = service.revokeKey(rid, "k1");
        KeyResponse replay = service.revokeKey(rid, "k1");
        assertThat(replay.revokedAt()).isEqualTo(first.revokedAt());

        assertThatThrownBy(() -> service.publishPolicy(rid, activePolicy(2L, 1, "k9")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void signatureReplayDoesNotDuplicateAndDifferentParamsConflict() {
        service.registerArtifact(requestId(), artifact("app", 1));
        service.publishPolicy(requestId(), activePolicy(1L, 1, "k1", "k2"));
        String digest = digestOf("app", 1);
        String rid = requestId();

        SignatureResponse first = service.addSignature(rid, "app", 1,
                new AddSignatureRequest("k1", digest));
        SignatureResponse replay = service.addSignature(rid, "app", 1,
                new AddSignatureRequest("k1", digest));
        assertThat(replay.createdAt()).isEqualTo(first.createdAt());
        assertThat(service.listSignatures("app", 1)).hasSize(1);

        // 同 requestId 换钥匙 -> 409。
        assertThatThrownBy(() -> service.addSignature(rid, "app", 1,
                new AddSignatureRequest("k2", digest)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void failedPolicyPublishDoesNotConsumeRequestId() {
        String rid = requestId();
        // 阈值越界失败（400），requestId 不被占用。
        assertThatThrownBy(() -> service.publishPolicy(rid, activePolicy(1L, 3, "k1")))
                .isInstanceOf(ApiException.class);
        PolicyResponse later = service.publishPolicy(rid, activePolicy(1L, 1, "k1"));
        assertThat(later.policyVersion()).isEqualTo(1L);
    }

    @Test
    void lockReplayDoesNotReselectSignaturesAfterLaterCountersigning() {
        service.registerArtifact(requestId(), artifact("app", 1));
        service.publishPolicy(requestId(), activePolicy(1L, 2, "k1", "k2"));
        sign("app", 1, "k1");
        sign("app", 1, "k2");
        String rid = requestId();
        LockRequest lockRequest = new LockRequest("app", 1, repositoryVersion());
        LockFileResponse first = service.createLock(rid, lockRequest);
        assertThat(findEntry(first, "app").signerKeyIds()).containsExactly("k1", "k2");

        // 新策略 v2 激活（m=3）并补 k3；仓库前进。
        service.publishPolicy(requestId(), activePolicy(2L, 3, "k1", "k2", "k3"));
        sign("app", 1, "k3");

        // 同参重放：原样返回 v1 的冻结证据，不重新选签名、不重新解析。
        LockFileResponse replay = service.createLock(rid, lockRequest);
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.repositoryVersion()).isEqualTo(first.repositoryVersion());
        assertThat(findEntry(replay, "app").policyVersion()).isEqualTo(1L);
        assertThat(findEntry(replay, "app").signerKeyIds()).containsExactly("k1", "k2");
    }

    // ------------------------------------------------------------------
    // 并发边界
    // ------------------------------------------------------------------

    @Test
    void concurrentPolicyActivationAndLockResolveToOneConsistentState() throws Exception {
        service.registerArtifact(requestId(), artifact("app", 1));
        service.publishPolicy(requestId(), activePolicy(1L, 1, "k1"));
        sign("app", 1, "k1");
        long versionBefore = repositoryVersion();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        String lockRid = requestId();
        String policyRid = requestId();

        Callable<Object> lockTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return service.createLock(lockRid,
                        new LockRequest("app", 1, versionBefore));
            } catch (ApiException e) {
                return e;
            }
        };
        Callable<Object> policyTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            // v2 要求 m=2，且 app 只有 k1 签名：若锁在其后执行必然 422/版本过期。
            return service.publishPolicy(policyRid, activePolicy(2L, 2, "k1", "k2"));
        };

        List<Future<Object>> futures = pool.invokeAll(List.of(lockTask, policyTask));
        pool.shutdown();
        Object lockOutcome = futures.get(0).get(30, TimeUnit.SECONDS);
        Object policyOutcome = futures.get(1).get(30, TimeUnit.SECONDS);

        assertThat(policyOutcome).isInstanceOf(PolicyResponse.class);
        if (lockOutcome instanceof LockFileResponse lock) {
            // 锁先提交：冻结旧仓库版本与 policyVersion=1。
            assertThat(lock.repositoryVersion()).isEqualTo(versionBefore);
            assertThat(findEntry(lock, "app").policyVersion()).isEqualTo(1L);
        } else {
            // 策略先提交：期望版本过期 -> 409，绝不能出现引用半新状态的锁。
            assertThat(lockOutcome).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(409));
            assertThat(service.listLocks()).isEmpty();
        }
    }

    @Test
    void concurrentRevokeAndCountersignSerializeWithoutCorruption() throws Exception {
        service.registerArtifact(requestId(), artifact("app", 1));
        service.publishPolicy(requestId(), activePolicy(1L, 1, "k1"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        String signRid = requestId();
        String revokeRid = requestId();
        String digest = digestOf("app", 1);

        Callable<Object> signTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return service.addSignature(signRid, "app", 1,
                        new AddSignatureRequest("k1", digest));
            } catch (ApiException e) {
                return e;
            }
        };
        Callable<Object> revokeTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return service.revokeKey(revokeRid, "k1");
            } catch (ApiException e) {
                return e;
            }
        };

        List<Future<Object>> futures = pool.invokeAll(List.of(signTask, revokeTask));
        pool.shutdown();
        Object signOutcome = futures.get(0).get(30, TimeUnit.SECONDS);
        Object revokeOutcome = futures.get(1).get(30, TimeUnit.SECONDS);

        assertThat(revokeOutcome).isInstanceOf(KeyResponse.class);
        // 写事务串行化：要么补签先成功（201），要么撤销先成功导致补签 409。
        if (signOutcome instanceof SignatureResponse signature) {
            assertThat(signature.keyId()).isEqualTo("k1");
        } else {
            assertThat(signOutcome).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(409));
        }
        // 无论谁先谁后，至多一条签名且钥匙最终撤销。
        assertThat(service.listSignatures("app", 1)).hasSizeLessThanOrEqualTo(1);
        assertThat(service.getKey("k1").revoked()).isTrue();
    }

    @Test
    void concurrentSameKeyCountersignatureOneWinsRestConflict() throws Exception {
        int threads = 6;
        service.registerArtifact(requestId(), artifact("app", 1));
        service.publishPolicy(requestId(), activePolicy(1L, 1, "k1"));
        String digest = digestOf("app", 1);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    // 不同 requestId、同钥匙补签：唯一约束保证只有一个成功。
                    service.addSignature(requestId(), "app", 1,
                            new AddSignatureRequest("k1", digest));
                    return 201;
                } catch (ApiException e) {
                    return e.getStatus();
                }
            }));
        }
        pool.shutdown();

        int success = 0;
        int conflict = 0;
        for (Future<Integer> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS);
            assertThat(status).isIn(201, 409);
            if (status == 201) {
                success++;
            } else {
                conflict++;
            }
        }
        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        assertThat(service.listSignatures("app", 1)).hasSize(1);
    }

    @Test
    void concurrentSameRequestIdCountersignatureResolvesToSingleResult() throws Exception {
        int threads = 6;
        service.registerArtifact(requestId(), artifact("app", 1));
        service.publishPolicy(requestId(), activePolicy(1L, 1, "k1"));
        String digest = digestOf("app", 1);
        String rid = requestId();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<SignatureResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.addSignature(rid, "app", 1,
                        new AddSignatureRequest("k1", digest));
            }));
        }
        pool.shutdown();

        SignatureResponse first = futures.get(0).get(30, TimeUnit.SECONDS);
        for (Future<SignatureResponse> future : futures) {
            SignatureResponse response = future.get(30, TimeUnit.SECONDS);
            assertThat(response.createdAt()).isEqualTo(first.createdAt());
        }
        assertThat(service.listSignatures("app", 1)).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
    }

    @Test
    void registrationReturnsExplicitOrDerivedContentDigest() {
        var explicit = service.registerArtifact(requestId(),
                artifactWithDigest("doc", 1, "c".repeat(64)));
        assertThat(explicit.contentDigest()).isEqualTo("c".repeat(64));
        assertThat(digestOf("doc", 1)).isEqualTo("c".repeat(64));

        var derived = service.registerArtifact(requestId(),
                artifact("tool", 1, dep("core", 1, 2)));
        assertThat(derived.contentDigest()).hasSize(64).matches("[0-9a-f]{64}");
        // 相同规范化坐标与依赖派生出相同摘要。
        var derivedAgain = service.registerArtifact(requestId(),
                new RegisterArtifactRequest("tool2", 1, null,
                        List.of(dep("core", 1, 2))));
        // 名称不同则摘要不同；同坐标（在另一名称上无法复现名称）这里仅校验非空与十六进制格式。
        assertThat(derivedAgain.contentDigest()).isNotEqualTo(derived.contentDigest());
    }

    @Test
    void tenMaximumLengthKeyIdsAreFrozenInLockEvidenceColumn() {
        // 上界：10 个 keyId，每个 128 字符；锁条目 signer_key_ids 列必须容纳 10*128+9 字节。
        List<String> keyIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            keyIds.add("k" + String.format("%03d", i) + "x".repeat(124));
        }
        service.registerArtifact(requestId(), artifact("app", 1));
        service.publishPolicy(requestId(),
                new PublishPolicyRequest(1L, keyIds, 10, Instant.now().minusSeconds(60)));
        for (String keyId : keyIds) {
            sign("app", 1, keyId);
        }

        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("app", 1, repositoryVersion()));
        LockEntryResponse entry = findEntry(lock, "app");
        assertThat(entry.signerKeyIds()).hasSize(10);
        assertThat(entry.signerKeyIds()).containsExactlyElementsOf(keyIds);

        // 数据库列实际写入长度与重载一致性。
        String csv = jdbcTemplate.queryForObject(
                "SELECT signer_key_ids FROM lock_file_entry WHERE lock_file_id = ? AND name = 'app'",
                String.class, lock.id());
        assertThat(csv).hasSize(10 * 128 + 9);
        LockFileResponse reloaded = service.getLock(lock.id());
        assertThat(findEntry(reloaded, "app").signerKeyIds())
                .containsExactlyElementsOf(keyIds);
    }

    @Test
    void elevenKeysAreRejected() {
        List<String> keyIds = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            keyIds.add("k" + i);
        }
        assertThatThrownBy(() -> service.publishPolicy(requestId(),
                new PublishPolicyRequest(1L, keyIds, 1, Instant.now().minusSeconds(60))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    private LockEntryResponse findEntry(LockFileResponse lock, String name) {
        return lock.entries().stream()
                .filter(e -> e.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("锁条目缺少节点: " + name));
    }
}
