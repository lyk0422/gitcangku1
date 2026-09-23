package com.example.starter.api;

import com.example.starter.api.dto.CandidateRejectionResponse;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
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
 * 替代策略与锁图解释的真实 H2（MODE=MySQL）集成测试：
 * 多跳替代、平台触发、路径冲突/环整体回滚、解释冻结与历史稳定、幂等、策略-锁定并发边界。
 */
@SpringBootTest
class SubstitutionH2Test {

    @Autowired
    private ArtifactService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM lock_step_rejection");
        jdbcTemplate.update("DELETE FROM lock_substitution_step");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_platform");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM substitution_candidate");
        jdbcTemplate.update("DELETE FROM substitution_rule");
        jdbcTemplate.update("DELETE FROM substitution_policy");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
        jdbcTemplate.update("UPDATE policy_state SET current_version = 0 WHERE id = 1");
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private static DependencySpec dep(String name, int min, int max) {
        return new DependencySpec(name, min, max);
    }

    private void register(String name, int version, List<DependencySpec> deps, String... platforms) {
        service.registerArtifact(rid(), new RegisterArtifactRequest(name, version, deps, List.of(platforms)));
    }

    private void withdraw(String name, int version) {
        service.withdrawArtifact(rid(), name, version);
    }

    private static PublishPolicyRequest.CandidateSpec candidate(String coordinate, int priority) {
        return new PublishPolicyRequest.CandidateSpec(coordinate, priority);
    }

    private static PublishPolicyRequest.RuleSpec rule(String source, String platform,
                                                      PublishPolicyRequest.CandidateSpec... candidates) {
        return new PublishPolicyRequest.RuleSpec(source, platform, List.of(candidates),
                Instant.now().minusSeconds(3600));
    }

    private PolicyResponse publish(PublishPolicyRequest.RuleSpec... rules) {
        return service.publishPolicy(rid(), new PublishPolicyRequest(List.of(rules)));
    }

    private long repositoryVersion() {
        return jdbcTemplate.queryForObject("SELECT version FROM repository_state WHERE id = 1", Long.class);
    }

    // ------------------------------------------------------------------
    // 策略发布与校验
    // ------------------------------------------------------------------

    @Test
    void publishPolicyPersistsVersionRulesCandidatesAndAdvancesVersions() {
        PolicyResponse response = publish(
                rule("legacy", "jvm", candidate("newlib", 1), candidate("other", 2)));

        assertThat(response.version()).isEqualTo(1L);
        assertThat(response.rules()).hasSize(1);
        PolicyResponse.RuleView ruleView = response.rules().get(0);
        assertThat(ruleView.source()).isEqualTo("legacy");
        assertThat(ruleView.platform()).isEqualTo("jvm");
        assertThat(ruleView.candidates()).extracting("priority", "coordinate")
                .containsExactly(tuple(1, "newlib"), tuple(2, "other"));
        assertThat(repositoryVersion()).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT current_version FROM policy_state WHERE id = 1", Long.class)).isEqualTo(1L);

        // 只读查询：当前策略、按版本查询、列表稳定排序。
        assertThat(service.getCurrentPolicy().version()).isEqualTo(1L);
        assertThat(service.getPolicy(1).rules()).hasSize(1);
        assertThat(service.listPolicies()).extracting("version").containsExactly(1L);
    }

    @Test
    void publishingSecondPolicyKeepsFirstVersionImmutable() {
        publish(rule("a", "jvm", candidate("b", 1)));
        publish(rule("c", "jvm", candidate("d", 1)));

        assertThat(service.getPolicy(1).rules()).extracting("source").containsExactly("a");
        assertThat(service.getCurrentPolicy().version()).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM substitution_rule", Integer.class)).isEqualTo(2);
    }

    @Test
    void invalidCyclePolicyReturns422AndAdvancesNothing() {
        long before = repositoryVersion();
        String requestId = rid();
        assertThatThrownBy(() -> service.publishPolicy(requestId,
                new PublishPolicyRequest(List.of(
                        rule("a", "jvm", candidate("b", 1)),
                        rule("b", "jvm", candidate("a", 1))))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("环");
                });

        assertThat(repositoryVersion()).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT current_version FROM policy_state WHERE id = 1", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM substitution_policy", Integer.class)).isZero();

        // 失败不占键：同一 requestId 可发布合法策略。
        PolicyResponse later = service.publishPolicy(requestId,
                new PublishPolicyRequest(List.of(rule("a", "jvm", candidate("b", 1)))));
        assertThat(later.version()).isEqualTo(1L);
    }

    @Test
    void overlappingRulesReturn422() {
        assertThatThrownBy(() -> publish(
                rule("com.old.A", "jvm", candidate("x", 1)),
                rule("com.old.*", "jvm", candidate("y", 1))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void policyIdempotentReplayReturnsSameVersion() {
        String requestId = rid();
        PublishPolicyRequest request = new PublishPolicyRequest(
                List.of(rule("a", "jvm", candidate("b", 1))));
        PolicyResponse first = service.publishPolicy(requestId, request);
        PolicyResponse replay = service.publishPolicy(requestId, request);
        assertThat(replay.version()).isEqualTo(first.version());
        assertThat(replay.createdAt()).isEqualTo(first.createdAt());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM substitution_policy", Integer.class)).isEqualTo(1);
        assertThat(repositoryVersion()).isEqualTo(1L);
    }

    @Test
    void restoreMakesWithdrawnArtifactAvailableAgainAndAdvancesVersion() {
        register("app", 1, List.of());
        withdraw("app", 1);
        long versionAfterWithdraw = repositoryVersion();

        var restored = service.restoreArtifact(rid(), "app", 1);
        assertThat(restored.withdrawn()).isFalse();
        assertThat(repositoryVersion()).isEqualTo(versionAfterWithdraw + 1);
        assertThatThrownBy(() -> service.restoreArtifact(rid(), "app", 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    // ------------------------------------------------------------------
    // 多跳替代与解释冻结
    // ------------------------------------------------------------------

    @Test
    void multiHopSubstitutionLocksFinalCoordinatesAndFreezesExplanation() {
        register("app", 1, List.of(dep("a", 1, 1)));
        register("a", 1, List.of());
        withdraw("a", 1);
        register("b", 1, List.of());
        withdraw("b", 1);
        register("c", 1, List.of(dep("util", 1, 1)));
        register("util", 1, List.of());
        publish(rule("a", "jvm", candidate("b", 1)),
                rule("b", "jvm", candidate("c", 1)));
        long expectedVersion = repositoryVersion();

        LockFileResponse lock = service.createLock(rid(),
                new LockRequest("app", 1, "jvm", expectedVersion));

        assertThat(lock.platform()).isEqualTo("jvm");
        assertThat(lock.policyVersion()).isEqualTo(1L);
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("c", 1), tuple("util", 1));
        assertThat(lock.substitutionSteps()).hasSize(2);
        SubstitutionStepResponse first = lock.substitutionSteps().get(0);
        assertThat(first.originalCoordinate()).isEqualTo("a");
        assertThat(first.sourcePattern()).isEqualTo("a");
        assertThat(first.finalCoordinate()).isEqualTo("c");
        assertThat(first.policyVersion()).isEqualTo(1L);
        SubstitutionStepResponse second = lock.substitutionSteps().get(1);
        assertThat(second.originalCoordinate()).isEqualTo("b");
        assertThat(second.finalCoordinate()).isEqualTo("c");

        // 解释表实际落库，可再次只读查询且稳定排序。
        LockFileResponse reloaded = service.getLock(lock.id());
        assertThat(reloaded.substitutionSteps()).usingRecursiveComparison()
                .isEqualTo(lock.substitutionSteps());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_substitution_step", Integer.class)).isEqualTo(2);
    }

    @Test
    void candidateRejectionReasonsAreFrozen() {
        register("app", 1, List.of(dep("legacy", 1, 1)));
        register("legacy", 1, List.of());
        withdraw("legacy", 1);
        register("withdrawn-only", 1, List.of());
        withdraw("withdrawn-only", 1);
        register("good", 1, List.of());
        publish(rule("legacy", "jvm",
                candidate("ghost", 1), candidate("withdrawn-only", 2), candidate("good", 3)));

        LockFileResponse lock = service.createLock(rid(),
                new LockRequest("app", 1, "jvm", repositoryVersion()));

        List<CandidateRejectionResponse> rejections = lock.substitutionSteps().get(0).rejections();
        assertThat(rejections).extracting("coordinate", "priority", "reason")
                .containsExactly(
                        tuple("ghost", 1, "NOT_FOUND"),
                        tuple("withdrawn-only", 2, "WITHDRAWN"));
    }

    @Test
    void platformUnavailabilityTriggersSubstitution() {
        register("legacy", 1, List.of(), "native");
        register("app", 1, List.of(dep("legacy", 1, 1)));
        register("alt", 1, List.of());
        publish(rule("legacy", "jvm", candidate("alt", 1)));

        LockFileResponse lock = service.createLock(rid(),
                new LockRequest("app", 1, "jvm", repositoryVersion()));
        assertThat(lock.entries()).extracting("name").containsExactly("alt", "app");
    }

    // ------------------------------------------------------------------
    // 整体回滚：冲突与环不留部分锁
    // ------------------------------------------------------------------

    @Test
    void pathConflictReturns422AndPersistsNoPartialLock() {
        register("app", 1, List.of(dep("x", 1, 1), dep("y", 1, 1)));
        register("x", 1, List.of(dep("shared", 1, 1)));
        register("y", 1, List.of(dep("shared", 2, 2)));
        register("shared", 1, List.of());
        register("shared", 2, List.of());
        withdraw("shared", 1);
        withdraw("shared", 2);
        register("alt", 1, List.of());
        publish(rule("shared", "jvm", candidate("alt", 1)));
        long versionBefore = repositoryVersion();

        assertThatThrownBy(() -> service.createLock(rid(),
                new LockRequest("app", 1, "jvm", versionBefore)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));

        assertThat(service.listLocks()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_entry", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_substitution_step", Integer.class)).isZero();
        assertThat(repositoryVersion()).isEqualTo(versionBefore);
    }

    @Test
    void substitutionCycleReturns422AndPersistsNothing() {
        register("app", 1, List.of(dep("a", 1, 1)));
        register("a", 1, List.of());
        withdraw("a", 1);
        register("b", 1, List.of());
        withdraw("b", 1);
        // 直接构造运行期环：静态环规则无法发布，这里用同一终点自指的候选模拟库内异常状态，
        // 因此改为发布无环策略后手工把候选改成指回 a，验证解析器环检测与事务回滚。
        publish(rule("a", "jvm", candidate("b", 1)));
        jdbcTemplate.update("UPDATE substitution_candidate SET coordinate = 'a' WHERE rule_id IN "
                + "(SELECT id FROM substitution_rule WHERE source_pattern = 'a')");

        assertThatThrownBy(() -> service.createLock(rid(),
                new LockRequest("app", 1, "jvm", repositoryVersion())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listLocks()).isEmpty();
    }

    // ------------------------------------------------------------------
    // 历史稳定：策略修改、撤回、恢复不改写已冻结解释
    // ------------------------------------------------------------------

    @Test
    void laterPolicyWithdrawAndRestoreDoNotRewriteFrozenExplanation() {
        register("app", 1, List.of(dep("legacy", 1, 1)));
        register("legacy", 1, List.of());
        withdraw("legacy", 1);
        register("newlib", 1, List.of());
        register("otherlib", 1, List.of());
        publish(rule("legacy", "jvm", candidate("newlib", 1)));
        String lockRequestId = rid();
        LockFileResponse first = service.createLock(lockRequestId,
                new LockRequest("app", 1, "jvm", repositoryVersion()));

        // 策略改版、终点撤回再恢复，仓库持续前进。
        publish(rule("legacy", "jvm", candidate("otherlib", 1)));
        withdraw("newlib", 1);
        service.restoreArtifact(rid(), "newlib", 1);

        LockFileResponse reloaded = service.getLock(first.id());
        assertThat(reloaded.repositoryVersion()).isEqualTo(first.repositoryVersion());
        assertThat(reloaded.policyVersion()).isEqualTo(1L);
        assertThat(reloaded.entries()).isEqualTo(first.entries());
        assertThat(reloaded.substitutionSteps()).usingRecursiveComparison()
                .isEqualTo(first.substitutionSteps());

        // requestId 同参重放首次锁与解释。
        LockFileResponse replay = service.createLock(lockRequestId,
                new LockRequest("app", 1, "jvm", first.repositoryVersion()));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.substitutionSteps()).usingRecursiveComparison()
                .isEqualTo(first.substitutionSteps());
    }

    @Test
    void sameRequestIdDifferentLockParamsReturns409() {
        register("app", 1, List.of());
        String requestId = rid();
        service.createLock(requestId, new LockRequest("app", 1, "jvm", repositoryVersion()));
        assertThatThrownBy(() -> service.createLock(requestId,
                new LockRequest("app", 1, "native", repositoryVersion())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void failedLockDoesNotConsumeRequestId() {
        register("app", 1, List.of(dep("ghost", 1, 1)));
        String requestId = rid();
        assertThatThrownBy(() -> service.createLock(requestId,
                new LockRequest("app", 1, "jvm", repositoryVersion())))
                .isInstanceOf(ApiException.class);

        register("good", 1, List.of());
        LockFileResponse lock = service.createLock(requestId,
                new LockRequest("good", 1, "jvm", repositoryVersion()));
        assertThat(lock.rootName()).isEqualTo("good");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, requestId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 并发边界：策略激活与锁定按提交顺序，锁图不混用两个策略版本
    // ------------------------------------------------------------------

    @Test
    void policyActivationAndLockCommitInSerialOrderWithoutMixedPolicyVersion() throws Exception {
        register("app", 1, List.of(dep("lib", 1, 1)));
        register("lib", 1, List.of());
        final long versionBeforePolicy = 2L;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        String lockRequestId = rid();
        String policyRequestId = rid();

        Callable<Object> lockTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return service.createLock(lockRequestId,
                        new LockRequest("app", 1, "jvm", versionBeforePolicy));
            } catch (ApiException e) {
                return e;
            }
        };
        Callable<Object> policyTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.publishPolicy(policyRequestId,
                    new PublishPolicyRequest(List.of(
                            rule("unrelated", "jvm", candidate("target", 1)))));
        };

        List<Future<Object>> futures = pool.invokeAll(List.of(lockTask, policyTask));
        pool.shutdown();
        Object lockOutcome = futures.get(0).get(30, TimeUnit.SECONDS);
        Object policyOutcome = futures.get(1).get(30, TimeUnit.SECONDS);
        assertThat(policyOutcome).isInstanceOf(PolicyResponse.class);

        if (lockOutcome instanceof LockFileResponse lock) {
            // 锁先提交：读取的 policyVersion 必须是发布前的 null，事后不改写。
            assertThat(lock.policyVersion()).isNull();
            assertThat(service.getLock(lock.id()).policyVersion()).isNull();
        } else {
            // 策略先提交：仓库版本前进，旧期望版本的锁 409。
            assertThat(lockOutcome).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(409));
            assertThat(service.listLocks()).isEmpty();
        }
        // 任一提交顺序下，每个存在的锁只引用一个策略版本，不存在混用。
        for (LockFileResponse lock : service.listLocks()) {
            long distinct = jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT policy_version) FROM lock_substitution_step WHERE lock_file_id = ?",
                    Long.class, lock.id());
            assertThat(distinct).isLessThanOrEqualTo(1L);
        }
    }

    @Test
    void concurrentSameRequestIdLocksResolveToSingleLock() throws Exception {
        register("app", 1, List.of());
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        String requestId = rid();
        LockRequest request = new LockRequest("app", 1, "jvm", 1L);

        List<Future<LockFileResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.createLock(requestId, request);
            }));
        }
        pool.shutdown();
        long firstId = futures.get(0).get(30, TimeUnit.SECONDS).id();
        for (Future<LockFileResponse> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS).id()).isEqualTo(firstId);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE operation = 'CREATE_LOCK'",
                Integer.class)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 数据库唯一约束
    // ------------------------------------------------------------------

    @Test
    void databaseEnforcesCandidateAndStepUniqueConstraints() {
        publish(rule("a", "jvm", candidate("b", 1)));
        Long ruleId = jdbcTemplate.queryForObject(
                "SELECT id FROM substitution_rule WHERE source_pattern = 'a'", Long.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO substitution_candidate (rule_id, priority, coordinate) VALUES (?, ?, ?)",
                ruleId, 1, "dup"))
                .isInstanceOf(DuplicateKeyException.class);

        register("app", 1, List.of());
        LockFileResponse lock = service.createLock(rid(), new LockRequest("app", 1, "jvm", repositoryVersion()));
        jdbcTemplate.update("INSERT INTO lock_substitution_step (lock_file_id, step_order, "
                + "original_coordinate, source_pattern, platform, final_coordinate, policy_version) "
                + "VALUES (?, 0, 'x', 'x', 'jvm', 'y', 1)", lock.id());
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO lock_substitution_step (lock_file_id, step_order, "
                        + "original_coordinate, source_pattern, platform, final_coordinate, policy_version) "
                        + "VALUES (?, 0, 'z', 'z', 'jvm', 'q', 1)", lock.id()))
                .isInstanceOf(DuplicateKeyException.class);
    }
}
