package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.ScopedLockFileResponse;
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

/**
 * 私有作用域锁定的真实并发测试：同键并发重放与锁定/登记竞争，均核对最终数据。
 */
@SpringBootTest
class ScopedLockConcurrencyH2Test {

    @Autowired
    private ArtifactService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM scoped_lock_edge");
        jdbcTemplate.update("DELETE FROM scoped_lock_entry");
        jdbcTemplate.update("DELETE FROM scoped_lock_scope");
        jdbcTemplate.update("DELETE FROM scoped_lock");
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

    private void registerSyntheticGraph() {
        service.registerArtifact(requestId(), new RegisterArtifactRequest("app", 1,
                List.of(new DependencySpec("x", 1, 1),
                        new DependencySpec("p", 1, 1, "PRIVATE"))));
        service.registerArtifact(requestId(), new RegisterArtifactRequest("x", 1, List.of()));
        service.registerArtifact(requestId(), new RegisterArtifactRequest("x", 2, List.of()));
        service.registerArtifact(requestId(), new RegisterArtifactRequest("p", 1,
                List.of(new DependencySpec("x", 2, 2))));
    }

    @Test
    void concurrentScopedLockSameRequestIdResolvesToSingleLock() throws Exception {
        registerSyntheticGraph();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        String rid = requestId();

        List<Future<ScopedLockFileResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.createScopedLock(rid, new LockRequest("app", 1, 4L));
            }));
        }
        pool.shutdown();

        ScopedLockFileResponse first = futures.get(0).get(30, TimeUnit.SECONDS);
        for (Future<ScopedLockFileResponse> future : futures) {
            ScopedLockFileResponse response = future.get(30, TimeUnit.SECONDS);
            assertThat(response.id()).isEqualTo(first.id());
            assertThat(response.scopes()).isEqualTo(first.scopes());
            assertThat(response.edges()).isEqualTo(first.edges());
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM scoped_lock", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM scoped_lock_scope", Integer.class))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE operation = 'CREATE_SCOPED_LOCK'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentScopedLockAndRegisterSerializeOnRepositoryVersion() throws Exception {
        registerSyntheticGraph();
        long versionBefore = 4L;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        String lockRequestId = requestId();
        String registerRequestId = requestId();

        Callable<Object> lockTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return service.createScopedLock(lockRequestId, new LockRequest("app", 1, versionBefore));
            } catch (ApiException e) {
                return e;
            }
        };
        Callable<Object> registerTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.registerArtifact(registerRequestId,
                    new RegisterArtifactRequest("late", 1, List.of()));
        };

        List<Future<Object>> futures = pool.invokeAll(List.of(lockTask, registerTask));
        pool.shutdown();
        Object lockOutcome = futures.get(0).get(30, TimeUnit.SECONDS);
        Object registerOutcome = futures.get(1).get(30, TimeUnit.SECONDS);

        // 登记总是成功并把仓库版本推进到 5。
        assertThat(registerOutcome).isInstanceOf(ArtifactResponse.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class))
                .isEqualTo(versionBefore + 1);

        if (lockOutcome instanceof ScopedLockFileResponse lock) {
            // 锁先提交：内容完整可查询，仓库版本为锁定时的 4。
            assertThat(lock.repositoryVersion()).isEqualTo(versionBefore);
            assertThat(lock.scopes()).hasSize(2);
            assertThat(service.getScopedLock(lock.id()).scopes()).hasSize(2);
        } else {
            // 登记先提交：期望版本过期，锁 409 且无半成品。
            assertThat(lockOutcome).isInstanceOf(ApiException.class);
            assertThat(((ApiException) lockOutcome).getStatus()).isEqualTo(409);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM scoped_lock", Integer.class)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM scoped_lock_scope", Integer.class)).isZero();
        }
    }
}
