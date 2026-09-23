package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.PolicyResponse;
import com.example.starter.api.dto.PublishPolicyRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.SubstitutionStepResponse;
import com.example.starter.support.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
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
 * 替代策略与锁图解释的真实 H2（MODE=MySQL）集成测试：
 * 多跳替代、整体回滚、策略校验、历史稳定、幂等重放、平台可用性与并发提交顺序。
 */
@SpringBootTest
class SubstitutionH2Test {

    private static final String PLATFORM = "linux-x86_64";
    private static final Instant EFFECTIVE = Instant.parse("2025-01-01T00:00:00Z");

    @Autowired
    private ArtifactService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM lock_substitution_step");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM substitution_alternative");
        jdbcTemplate.update("DELETE FROM substitution_rule");
        jdbcTemplate.update("DELETE FROM substitution_policy");
        jdbcTemplate.update("DELETE FROM artifact_platform");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private RegisterArtifactRequest artifact(String name, int version, List<String> platforms,
                                             DependencySpec... deps) {
        return new RegisterArtifactRequest(name, version, List.of(deps), platforms);
    }

    private static DependencySpec dep(String name, int min, int max) {
        return new DependencySpec(name, min, max);
    }

    private static PublishPolicyRequest.AlternativeSpec alt(String name, int version) {
        return new PublishPolicyRequest.AlternativeSpec(name, version);
    }

    private static PublishPolicyRequest.RuleSpec rule(String pattern,
                                                      PublishPolicyRequest.AlternativeSpec... alts) {
        return new PublishPolicyRequest.RuleSpec(pattern, PLATFORM, EFFECTIVE, List.of(alts));
    }

    private long repositoryVersion() {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
    }

    // ------------------------------------------------------------------
    // 策略发布与查询
    // ------------------------------------------------------------------

    @Test
    void publishPolicyPersistsOrderedRulesAndIncrementsVersion() {
        long before = repositoryVersion();
        PublishPolicyRequest request = new PublishPolicyRequest("pol-1", List.of(
                rule("mid:1", alt("new", 1)),
                rule("old:1", alt("mid", 1))));
        PolicyResponse response = service.publishPolicy(rid(), request);

        assertThat(response.policyVersion()).isPositive();
        assertThat(response.policyKey()).isEqualTo("pol-1");
        assertThat(response.rules()).hasSize(2);
        // 稳定排序：按 ruleIndex 升序。
        assertThat(response.rules()).extracting("ruleIndex").containsExactly(0, 1);
        assertThat(response.rules().get(1).alternatives())
                .extracting("name", "version").containsExactly(tuple("mid", 1));
        assertThat(repositoryVersion()).isEqualTo(before + 1);

        // 只读查询稳定排序。
        assertThat(service.listPolicies()).extracting("policyVersion")
                .containsExactly(response.policyVersion());
        assertThat(service.getPolicy(response.policyVersion()).rules()).hasSize(2);
    }

    @Test
    void duplicatePolicyKeyReturns409() {
        service.publishPolicy(rid(), new PublishPolicyRequest("dup",
                List.of(rule("old:1", alt("good", 1)))));
        assertThatThrownBy(() -> service.publishPolicy(rid(), new PublishPolicyRequest("dup",
                List.of(rule("old:1", alt("other", 1))))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void selfSubstitutionRuleRejectedAtPublish() {
        assertThatThrownBy(() -> service.publishPolicy(rid(), new PublishPolicyRequest("self",
                List.of(rule("good:1", alt("good", 1))))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM substitution_policy", Integer.class)).isZero();
    }

    @Test
    void overlappingRulesRejectedAtPublish() {
        assertThatThrownBy(() -> service.publishPolicy(rid(), new PublishPolicyRequest("overlap",
                List.of(rule("old:1", alt("a", 1)), rule("old:*", alt("b", 1))))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM substitution_policy", Integer.class)).isZero();
    }

    @Test
    void policyPublishIsIdempotentByRequestId() {
        PublishPolicyRequest request = new PublishPolicyRequest("idem",
                List.of(rule("old:1", alt("good", 1))));
        String id = rid();
        PolicyResponse first = service.publishPolicy(id, request);
        PolicyResponse replay = service.publishPolicy(id, request);
        assertThat(replay.policyVersion()).isEqualTo(first.policyVersion());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM substitution_policy", Integer.class)).isEqualTo(1);
    }

    @Test
    void sameRequestIdDifferentPolicyPayloadReturns409() {
        String id = rid();
        service.publishPolicy(id, new PublishPolicyRequest("k",
                List.of(rule("old:1", alt("good", 1)))));
        assertThatThrownBy(() -> service.publishPolicy(id, new PublishPolicyRequest("k2",
                List.of(rule("old:1", alt("good", 1))))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    // ------------------------------------------------------------------
    // 多跳替代与解释快照
    // ------------------------------------------------------------------

    @Test
    void multiHopSubstitutionLocksFinalNodesAndFreezesExplanation() {
        // app:1 -> old[1,1]；old:1 撤回；mid:1 撤回；new:1 -> util[1,1]。
        service.registerArtifact(rid(), artifact("app", 1, List.of(), dep("old", 1, 1)));
        service.registerArtifact(rid(), artifact("old", 1, List.of()));
        service.withdrawArtifact(rid(), "old", 1);
        service.registerArtifact(rid(), artifact("mid", 1, List.of()));
        service.withdrawArtifact(rid(), "mid", 1);
        service.registerArtifact(rid(), artifact("new", 1, List.of(), dep("util", 1, 1)));
        service.registerArtifact(rid(), artifact("util", 1, List.of()));

        PolicyResponse policy = service.publishPolicy(rid(), new PublishPolicyRequest("chain",
                List.of(rule("old:1", alt("ghost", 1), alt("mid", 1)),
                        rule("mid:1", alt("new", 1)))));
        long expectedRepo = repositoryVersion();

        LockFileResponse lock = service.createLock(rid(),
                new LockRequest("app", 1, expectedRepo, PLATFORM));

        assertThat(lock.platform()).isEqualTo(PLATFORM);
        assertThat(lock.policyVersion()).isEqualTo(policy.policyVersion());
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactlyInAnyOrder(tuple("app", 1), tuple("new", 1), tuple("util", 1));
        assertThat(lock.entries()).extracting("name").doesNotContain("old", "mid");

        List<SubstitutionStepResponse> steps = lock.substitutions();
        assertThat(steps).hasSize(2);
        // 按原坐标名称排序：mid 在 old 前。
        assertThat(steps.get(0).originalName()).isEqualTo("mid");
        assertThat(steps.get(0).finalName()).isEqualTo("new");
        assertThat(steps.get(0).policyVersion()).isEqualTo(policy.policyVersion());
        SubstitutionStepResponse oldStep = steps.get(1);
        assertThat(oldStep.originalName()).isEqualTo("old");
        assertThat(oldStep.finalName()).isEqualTo("new");
        // 首个候选 ghost:1 不存在，拒绝原因被冻结。
        assertThat(oldStep.rejectedCandidates()).hasSize(1);
        assertThat(oldStep.rejectedCandidates().get(0).name()).isEqualTo("ghost");

        // 重新查询解释一致。
        assertThat(service.getLock(lock.id()).substitutions()).isEqualTo(steps);
    }

    @Test
    void platformRestrictedVersionTriggersSubstitution() {
        // lib:1 仅发布到其他平台，linux 平台不可用 → 替代为 lib-ng:1。
        service.registerArtifact(rid(), artifact("app", 1, List.of(), dep("lib", 1, 1)));
        service.registerArtifact(rid(), artifact("lib", 1, List.of("other-platform")));
        service.registerArtifact(rid(), artifact("lib-ng", 1, List.of(PLATFORM)));
        service.publishPolicy(rid(), new PublishPolicyRequest("pf",
                List.of(rule("lib:1", alt("lib-ng", 1)))));

        LockFileResponse lock = service.createLock(rid(),
                new LockRequest("app", 1, repositoryVersion(), PLATFORM));
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactlyInAnyOrder(tuple("app", 1), tuple("lib-ng", 1));
    }

    @Test
    void lockWithoutPlatformIgnoresSubstitutionAndKeepsLegacyBehavior() {
        service.registerArtifact(rid(), artifact("app", 1, List.of(), dep("lib", 1, 2)));
        service.registerArtifact(rid(), artifact("lib", 2, List.of()));
        service.registerArtifact(rid(), artifact("lib", 1, List.of()));
        service.publishPolicy(rid(), new PublishPolicyRequest("unused",
                List.of(rule("lib:*", alt("lib-ng", 1)))));

        LockFileResponse lock = service.createLock(rid(),
                new LockRequest("app", 1, repositoryVersion(), null));
        assertThat(lock.policyVersion()).isNull();
        assertThat(lock.substitutions()).isEmpty();
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 2));
    }

    // ------------------------------------------------------------------
    // 失败分支与整体回滚
    // ------------------------------------------------------------------

    @Test
    void unavailableNodeWithoutApplicableRuleReturns422AndCreatesNoPartialLock() {
        // old:1 撤回，但策略没有任何命中 old 的规则 → 明确失败 422。
        service.registerArtifact(rid(), artifact("app", 1, List.of(), dep("old", 1, 1)));
        service.registerArtifact(rid(), artifact("old", 1, List.of()));
        service.withdrawArtifact(rid(), "old", 1);
        service.registerArtifact(rid(), artifact("unrelated", 1, List.of()));
        service.publishPolicy(rid(), new PublishPolicyRequest("nomatch",
                List.of(rule("other:1", alt("unrelated", 1)))));

        String retryId = rid();
        assertThatThrownBy(() -> service.createLock(retryId,
                new LockRequest("app", 1, repositoryVersion(), PLATFORM)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listLocks()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file_entry", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_substitution_step", Integer.class)).isZero();
        // 失败不占用 requestId：同键随后可用于成功请求。
        service.registerArtifact(retryId, artifact("fresh", 1, List.of()));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, retryId)).isEqualTo(1);
    }

    @Test
    void substitutionCycleReturns422() {
        service.registerArtifact(rid(), artifact("app", 1, List.of(), dep("old", 1, 1)));
        service.registerArtifact(rid(), artifact("old", 1, List.of()));
        service.withdrawArtifact(rid(), "old", 1);
        service.registerArtifact(rid(), artifact("new", 1, List.of()));
        service.withdrawArtifact(rid(), "new", 1);
        service.publishPolicy(rid(), new PublishPolicyRequest("cycle", List.of(
                rule("old:1", alt("new", 1)),
                rule("new:1", alt("old", 1)))));

        assertThatThrownBy(() -> service.createLock(rid(),
                new LockRequest("app", 1, repositoryVersion(), PLATFORM)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listLocks()).isEmpty();
    }

    @Test
    void incompatibleSubstitutedConstraintsReturn422() {
        // old:1 -> good:1，good:1 要求 util[5,5]，仅 util:1。
        service.registerArtifact(rid(), artifact("app", 1, List.of(), dep("old", 1, 1)));
        service.registerArtifact(rid(), artifact("old", 1, List.of()));
        service.withdrawArtifact(rid(), "old", 1);
        service.registerArtifact(rid(), artifact("good", 1, List.of(), dep("util", 5, 5)));
        service.registerArtifact(rid(), artifact("util", 1, List.of()));
        service.publishPolicy(rid(), new PublishPolicyRequest("incompat",
                List.of(rule("old:1", alt("good", 1)))));

        assertThatThrownBy(() -> service.createLock(rid(),
                new LockRequest("app", 1, repositoryVersion(), PLATFORM)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listLocks()).isEmpty();
    }

    // ------------------------------------------------------------------
    // 历史稳定：策略修改、撤回、恢复不改写历史解释
    // ------------------------------------------------------------------

    @Test
    void laterPolicyChangeAndRestoreDoNotRewriteHistoricalExplanation() {
        service.registerArtifact(rid(), artifact("app", 1, List.of(), dep("old", 1, 1)));
        service.registerArtifact(rid(), artifact("old", 1, List.of()));
        service.withdrawArtifact(rid(), "old", 1);
        service.registerArtifact(rid(), artifact("good", 1, List.of()));
        long p1 = service.publishPolicy(rid(), new PublishPolicyRequest("p1",
                List.of(rule("old:1", alt("good", 1))))).policyVersion();

        LockFileResponse first = service.createLock(rid(),
                new LockRequest("app", 1, repositoryVersion(), PLATFORM));
        assertThat(first.policyVersion()).isEqualTo(p1);

        // 发布新策略版本，将 old:1 改指 other:1；再恢复 old:1。
        service.registerArtifact(rid(), artifact("other", 1, List.of()));
        service.publishPolicy(rid(), new PublishPolicyRequest("p2",
                List.of(rule("old:1", alt("other", 1)))));
        service.restoreArtifact(rid(), "old", 1);

        LockFileResponse queried = service.getLock(first.id());
        assertThat(queried.policyVersion()).isEqualTo(p1);
        assertThat(queried.entries()).isEqualTo(first.entries());
        assertThat(queried.substitutions()).isEqualTo(first.substitutions());
        assertThat(queried.substitutions().get(0).finalName()).isEqualTo("good");
    }

    @Test
    void restoreMakesVersionAvailableAndAdvancesRepositoryVersion() {
        service.registerArtifact(rid(), artifact("app", 1, List.of()));
        service.withdrawArtifact(rid(), "app", 1);
        ArtifactResponse restored = service.restoreArtifact(rid(), "app", 1);
        assertThat(restored.withdrawn()).isFalse();
        assertThat(repositoryVersion()).isEqualTo(3L);
        // 再次恢复冲突。
        assertThatThrownBy(() -> service.restoreArtifact(rid(), "app", 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void lockReplayReturnsSameLockAndExplanation() {
        service.registerArtifact(rid(), artifact("app", 1, List.of(), dep("old", 1, 1)));
        service.registerArtifact(rid(), artifact("old", 1, List.of()));
        service.withdrawArtifact(rid(), "old", 1);
        service.registerArtifact(rid(), artifact("good", 1, List.of()));
        service.publishPolicy(rid(), new PublishPolicyRequest("p",
                List.of(rule("old:1", alt("good", 1)))));
        long expectedRepo = repositoryVersion();

        String id = rid();
        LockRequest request = new LockRequest("app", 1, expectedRepo, PLATFORM);
        LockFileResponse first = service.createLock(id, request);
        LockFileResponse replay = service.createLock(id, request);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.policyVersion()).isEqualTo(first.policyVersion());
        assertThat(replay.entries()).isEqualTo(first.entries());
        assertThat(replay.substitutions()).isEqualTo(first.substitutions());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file", Integer.class)).isEqualTo(1);
    }

    @Test
    void sameRequestIdDifferentPlatformReturns409() {
        service.registerArtifact(rid(), artifact("app", 1, List.of()));
        long v = repositoryVersion();
        String id = rid();
        service.createLock(id, new LockRequest("app", 1, v, PLATFORM));
        assertThatThrownBy(() -> service.createLock(id, new LockRequest("app", 1, v, "arm64")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    // ------------------------------------------------------------------
    // 并发：策略激活与锁定按提交顺序，锁图不混用两个策略版本
    // ------------------------------------------------------------------

    @Test
    void concurrentPolicyActivationAndLockObserveSingleCommitOrder() throws Exception {
        service.registerArtifact(rid(), artifact("app", 1, List.of(), dep("old", 1, 1)));
        service.registerArtifact(rid(), artifact("old", 1, List.of()));
        service.withdrawArtifact(rid(), "old", 1);
        service.registerArtifact(rid(), artifact("good", 1, List.of()));
        service.registerArtifact(rid(), artifact("other", 1, List.of()));
        long p1 = service.publishPolicy(rid(), new PublishPolicyRequest("c1",
                List.of(rule("old:1", alt("good", 1))))).policyVersion();
        final long versionAtP1 = repositoryVersion();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        Callable<Object> lockTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return service.createLock(rid(),
                        new LockRequest("app", 1, versionAtP1, PLATFORM));
            } catch (ApiException e) {
                return e;
            }
        };
        Callable<Object> publishTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.publishPolicy(rid(), new PublishPolicyRequest("c2",
                    List.of(rule("old:1", alt("other", 1)))));
        };

        List<Future<Object>> futures = pool.invokeAll(List.of(lockTask, publishTask));
        pool.shutdown();
        Object lockOutcome = futures.get(0).get(30, TimeUnit.SECONDS);
        Object publishOutcome = futures.get(1).get(30, TimeUnit.SECONDS);
        assertThat(publishOutcome).isInstanceOf(PolicyResponse.class);

        if (lockOutcome instanceof LockFileResponse lock) {
            // 锁先提交：必须冻结在 P1，即使之后 P2 激活也不混用、不改写。
            assertThat(lock.policyVersion()).isEqualTo(p1);
            assertThat(lock.substitutions().get(0).finalName()).isEqualTo("good");
            LockFileResponse requery = service.getLock(lock.id());
            assertThat(requery.policyVersion()).isEqualTo(p1);
            assertThat(requery.substitutions()).isEqualTo(lock.substitutions());
        } else {
            // 策略先提交：期望仓库版本过期 → 409，且无半成品锁。
            assertThat(lockOutcome).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(409));
            assertThat(service.listLocks()).isEmpty();
        }
    }
}
