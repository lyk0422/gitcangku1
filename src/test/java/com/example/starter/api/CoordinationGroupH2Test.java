package com.example.starter.api;

import com.example.starter.api.dto.CoordinationGroupRequest;
import com.example.starter.api.dto.CoordinationGroupResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.TupleEntrySpec;
import com.example.starter.support.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

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
 * 协调组（厂商验证版本元组）的 H2 数据库测试：
 * 登记/修订、校验分支、幂等重放、锁定裁决与并发边界。
 */
@SpringBootTest
class CoordinationGroupH2Test {

    @Autowired
    private ArtifactService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM lock_coordination_hit");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM coordination_tuple_entry");
        jdbcTemplate.update("DELETE FROM coordination_tuple");
        jdbcTemplate.update("DELETE FROM coordination_group_member");
        jdbcTemplate.update("DELETE FROM coordination_group");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }

    private void register(String name, int version) {
        service.registerArtifact(requestId(),
                new RegisterArtifactRequest(name, version, List.of()));
    }

    private static TupleEntrySpec e(String name, int version) {
        return new TupleEntrySpec(name, version);
    }

    private static CoordinationGroupRequest groupRequest(String groupId, long expected,
                                                         List<String> members,
                                                         List<List<TupleEntrySpec>> tuples) {
        return new CoordinationGroupRequest(groupId, "tester", members, tuples, expected);
    }

    private long repositoryVersion() {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
    }

    /** 登记 x1/x2/y1/y2 四个版本（仓库版本推进到 4）。 */
    private void prepareXY() {
        register("x", 1);
        register("x", 2);
        register("y", 1);
        register("y", 2);
    }

    // ------------------------------------------------------------------
    // 登记与修订
    // ------------------------------------------------------------------

    @Test
    void upsertCreatesGroupAndAdvancesRepositoryVersion() {
        prepareXY();
        CoordinationGroupResponse response = service.upsertCoordinationGroup(requestId(),
                groupRequest("g1", 4L, List.of("y", "x"),
                        List.of(List.of(e("y", 2), e("x", 1)), List.of(e("x", 2), e("y", 1)))));

        assertThat(response.repositoryVersion()).isEqualTo(5L);
        assertThat(response.operator()).isEqualTo("tester");
        // 成员与元组按规范化顺序返回（换序同参）。
        assertThat(response.members()).containsExactly("x", "y");
        assertThat(response.tuples()).hasSize(2);
        assertThat(response.tuples().get(0))
                .extracting("name", "version")
                .containsExactly(tuple("x", 1), tuple("y", 2));
        assertThat(repositoryVersion()).isEqualTo(5L);

        List<CoordinationGroupResponse> groups = service.listCoordinationGroups();
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).repositoryVersion()).isEqualTo(5L);
    }

    @Test
    void fourthGroupReturns422() {
        register("a", 1);
        register("b", 1);
        for (int i = 1; i <= 3; i++) {
            service.upsertCoordinationGroup(requestId(),
                    groupRequest("g" + i, repositoryVersion(), List.of("a", "b"),
                            List.of(List.of(e("a", 1), e("b", 1)))));
        }
        assertThatThrownBy(() -> service.upsertCoordinationGroup(requestId(),
                groupRequest("g4", repositoryVersion(), List.of("a", "b"),
                        List.of(List.of(e("a", 1), e("b", 1))))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listCoordinationGroups()).hasSize(3);
    }

    @Test
    void revisionReplacesContentAndAdvancesVersion() {
        prepareXY();
        service.upsertCoordinationGroup(requestId(),
                groupRequest("g1", 4L, List.of("x", "y"),
                        List.of(List.of(e("x", 1), e("y", 2)))));
        CoordinationGroupResponse revised = service.upsertCoordinationGroup(requestId(),
                groupRequest("g1", 5L, List.of("x", "y"),
                        List.of(List.of(e("x", 2), e("y", 1)))));

        assertThat(revised.repositoryVersion()).isEqualTo(6L);
        List<CoordinationGroupResponse> groups = service.listCoordinationGroups();
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).tuples()).hasSize(1);
        assertThat(groups.get(0).tuples().get(0))
                .extracting("name", "version")
                .containsExactly(tuple("x", 2), tuple("y", 1));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM coordination_tuple", Integer.class)).isEqualTo(1);
    }

    @Test
    void staleExpectedRepositoryVersionReturns409AndKeepsRules() {
        prepareXY();
        assertThatThrownBy(() -> service.upsertCoordinationGroup(requestId(),
                groupRequest("g1", 0L, List.of("x", "y"),
                        List.of(List.of(e("x", 1), e("y", 2))))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        // 409 不改变规则：无组写入，版本不推进。
        assertThat(service.listCoordinationGroups()).isEmpty();
        assertThat(repositoryVersion()).isEqualTo(4L);
    }

    // ------------------------------------------------------------------
    // 请求校验分支
    // ------------------------------------------------------------------

    @Test
    void duplicateMembersReturn400() {
        assertThatThrownBy(() -> service.upsertCoordinationGroup(requestId(),
                groupRequest("g1", 0L, List.of("x", "x"),
                        List.of(List.of(e("x", 1), e("x", 2))))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void singleMemberReturns400() {
        assertThatThrownBy(() -> service.upsertCoordinationGroup(requestId(),
                groupRequest("g1", 0L, List.of("x"),
                        List.of(List.of(e("x", 1))))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void tupleNotCoveringAllMembersReturns400() {
        assertThatThrownBy(() -> service.upsertCoordinationGroup(requestId(),
                groupRequest("g1", 0L, List.of("x", "y"),
                        List.of(List.of(e("x", 1))))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void tupleWithOutsideMemberReturns400() {
        assertThatThrownBy(() -> service.upsertCoordinationGroup(requestId(),
                groupRequest("g1", 0L, List.of("x", "y"),
                        List.of(List.of(e("x", 1), e("z", 1))))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void duplicateTuplesReturn400() {
        assertThatThrownBy(() -> service.upsertCoordinationGroup(requestId(),
                groupRequest("g1", 0L, List.of("x", "y"),
                        List.of(List.of(e("x", 1), e("y", 2)),
                                List.of(e("y", 2), e("x", 1))))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void blankOperatorReturns400() {
        assertThatThrownBy(() -> service.upsertCoordinationGroup(requestId(),
                new CoordinationGroupRequest("g1", "  ", List.of("x", "y"),
                        List.of(List.of(e("x", 1), e("y", 1))), 0L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void tupleReferencingMissingVersionReturns404() {
        register("x", 1);
        register("y", 1);
        assertThatThrownBy(() -> service.upsertCoordinationGroup(requestId(),
                groupRequest("g1", 2L, List.of("x", "y"),
                        List.of(List.of(e("x", 1), e("y", 9))))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
        // 404 失败不写组、不推进版本。
        assertThat(service.listCoordinationGroups()).isEmpty();
        assertThat(repositoryVersion()).isEqualTo(2L);
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void sameRequestIdWithReorderedParamsReplaysOriginalResponse() {
        prepareXY();
        String rid = requestId();
        CoordinationGroupResponse first = service.upsertCoordinationGroup(rid,
                groupRequest("g1", 4L, List.of("x", "y"),
                        List.of(List.of(e("x", 1), e("y", 2)), List.of(e("x", 2), e("y", 1)))));
        // 成员与元组列表换序、元组内条目换序：视为同参，返回首次完整响应。
        CoordinationGroupResponse replay = service.upsertCoordinationGroup(rid,
                groupRequest("g1", 4L, List.of("y", "x"),
                        List.of(List.of(e("y", 1), e("x", 2)), List.of(e("y", 2), e("x", 1)))));

        assertThat(replay.createdAt()).isEqualTo(first.createdAt());
        assertThat(replay.repositoryVersion()).isEqualTo(first.repositoryVersion());
        assertThat(repositoryVersion()).isEqualTo(5L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM coordination_group", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
    }

    @Test
    void sameRequestIdDifferentParamsReturns409() {
        prepareXY();
        String rid = requestId();
        service.upsertCoordinationGroup(rid,
                groupRequest("g1", 4L, List.of("x", "y"),
                        List.of(List.of(e("x", 1), e("y", 2)))));
        assertThatThrownBy(() -> service.upsertCoordinationGroup(rid,
                groupRequest("g1", 4L, List.of("x", "y"),
                        List.of(List.of(e("x", 2), e("y", 1))))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void failedUpsertDoesNotConsumeRequestId() {
        prepareXY();
        String rid = requestId();
        // 引用不存在版本失败（404）不占键。
        assertThatThrownBy(() -> service.upsertCoordinationGroup(rid,
                groupRequest("g1", 4L, List.of("x", "y"),
                        List.of(List.of(e("x", 1), e("y", 9))))))
                .isInstanceOf(ApiException.class);
        CoordinationGroupResponse later = service.upsertCoordinationGroup(rid,
                groupRequest("g1", 4L, List.of("x", "y"),
                        List.of(List.of(e("x", 1), e("y", 2)))));
        assertThat(later.groupId()).isEqualTo("g1");
    }

    // ------------------------------------------------------------------
    // 锁定裁决与并发
    // ------------------------------------------------------------------

    /** 登记 y2 -> x[1,2]，再登记 x1/x2/y1（仓库版本推进到 4）。 */
    private void prepareXYWithDependency() {
        service.registerArtifact(requestId(), new RegisterArtifactRequest("y", 2,
                List.of(new com.example.starter.api.dto.DependencySpec("x", 1, 2))));
        register("y", 1);
        register("x", 1);
        register("x", 2);
    }

    @Test
    void lockBacktracksToTupleProjectionAndFreezesHits() {
        // 合成场景：允许 (X1,Y2) 与 (X2,Y1)，根固定 Y2、X 范围 1..2，应回退 X1。
        prepareXYWithDependency();
        service.upsertCoordinationGroup(requestId(),
                groupRequest("g1", 4L, List.of("x", "y"),
                        List.of(List.of(e("x", 1), e("y", 2)), List.of(e("x", 2), e("y", 1)))));

        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("y", 2, 5L));

        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("x", 1), tuple("y", 2));
        assertThat(lock.coordinationHits()).hasSize(1);
        assertThat(lock.coordinationHits().get(0).groupId()).isEqualTo("g1");
        assertThat(lock.coordinationHits().get(0).tuple())
                .extracting("name", "version")
                .containsExactly(tuple("x", 1), tuple("y", 2));

        // 历史查询同样返回冻结的命中依据。
        LockFileResponse queried = service.getLock(lock.id());
        assertThat(queried.coordinationHits()).isEqualTo(lock.coordinationHits());
    }

    @Test
    void lockInfeasibleByGroupReturns422WithoutSaving() {
        // 只允许 (X1,Y1)，根固定 Y2：无解。
        prepareXYWithDependency();
        service.upsertCoordinationGroup(requestId(),
                groupRequest("g1", 4L, List.of("x", "y"),
                        List.of(List.of(e("x", 1), e("y", 1)))));

        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("y", 2, 5L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listLocks()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_coordination_hit", Integer.class)).isZero();
        assertThat(repositoryVersion()).isEqualTo(5L);
    }

    @Test
    void historicalLockUnchangedAfterGroupRevision() {
        prepareXYWithDependency();
        service.upsertCoordinationGroup(requestId(),
                groupRequest("g1", 4L, List.of("x", "y"),
                        List.of(List.of(e("x", 1), e("y", 2)), List.of(e("x", 2), e("y", 1)))));
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("y", 2, 5L));

        // 修订组：删除原命中元组，仅保留 (X2,Y1)。
        service.upsertCoordinationGroup(requestId(),
                groupRequest("g1", 5L, List.of("x", "y"),
                        List.of(List.of(e("x", 2), e("y", 1)))));

        LockFileResponse historical = service.getLock(lock.id());
        assertThat(historical.entries()).extracting("name", "version")
                .containsExactly(tuple("x", 1), tuple("y", 2));
        assertThat(historical.coordinationHits()).isEqualTo(lock.coordinationHits());
    }

    @Test
    void withdrawnTupleVersionIsNotSelectableForNewLock() {
        // 元组 (X1,Y2) 保留，但 X1 撤回后新锁不能选它：无解 422。
        prepareXYWithDependency();
        service.upsertCoordinationGroup(requestId(),
                groupRequest("g1", 4L, List.of("x", "y"),
                        List.of(List.of(e("x", 1), e("y", 2)))));
        service.withdrawArtifact(requestId(), "x", 1);

        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("y", 2, 6L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        // 历史元组保留（组内容不因撤回而改变）。
        assertThat(service.listCoordinationGroups()).hasSize(1);
        assertThat(service.listCoordinationGroups().get(0).tuples()).hasSize(1);
    }

    @Test
    void concurrentLockAndGroupRevisionShareSingleVersionView() throws Exception {
        // 锁定与组修订在同一仓库版本视图裁决：一个先提交，另一个看到推进后的版本。
        prepareXYWithDependency();
        service.upsertCoordinationGroup(requestId(),
                groupRequest("g1", 4L, List.of("x", "y"),
                        List.of(List.of(e("x", 1), e("y", 2)), List.of(e("x", 2), e("y", 1)))));
        long versionBefore = repositoryVersion();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Object> lockTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return service.createLock(requestId(), new LockRequest("y", 2, versionBefore));
            } catch (ApiException e) {
                return e;
            }
        };
        Callable<Object> reviseTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.upsertCoordinationGroup(requestId(),
                    groupRequest("g1", versionBefore, List.of("x", "y"),
                            List.of(List.of(e("x", 2), e("y", 1)))));
        };

        List<Future<Object>> futures = pool.invokeAll(List.of(lockTask, reviseTask));
        pool.shutdown();
        Object lockOutcome = futures.get(0).get(30, TimeUnit.SECONDS);
        Object reviseOutcome = futures.get(1).get(30, TimeUnit.SECONDS);

        assertThat(reviseOutcome).isInstanceOf(CoordinationGroupResponse.class);
        assertThat(repositoryVersion()).isEqualTo(versionBefore + 1);
        if (lockOutcome instanceof LockFileResponse lock) {
            // 锁先提交：命中依据冻结，之后修订不影响。
            assertThat(lock.entries()).extracting("name", "version")
                    .containsExactly(tuple("x", 1), tuple("y", 2));
            assertThat(service.getLock(lock.id()).coordinationHits())
                    .isEqualTo(lock.coordinationHits());
        } else {
            // 修订先提交：锁的期望版本过期，必须 409 且无半成品。
            assertThat(lockOutcome).isInstanceOf(ApiException.class);
            assertThat(((ApiException) lockOutcome).getStatus()).isEqualTo(409);
            assertThat(service.listLocks()).isEmpty();
        }
    }
}
