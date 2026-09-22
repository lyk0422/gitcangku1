package com.example.starter.web;

import com.example.starter.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 写操作并发一致性 H2 测试：真实多线程协调并发，带超时；
 * 断言行锁串行下的最终数据，而非依赖打印或休眠。
 */
class ConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private ExecutorService pool;

    private String registerJson(String requestId, String name, int version,
                                List<Map<String, Object>> deps) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("name", name);
        body.put("version", version);
        body.put("dependencies", deps);
        return objectMapper.writeValueAsString(body);
    }

    private Map<String, Object> dep(String name, int min, int max) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", name);
        d.put("minVersion", min);
        d.put("maxVersion", max);
        return d;
    }

    private String lockJson(String requestId, String root, int version, long expected) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("rootName", root);
        body.put("rootVersion", version);
        body.put("expectedRepositoryVersion", expected);
        return objectMapper.writeValueAsString(body);
    }

    private void register(String requestId, String name, int version,
                          List<Map<String, Object>> deps) throws Exception {
        mockMvc.perform(post("/api/artifacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(requestId, name, version, deps)))
                .andExpect(status().isCreated());
    }

    @Test
    void concurrentLocksAgainstStableRepositoryAllSucceedAndAreConsistent() throws Exception {
        // app1 -> lib[1,1], lib1：两次登记后仓库版本为2
        register("r-app", "app", 1, List.of(dep("lib", 1, 1)));
        register("r-lib", "lib", 1, List.of());

        int threads = 8;
        pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final String requestId = "lock-" + i;
            futures.add(pool.submit(() -> {
                try {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    MvcResult result = mockMvc.perform(post("/api/locks")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(lockJson(requestId, "app", 1, 2)))
                            .andReturn();
                    assertThat(result.getResponse().getStatus()).isEqualTo(201);
                    success.incrementAndGet();
                } catch (Exception e) {
                    throw new AssertionError("并发锁定出现异常", e);
                }
            }));
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(success).hasValue(threads);
        Integer lockCount = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class);
        Integer itemCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file_item", Integer.class);
        // 每个锁文件恰好两个名称各一条明细，无半成品
        assertThat(lockCount).isEqualTo(threads);
        assertThat(itemCount).isEqualTo(threads * 2);
        List<Map<String, Object>> badItems = jdbcTemplate.queryForList(
                "SELECT lock_file_id, COUNT(DISTINCT artifact_name) AS names, COUNT(1) AS rows "
                        + "FROM lock_file_item GROUP BY lock_file_id HAVING names <> rows OR rows <> 2");
        assertThat(badItems).isEmpty();
    }

    @Test
    void concurrentSameRequestIdRegisterProducesExactlyOneArtifact() throws Exception {
        int threads = 6;
        pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    int status = mockMvc.perform(post("/api/artifacts")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(registerJson("same-key", "app", 1, List.of())))
                            .andReturn().getResponse().getStatus();
                    // 行锁串行：首个执行，其余同参重放，全部201
                    assertThat(status).isEqualTo(201);
                    created.incrementAndGet();
                } catch (Exception e) {
                    throw new AssertionError("并发幂等登记出现异常", e);
                }
            }));
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // 行锁串行化下同键同参并发均应重放成功；至少严格只有一条制品数据
        Integer artifactCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact WHERE name = 'app' AND version = 1", Integer.class);
        Integer idemCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotency_record WHERE request_id = 'same-key'", Integer.class);
        assertThat(artifactCount).isEqualTo(1);
        assertThat(idemCount).isEqualTo(1);
        assertThat(created).hasValue(threads);
    }

    @Test
    void lockAndWithdrawRaceSeesConsistentRepositoryState() throws Exception {
        register("r-app", "app", 1, List.of(dep("lib", 1, 1)));
        register("r-lib", "lib", 1, List.of());
        // 当前仓库版本为2；锁定以2为期望值，撤回使其变为3

        pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<Integer> lockFuture = pool.submit(() -> {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            return mockMvc.perform(post("/api/locks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(lockJson("race-lock", "app", 1, 2)))
                    .andReturn().getResponse().getStatus();
        });
        Future<Integer> withdrawFuture = pool.submit(() -> {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            return mockMvc.perform(post("/api/artifacts/lib/versions/1/withdraw")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"race-withdraw\"}"))
                    .andReturn().getResponse().getStatus();
        });

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        int lockStatus = lockFuture.get(30, TimeUnit.SECONDS);
        int withdrawStatus = withdrawFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(withdrawStatus).isEqualTo(200);
        // 行锁串行：锁定要么先于撤回成功（201），要么看到版本3而409，绝不允许500或半成品
        assertThat(lockStatus).isIn(201, 409);
        if (lockStatus == 409) {
            Integer lockCount = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class);
            assertThat(lockCount).isZero();
        } else {
            Long repoVersionOnLock = jdbcTemplate.queryForObject(
                    "SELECT repository_version FROM lock_file WHERE request_id = 'race-lock'",
                    Long.class);
            Integer itemCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM lock_file_item WHERE lock_file_id = "
                            + "(SELECT id FROM lock_file WHERE request_id = 'race-lock')",
                    Integer.class);
            assertThat(repoVersionOnLock).isEqualTo(2L);
            assertThat(itemCount).isEqualTo(2);
        }
        Long finalRepoVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM repository_version WHERE id = 1", Long.class);
        assertThat(finalRepoVersion).isEqualTo(3L);
        Boolean withdrawn = jdbcTemplate.queryForObject(
                "SELECT withdrawn FROM artifact WHERE name = 'lib' AND version = 1", Boolean.class);
        assertThat(withdrawn).isTrue();
    }
}
