package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
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
import static org.assertj.core.api.Assertions.tuple;

/**
 * 真实 H2 事务并发测试：锁定与撤回竞争、同键同参收敛、同键异参仅一胜，
 * 均核对最终业务数据，竞争不报 500。
 */
@SpringBootTest
class ArtifactConcurrencyH2Test {

    private static final String OP = "packer-li";
    private static final long BIG_BUDGET = 1_000_000L;

    @Autowired
    private ArtifactService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
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

    private static DependencySpec dep(String name, int min, int max) {
        return new DependencySpec(name, min, max);
    }

    @Test
    void lockWithdrawConflictConcurrencyLeavesQueryableLockOrClean409() throws Exception {
        // app:1 -> lib[1,1]，lib:1；当前仓库版本为 2。
        service.registerArtifact(rid(), OP,
                new RegisterArtifactRequest("app", 1, 0L, List.of(dep("lib", 1, 1))));
        service.registerArtifact(rid(), OP, new RegisterArtifactRequest("lib", 1, 0L, List.of()));
        long versionBefore = 2L;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        String lockRequestId = rid();
        String withdrawRequestId = rid();

        Callable<Object> lockTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return service.createLock(lockRequestId, OP,
                        new LockRequest("app", 1, versionBefore, BIG_BUDGET));
            } catch (ApiException e) {
                return e;
            }
        };
        Callable<Object> withdrawTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.withdrawArtifact(withdrawRequestId, OP, "lib", 1);
        };

        List<Future<Object>> futures = pool.invokeAll(List.of(lockTask, withdrawTask));
        pool.shutdown();
        Object lockOutcome = futures.get(0).get(30, TimeUnit.SECONDS);
        Object withdrawOutcome = futures.get(1).get(30, TimeUnit.SECONDS);

        assertThat(withdrawOutcome).isInstanceOf(ArtifactResponse.class);
        long finalVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
        assertThat(finalVersion).isEqualTo(versionBefore + 1);

        if (lockOutcome instanceof LockFileResponse lock) {
            // 锁先提交：即使 lib 后被撤回，锁文件仍可查询、冻结内容不改写。
            assertThat(lock.entries()).extracting("name", "version")
                    .containsExactly(tuple("app", 1), tuple("lib", 1));
            LockFileResponse queried = service.getLock(lock.id());
            assertThat(queried.repositoryVersion()).isEqualTo(versionBefore);
            assertThat(queried.entries()).isEqualTo(lock.entries());
        } else {
            // 撤回先提交：期望版本过期，锁必须 409，且无半成品。
            assertThat(lockOutcome).isInstanceOf(ApiException.class);
            assertThat(((ApiException) lockOutcome).getStatus()).isEqualTo(409);
            assertThat(service.listLocks()).isEmpty();
        }
    }

    @Test
    void concurrentSameRequestIdSamePayloadResolvesToSingleResult() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        String id = rid();
        RegisterArtifactRequest request = new RegisterArtifactRequest("concurrent", 1, 3L, List.of());

        List<Future<ArtifactResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.registerArtifact(id, OP, request);
            }));
        }
        pool.shutdown();

        ArtifactResponse first = futures.get(0).get(30, TimeUnit.SECONDS);
        for (Future<ArtifactResponse> future : futures) {
            ArtifactResponse response = future.get(30, TimeUnit.SECONDS);
            assertThat(response.repositoryVersion()).isEqualTo(first.repositoryVersion());
            assertThat(response.createdAt()).isEqualTo(first.createdAt());
            assertThat(response.sizeBytes()).isEqualTo(3L);
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
        String id = rid();

        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    service.registerArtifact(id, OP,
                            new RegisterArtifactRequest("v" + idx, 1, null, List.of()));
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
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact", Integer.class)).isEqualTo(1);
    }

    @Test
    void uniqueConstraintOnNameAndVersionIsEnforcedByDatabase() {
        jdbcTemplate.update("INSERT INTO artifact (name, version, withdrawn, size_bytes, created_at) "
                + "VALUES ('x', 1, 0, 0, CURRENT_TIMESTAMP(6))");
        try {
            jdbcTemplate.update("INSERT INTO artifact (name, version, withdrawn, size_bytes, created_at) "
                    + "VALUES ('x', 1, 0, 0, CURRENT_TIMESTAMP(6))");
            throw new AssertionError("期望唯一约束冲突");
        } catch (org.springframework.dao.DuplicateKeyException expected) {
            // H2 实际强制 name+version 唯一。
        }
    }
}
