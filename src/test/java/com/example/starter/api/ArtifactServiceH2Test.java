package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockPeerMatchResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.PeerSpec;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.WithdrawRequest;
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
 * 基于嵌入式 H2（MODE=MySQL）的服务层数据库与业务测试：
 * 覆盖主流程、失败分支、幂等重放、事务回滚、peer 求解以及锁定/撤回/幂等的真实并发边界。
 */
@SpringBootTest
class ArtifactServiceH2Test {

    @Autowired
    private ArtifactService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM lock_file_peer_match");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_peer");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }

    private RegisterArtifactRequest artifact(String name, int version, String operator,
                                             List<DependencySpec> deps, List<PeerSpec> peers) {
        return new RegisterArtifactRequest(name, version, operator, deps, peers);
    }

    private RegisterArtifactRequest artifact(String name, int version, DependencySpec... deps) {
        return artifact(name, version, "alice", List.of(deps), List.of());
    }

    private static DependencySpec dep(String name, int min, int max) {
        return new DependencySpec(name, min, max);
    }

    private static PeerSpec peer(String name, int min, int max) {
        return new PeerSpec(name, min, max);
    }

    private static WithdrawRequest withdraw(String operator, long expectedVersion) {
        return new WithdrawRequest(operator, expectedVersion);
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    @Test
    void registerIncrementsRepositoryVersionAndPersistsDependencies() {
        ArtifactResponse a1 = service.registerArtifact(requestId(),
                artifact("app", 1, "bob", List.of(dep("lib", 1, 2)), List.of()));
        assertThat(a1.repositoryVersion()).isEqualTo(1);
        assertThat(a1.withdrawn()).isFalse();
        assertThat(a1.operator()).isEqualTo("bob");
        assertThat(a1.dependencies()).extracting("name", "minimumVersion", "maximumVersion")
                .containsExactly(tuple("lib", 1, 2));

        ArtifactResponse a2 = service.registerArtifact(requestId(),
                artifact("lib", 2, "bob", List.of(), List.of()));
        assertThat(a2.repositoryVersion()).isEqualTo(2);
    }

    @Test
    void registerWithPeerPersistsPeerAndOperator() {
        ArtifactResponse saved = service.registerArtifact(requestId(),
                artifact("p", 2, "carol", List.of(),
                        List.of(peer("h", 1, 2), peer("z", 1, 1))));
        assertThat(saved.peers()).extracting("name", "minimumVersion", "maximumVersion")
                .containsExactly(tuple("h", 1, 2), tuple("z", 1, 1));

        Integer peerRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_peer", Integer.class);
        assertThat(peerRows).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT operator FROM artifact WHERE name = 'p'", String.class)).isEqualTo("carol");
    }

    @Test
    void lockProducesSortedEntriesAndPersistsRepositoryVersion() {
        // app:1 -> lib[1,2] 且 util[1,1]；lib2/lib1 均在，取高版本 lib2。
        service.registerArtifact(requestId(),
                artifact("app", 1, "alice",
                        List.of(dep("util", 1, 1), dep("lib", 1, 2)), List.of()));
        service.registerArtifact(requestId(), artifact("lib", 2));
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerArtifact(requestId(), artifact("util", 1));

        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("app", 1, "dave", 4L));

        assertThat(lock.repositoryVersion()).isEqualTo(4L);
        assertThat(lock.operator()).isEqualTo("dave");
        assertThat(lock.rootName()).isEqualTo("app");
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 2), tuple("util", 1));

        List<LockFileResponse> locks = service.listLocks();
        assertThat(locks).hasSize(1);
        assertThat(service.getLock(lock.id()).entries()).isEqualTo(lock.entries());
    }

    @Test
    void lockFreezesPeerMatchesAndSyntheticScenarioBacktracksToP1() {
        // 合成：根 root:1 普通依赖宿主 H[1,1] 与插件 P[1,2]；
        // P2 peer 要求 H2，P1 peer 要求 H1 → 必须回退 P1，冻结匹配依据。
        service.registerArtifact(requestId(), artifact("root", 1, "alice",
                List.of(dep("h", 1, 1), dep("p", 1, 2)), List.of()));
        service.registerArtifact(requestId(), artifact("h", 1, "alice", List.of(), List.of()));
        service.registerArtifact(requestId(), artifact("p", 2, "alice", List.of(),
                List.of(peer("h", 2, 2))));
        service.registerArtifact(requestId(), artifact("p", 1, "alice", List.of(),
                List.of(peer("h", 1, 1))));

        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("root", 1, "dave", 4L));

        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("h", 1), tuple("p", 1), tuple("root", 1));
        assertThat(lock.peerMatches()).hasSize(1);
        LockPeerMatchResponse match = lock.peerMatches().get(0);
        assertThat(match.declarerName()).isEqualTo("p");
        assertThat(match.declarerVersion()).isEqualTo(1);
        assertThat(match.hostName()).isEqualTo("h");
        assertThat(match.minimumVersion()).isEqualTo(1);
        assertThat(match.maximumVersion()).isEqualTo(1);
        assertThat(match.matchedVersion()).isEqualTo(1);

        // 匹配依据落库；历史查询一致。
        Integer frozen = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file_peer_match WHERE declarer_name = 'p' "
                        + "AND declarer_version = 1 AND host_name = 'h' AND matched_version = 1",
                Integer.class);
        assertThat(frozen).isEqualTo(1);
        assertThat(service.getLock(lock.id()).peerMatches()).isEqualTo(lock.peerMatches());
    }

    @Test
    void removingNormalHostDependencyMakesLockInfeasibleWithoutAutoAddingHost() {
        // P 两个版本都 peer 要求 H[1,2]，但根只普通依赖 P（无 H 普通边）：
        // peer 不能自行引入 H → 422，且锁结果中不会出现 H。
        service.registerArtifact(requestId(), artifact("root", 1, "alice",
                List.of(dep("p", 1, 2)), List.of()));
        service.registerArtifact(requestId(), artifact("h", 1));
        service.registerArtifact(requestId(), artifact("p", 2, "alice", List.of(),
                List.of(peer("h", 1, 2))));
        service.registerArtifact(requestId(), artifact("p", 1, "alice", List.of(),
                List.of(peer("h", 1, 2))));

        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("root", 1, "dave", 4L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listLocks()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file_peer_match", Integer.class)).isZero();
    }

    @Test
    void laterWithdrawDoesNotChangeOldLockAndChecksSameExpectedVersion() {
        service.registerArtifact(requestId(), artifact("app", 1, "alice",
                List.of(dep("lib", 1, 1)), List.of()));
        service.registerArtifact(requestId(), artifact("lib", 1, "alice", List.of(),
                List.of(peer("app", 1, 1))));
        LockFileResponse first = service.createLock(requestId(),
                new LockRequest("app", 1, "dave", 2L));

        // 撤回必须核对同一 expectedRepositoryVersion：过期版本 → 409。
        assertThatThrownBy(() -> service.withdrawArtifact(requestId(), "lib", 1,
                withdraw("eve", 1L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));

        // 正确版本撤回成功。
        ArtifactResponse withdrawn = service.withdrawArtifact(requestId(), "lib", 1,
                withdraw("eve", 2L));
        assertThat(withdrawn.repositoryVersion()).isEqualTo(3L);
        assertThat(withdrawn.operator()).isEqualTo("eve");

        // 旧锁内容与冻结的 peer 匹配依据不随后续撤回改变。
        LockFileResponse old = service.getLock(first.id());
        assertThat(old.repositoryVersion()).isEqualTo(2L);
        assertThat(old.entries()).isEqualTo(first.entries());
        assertThat(old.peerMatches()).isEqualTo(first.peerMatches());
    }

    @Test
    void lockWithdrawConflictConcurrencyLeavesQueryableLock() throws Exception {
        // 串行准备：app:1 -> lib[1,1]，lib:1；当前仓库版本为 2。
        service.registerArtifact(requestId(), artifact("app", 1));
        service.registerArtifact(requestId(), artifact("lib", 1));
        long versionBefore = 2L;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        String lockRequestId = requestId();
        String withdrawRequestId = requestId();

        Callable<Object> lockTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return service.createLock(lockRequestId,
                        new LockRequest("app", 1, "dave", versionBefore));
            } catch (ApiException e) {
                return e;
            }
        };
        Callable<Object> withdrawTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return service.withdrawArtifact(withdrawRequestId, "lib", 1,
                        withdraw("eve", versionBefore));
            } catch (ApiException e) {
                return e;
            }
        };

        List<Future<Object>> futures = pool.invokeAll(List.of(lockTask, withdrawTask));
        pool.shutdown();
        Object lockOutcome = futures.get(0).get(30, TimeUnit.SECONDS);
        Object withdrawOutcome = futures.get(1).get(30, TimeUnit.SECONDS);

        // 行锁串行化：锁定不推进仓库版本。
        // 撤回先提交 → 版本变 3，锁核对版本 2 失败（409），仅撤回成功；
        // 锁先提交 → 版本仍为 2，锁成功且撤回核对版本 2 同样成功，最终版本 3。
        long finalVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
        assertThat(finalVersion).isEqualTo(versionBefore + 1);
        assertThat(withdrawOutcome).isInstanceOf(ArtifactResponse.class);

        if (lockOutcome instanceof LockFileResponse lock) {
            // 锁先提交：即使 lib 后被撤回，锁文件仍可查询、内容不改写。
            assertThat(lock.entries()).extracting("name", "version")
                    .containsExactly(tuple("app", 1), tuple("lib", 1));
            LockFileResponse queried = service.getLock(lock.id());
            assertThat(queried.repositoryVersion()).isEqualTo(versionBefore);
            assertThat(queried.entries()).isEqualTo(lock.entries());
        } else {
            // 撤回先提交：期望版本过期，锁必须 409，且无半成品。
            assertThat(lockOutcome).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(409));
            assertThat(service.listLocks()).isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // 失败分支
    // ------------------------------------------------------------------

    @Test
    void duplicateArtifactVersionReturns409() {
        service.registerArtifact(requestId(), artifact("app", 1));
        assertThatThrownBy(() -> service.registerArtifact(requestId(), artifact("app", 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void blankOperatorReturns400AndIsEnforcedInService() {
        // 不经 Bean Validation，直接验证 Service 不丢 operator 字段。
        assertThatThrownBy(() -> service.registerArtifact(requestId(),
                new RegisterArtifactRequest("app", 1, "  ", List.of(), List.of())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, null, 0L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void withdrawMissingArtifactReturns404() {
        assertThatThrownBy(() -> service.withdrawArtifact(requestId(), "ghost", 9,
                withdraw("eve", 0L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void doubleWithdrawReturns409AndSecondWithdrawConsumesNoKey() {
        service.registerArtifact(requestId(), artifact("app", 1));
        service.withdrawArtifact(requestId(), "app", 1, withdraw("eve", 1L));
        long versionAfterFirstWithdraw = jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);

        assertThatThrownBy(() -> service.withdrawArtifact(requestId(), "app", 1,
                withdraw("eve", 2L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class))
                .isEqualTo(versionAfterFirstWithdraw);
    }

    @Test
    void withdrawWithStaleExpectedRepositoryVersionReturns409WithoutChange() {
        service.registerArtifact(requestId(), artifact("app", 1));
        assertThatThrownBy(() -> service.withdrawArtifact(requestId(), "app", 1,
                withdraw("eve", 0L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT withdrawn FROM artifact WHERE name = 'app' AND version = 1",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class)).isEqualTo(1L);
    }

    @Test
    void lockWithStaleExpectedRepositoryVersionReturns409WithoutSaving() {
        service.registerArtifact(requestId(), artifact("app", 1));
        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, "dave", 0L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(service.listLocks()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_entry", Integer.class))
                .isZero();
    }

    @Test
    void lockOnWithdrawnRootReturns409() {
        service.registerArtifact(requestId(), artifact("app", 1));
        service.withdrawArtifact(requestId(), "app", 1, withdraw("eve", 1L));
        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, "dave", 2L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void lockOnMissingRootReturns404() {
        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("ghost", 1, "dave", 0L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void infeasibleLockReturns422AndPersistsNothing() {
        service.registerArtifact(requestId(), artifact("app", 1, "alice",
                List.of(dep("lib", 2, 2)), List.of()));
        service.registerArtifact(requestId(), artifact("lib", 1));

        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, "dave", 2L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listLocks()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_entry", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class)).isEqualTo(2L);
    }

    @Test
    void registeringTwentyFirstNameReturns422() {
        for (int i = 1; i <= 20; i++) {
            service.registerArtifact(requestId(), artifact("name" + i, 1));
        }
        assertThatThrownBy(() -> service.registerArtifact(requestId(),
                artifact("overflow", 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void registeringSixthVersionOfNameReturns422() {
        for (int v = 1; v <= 5; v++) {
            service.registerArtifact(requestId(), artifact("app", v));
        }
        assertThatThrownBy(() -> service.registerArtifact(requestId(), artifact("app", 6)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void invalidDependencyRangeReturns400() {
        assertThatThrownBy(() -> service.registerArtifact(requestId(),
                artifact("app", 1, "alice", List.of(dep("lib", 3, 2)), List.of())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void duplicateDependencyNamesReturn400() {
        assertThatThrownBy(() -> service.registerArtifact(requestId(),
                artifact("app", 1, "alice",
                        List.of(dep("lib", 1, 1), dep("lib", 2, 2)), List.of())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void moreThanTenDependenciesReturn400() {
        List<DependencySpec> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(dep("lib" + i, 1, 1));
        }
        assertThatThrownBy(() -> service.registerArtifact(requestId(),
                artifact("app", 1, "alice", eleven, List.of())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void moreThanThreePeersReturn400() {
        List<PeerSpec> four = List.of(peer("a", 1, 1), peer("b", 1, 1),
                peer("c", 1, 1), peer("d", 1, 1));
        assertThatThrownBy(() -> service.registerArtifact(requestId(),
                artifact("app", 1, "alice", List.of(), four)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void peerSameNameAsArtifactItselfReturns400() {
        assertThatThrownBy(() -> service.registerArtifact(requestId(),
                artifact("app", 1, "alice", List.of(), List.of(peer("app", 1, 1)))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void duplicatePeerNamesReturn400() {
        assertThatThrownBy(() -> service.registerArtifact(requestId(),
                artifact("app", 1, "alice", List.of(),
                        List.of(peer("h", 1, 1), peer("h", 2, 2)))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    // ------------------------------------------------------------------
    // 幂等：重放首次完整响应、改参 409、失败不占键、结构化指纹
    // ------------------------------------------------------------------

    @Test
    void sameRequestIdAndParamsReplaysOriginalResult() {
        String rid = requestId();
        RegisterArtifactRequest request = artifact("app", 1, "alice",
                List.of(dep("lib", 1, 1)), List.of());
        ArtifactResponse first = service.registerArtifact(rid, request);
        ArtifactResponse replay = service.registerArtifact(rid, request);

        assertThat(replay.repositoryVersion()).isEqualTo(first.repositoryVersion());
        assertThat(replay.createdAt()).isEqualTo(first.createdAt());
        assertThat(replay.operator()).isEqualTo(first.operator());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact WHERE name = 'app'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class)).isEqualTo(1L);
    }

    @Test
    void peerDeclarationReorderIsSameParametersAndReplays() {
        String rid = requestId();
        RegisterArtifactRequest first = artifact("p", 1, "alice", List.of(),
                List.of(peer("h", 1, 1), peer("z", 2, 2)));
        RegisterArtifactRequest reordered = artifact("p", 1, "alice", List.of(),
                List.of(peer("z", 2, 2), peer("h", 1, 1)));

        ArtifactResponse saved = service.registerArtifact(rid, first);
        ArtifactResponse replay = service.registerArtifact(rid, reordered);
        assertThat(replay.createdAt()).isEqualTo(saved.createdAt());
        // 响应与落库均按名称升序。
        assertThat(replay.peers()).extracting("name")
                .containsExactly("h", "z");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact WHERE name = 'p'", Integer.class)).isEqualTo(1);
    }

    @Test
    void sameRequestIdDifferentOperatorReturns409() {
        String rid = requestId();
        service.registerArtifact(rid, artifact("app", 1, "alice", List.of(), List.of()));
        assertThatThrownBy(() -> service.registerArtifact(rid,
                artifact("app", 1, "bob", List.of(), List.of())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void sameRequestIdDifferentPeerRangeReturns409() {
        String rid = requestId();
        service.registerArtifact(rid, artifact("p", 1, "alice", List.of(),
                List.of(peer("h", 1, 1))));
        assertThatThrownBy(() -> service.registerArtifact(rid,
                artifact("p", 1, "alice", List.of(), List.of(peer("h", 1, 2)))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void sameRequestIdDifferentParamsReturns409() {
        String rid = requestId();
        service.registerArtifact(rid, artifact("app", 1));
        assertThatThrownBy(() -> service.registerArtifact(rid, artifact("app", 2)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void sameRequestIdAcrossOperationsReturns409() {
        String rid = requestId();
        service.registerArtifact(rid, artifact("app", 1));
        assertThatThrownBy(() -> service.withdrawArtifact(rid, "app", 1, withdraw("eve", 1L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void sameLockRequestIdDifferentExpectedVersionReturns409() {
        service.registerArtifact(requestId(), artifact("app", 1));
        String rid = requestId();
        LockFileResponse first = service.createLock(rid, new LockRequest("app", 1, "dave", 1L));
        // 重放原参返回首次响应；改期望版本 → 409。
        assertThat(service.createLock(rid, new LockRequest("app", 1, "dave", 1L)).id())
                .isEqualTo(first.id());
        assertThatThrownBy(() -> service.createLock(rid,
                new LockRequest("app", 1, "dave", 2L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void failedBusinessCallDoesNotConsumeRequestId() {
        String rid = requestId();
        for (int v = 1; v <= 5; v++) {
            service.registerArtifact(requestId(), artifact("app", v));
        }
        assertThatThrownBy(() -> service.registerArtifact(rid, artifact("app", 6)))
                .isInstanceOf(ApiException.class);

        ArtifactResponse later = service.registerArtifact(rid, artifact("other", 1));
        assertThat(later.name()).isEqualTo("other");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
    }

    @Test
    void infeasibleLockDoesNotConsumeRequestId() {
        service.registerArtifact(requestId(), artifact("app", 1, "alice",
                List.of(dep("lib", 2, 2)), List.of()));
        service.registerArtifact(requestId(), artifact("lib", 1));
        String rid = requestId();
        assertThatThrownBy(() -> service.createLock(rid,
                new LockRequest("app", 1, "dave", 2L)))
                .isInstanceOf(ApiException.class);

        // 失败不占键：登记 lib2 后同一 requestId 可成功锁定。
        service.registerArtifact(requestId(), artifact("lib", 2));
        LockFileResponse lock = service.createLock(rid,
                new LockRequest("app", 1, "dave", 3L));
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 2));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 真实并发：同参重放唯一结果；异参一胜余 409，不暴露唯一键错误
    // ------------------------------------------------------------------

    @Test
    void concurrentSameRequestIdSamePayloadResolvesToSingleResult() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        String rid = requestId();
        RegisterArtifactRequest request = artifact("concurrent", 1);

        List<Future<ArtifactResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.registerArtifact(rid, request);
            }));
        }
        pool.shutdown();

        ArtifactResponse first = futures.get(0).get(30, TimeUnit.SECONDS);
        for (Future<ArtifactResponse> future : futures) {
            ArtifactResponse response = future.get(30, TimeUnit.SECONDS);
            assertThat(response.repositoryVersion()).isEqualTo(first.repositoryVersion());
            assertThat(response.createdAt()).isEqualTo(first.createdAt());
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact WHERE name = 'concurrent'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestIdDifferentPayloadOneWinsRestConflict() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        String rid = requestId();

        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    service.registerArtifact(rid, artifact("v" + idx, 1));
                    return 201;
                } catch (ApiException e) {
                    // 竞争方只应看到业务 409，不应是 DataIntegrityViolation（唯一键错误泄漏）。
                    assertThat(e.getStatus()).isEqualTo(409);
                    return e.getStatus();
                }
            }));
        }
        pool.shutdown();

        int success = 0;
        int conflict = 0;
        for (Future<Integer> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 201) {
                success++;
            } else {
                conflict++;
            }
        }
        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact", Integer.class)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 数据库边界：唯一约束与冻结数据实际生效
    // ------------------------------------------------------------------

    @Test
    void uniqueConstraintOnNameAndVersionIsEnforcedByDatabase() {
        jdbcTemplate.update("INSERT INTO artifact (name, version, withdrawn, operator, created_at) "
                + "VALUES ('x', 1, 0, 'op', CURRENT_TIMESTAMP(6))");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO artifact (name, version, withdrawn, operator, created_at) "
                        + "VALUES ('x', 1, 0, 'op', CURRENT_TIMESTAMP(6))"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void peerUniquePerArtifactIsEnforcedByDatabase() {
        jdbcTemplate.update("INSERT INTO artifact (name, version, withdrawn, operator, created_at) "
                + "VALUES ('p', 1, 0, 'op', CURRENT_TIMESTAMP(6))");
        Long artifactId = jdbcTemplate.queryForObject(
                "SELECT id FROM artifact WHERE name = 'p'", Long.class);
        jdbcTemplate.update("INSERT INTO artifact_peer (artifact_id, name, minimum_version, maximum_version) "
                + "VALUES (?, 'h', 1, 1)", artifactId);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO artifact_peer (artifact_id, name, minimum_version, maximum_version) "
                        + "VALUES (?, 'h', 2, 2)", artifactId))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void withdrawnArtifactVersionRemainsAndBlocksReRegistration() {
        service.registerArtifact(requestId(), artifact("app", 1));
        service.withdrawArtifact(requestId(), "app", 1, withdraw("eve", 1L));

        Integer withdrawnFlag = jdbcTemplate.queryForObject(
                "SELECT withdrawn FROM artifact WHERE name = 'app' AND version = 1",
                Integer.class);
        assertThat(withdrawnFlag).isEqualTo(1);

        assertThatThrownBy(() -> service.registerArtifact(requestId(), artifact("app", 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void lockReplayAfterUnrelatedChangesStillReturnsOriginalResolution() {
        service.registerArtifact(requestId(), artifact("app", 1, "alice",
                List.of(dep("lib", 1, 2)), List.of()));
        service.registerArtifact(requestId(), artifact("lib", 1));
        String rid = requestId();
        LockFileResponse first = service.createLock(rid,
                new LockRequest("app", 1, "dave", 2L));

        service.registerArtifact(requestId(), artifact("lib", 2));
        service.withdrawArtifact(requestId(), "lib", 1, withdraw("eve", 3L));

        LockFileResponse replay = service.createLock(rid,
                new LockRequest("app", 1, "dave", 2L));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.repositoryVersion()).isEqualTo(2L);
        assertThat(replay.entries()).isEqualTo(first.entries());
    }
}
