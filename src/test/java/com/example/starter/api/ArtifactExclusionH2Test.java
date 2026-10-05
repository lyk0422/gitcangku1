package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.LockExclusionResponse;
import com.example.starter.api.dto.LockEdgeResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.support.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 边级路径排除的真实 H2 业务测试：登记校验、合成场景锁定、
 * 撤回后失败、锁文件解释持久化与幂等身份。
 */
@SpringBootTest
class ArtifactExclusionH2Test {

    @Autowired
    private ArtifactService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM lock_file_exclusion");
        jdbcTemplate.update("DELETE FROM lock_file_edge");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_dependency_exclusion");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }

    private static DependencySpec dep(String name, int min, int max, String... exclusions) {
        return new DependencySpec(name, min, max, List.of(exclusions));
    }

    private RegisterArtifactRequest artifact(String name, int version, DependencySpec... deps) {
        return new RegisterArtifactRequest(name, version, List.of(deps));
    }

    /** 登记合成场景：root -> a（排除 c）、root -> b；a -> c；b -> c；c:1。仓库版本推进到 5。 */
    private void registerSyntheticGraph() {
        service.registerArtifact(requestId(),
                artifact("root", 1, dep("a", 1, 1, "c"), dep("b", 1, 1)));
        service.registerArtifact(requestId(), artifact("a", 1, dep("c", 1, 1)));
        service.registerArtifact(requestId(), artifact("b", 1, dep("c", 1, 1)));
        service.registerArtifact(requestId(), artifact("c", 1));
    }

    @Test
    void registerWithExclusionsPersistsSortedAndReordersReplay() {
        String rid = requestId();
        ArtifactResponse first = service.registerArtifact(rid,
                artifact("app", 1, dep("lib", 1, 2, "zeta", "alpha")));
        assertThat(first.dependencies()).extracting("name", "exclusions")
                .containsExactly(tuple("lib", List.of("alpha", "zeta")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_dependency_exclusion", Integer.class))
                .isEqualTo(2);

        // 排除集合换序为同参：同 requestId 重放首次完整响应，不重复登记。
        ArtifactResponse replay = service.registerArtifact(rid,
                artifact("app", 1, dep("lib", 1, 2, "alpha", "zeta")));
        assertThat(replay.createdAt()).isEqualTo(first.createdAt());
        assertThat(replay.repositoryVersion()).isEqualTo(first.repositoryVersion());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact", Integer.class)).isEqualTo(1);
    }

    @Test
    void duplicateExclusionNamesReturn400() {
        assertThatThrownBy(() -> service.registerArtifact(requestId(),
                artifact("app", 1, dep("lib", 1, 1, "c", "c"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void moreThanThreeExclusionsReturn400() {
        assertThatThrownBy(() -> service.registerArtifact(requestId(),
                artifact("app", 1, dep("lib", 1, 1, "a", "b", "c", "d"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void blankExclusionNameReturns400() {
        assertThatThrownBy(() -> service.registerArtifact(requestId(),
                artifact("app", 1, dep("lib", 1, 1, "  "))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void syntheticRootKeepsCWhenOnlyOnePathExcludes() {
        registerSyntheticGraph();
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("root", 1, 4L));

        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("a", 1), tuple("b", 1), tuple("c", 1), tuple("root", 1));
        // 实际保留边：root->a、root->b、b->c；a->c 被排除不保留。
        assertThat(lock.edges()).containsExactly(
                new LockEdgeResponse("b", 1, "c", 1),
                new LockEdgeResponse("root", 1, "a", 1),
                new LockEdgeResponse("root", 1, "b", 1));
        assertThat(lock.exclusions()).containsExactly(
                new LockExclusionResponse("root", 1, "a", "c", true));

        // 持久化查询与创建响应一致。
        LockFileResponse queried = service.getLock(lock.id());
        assertThat(queried.edges()).isEqualTo(lock.edges());
        assertThat(queried.exclusions()).isEqualTo(lock.exclusions());
    }

    @Test
    void lockFailsAfterWithdrawingOnlyC() {
        registerSyntheticGraph();
        service.withdrawArtifact(requestId(), "c", 1);

        assertThatThrownBy(() -> service.createLock(requestId(), new LockRequest("root", 1, 5L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_edge", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file_exclusion", Integer.class)).isZero();
    }

    @Test
    void unknownExclusionNameRecordedAsMissInLockFile() {
        service.registerArtifact(requestId(),
                artifact("app", 1, dep("lib", 1, 1, "ghost")));
        service.registerArtifact(requestId(), artifact("lib", 1));

        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 2L));
        assertThat(lock.exclusions()).containsExactly(
                new LockExclusionResponse("app", 1, "lib", "ghost", false));
    }

    @Test
    void lockExplanationUnchangedByLaterRegistrations() {
        registerSyntheticGraph();
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("root", 1, 4L));

        // 后续登记新制品与新版本，仓库前进。
        service.registerArtifact(requestId(), artifact("c", 2));
        service.registerArtifact(requestId(), artifact("extra", 1, dep("c", 1, 2)));

        LockFileResponse queried = service.getLock(lock.id());
        assertThat(queried.entries()).isEqualTo(lock.entries());
        assertThat(queried.edges()).isEqualTo(lock.edges());
        assertThat(queried.exclusions()).isEqualTo(lock.exclusions());
        assertThat(queried.repositoryVersion()).isEqualTo(4L);
    }

    @Test
    void sameRequestIdWithDifferentOperatorReturns409() {
        String rid = requestId();
        service.registerArtifact(rid, "alice", artifact("app", 1));
        assertThatThrownBy(() -> service.registerArtifact(rid, "bob", artifact("app", 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        // 同操作者同参重放成功。
        ArtifactResponse replay = service.registerArtifact(rid, "alice", artifact("app", 1));
        assertThat(replay.name()).isEqualTo("app");
    }

    @Test
    void concurrentRegisterWithExclusionsResolvesToSingleResult() throws Exception {
        int threads = 6;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CyclicBarrier barrier =
                new java.util.concurrent.CyclicBarrier(threads);
        String rid = requestId();
        RegisterArtifactRequest request = artifact("conc", 1, dep("lib", 1, 1, "x", "y"));

        java.util.List<java.util.concurrent.Future<ArtifactResponse>> futures =
                new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return service.registerArtifact(rid, request);
            }));
        }
        pool.shutdown();

        ArtifactResponse first = futures.get(0).get(30, java.util.concurrent.TimeUnit.SECONDS);
        for (var future : futures) {
            assertThat(future.get(30, java.util.concurrent.TimeUnit.SECONDS).createdAt())
                    .isEqualTo(first.createdAt());
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact WHERE name = 'conc'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_dependency_exclusion", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request", Integer.class)).isEqualTo(1);
    }
}
