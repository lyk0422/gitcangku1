package com.example.starter.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 并发与幂等边界测试：真实并发请求打到 H2，验证行锁序列化、
 * 唯一约束、幂等重放与锁文件一致性。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Timeout(120)
class ArtifactConcurrencyTests {

    private static final int THREADS = 4;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM lock_entry");
        jdbc.update("DELETE FROM lock_file");
        jdbc.update("DELETE FROM artifact_dependency");
        jdbc.update("DELETE FROM artifact");
        jdbc.update("DELETE FROM request_log");
        jdbc.update("UPDATE repository_meta SET repo_version = 0 WHERE id = 1");
    }

    @Test
    void concurrentSameLockRequestProducesSingleLockFile() throws Exception {
        register("req-lib-1", "lib", 1, List.of());
        register("req-app-1", "app", 1, List.of(Map.of(
                "name", "lib", "minVersion", 1, "maxVersion", 1)));

        List<MvcResult> results = runConcurrently(i ->
                lock("req-lock-same", "app", 1, 2));
        long firstLockFileId = -1;
        for (MvcResult result : results) {
            assertEquals(201, result.getResponse().getStatus());
            long lockFileId = objectMapper
                    .readTree(result.getResponse().getContentAsString())
                    .get("lockFileId").longValue();
            if (firstLockFileId < 0) {
                firstLockFileId = lockFileId;
            } else {
                assertEquals(firstLockFileId, lockFileId);
            }
        }
        Integer lockCount = jdbc.queryForObject("SELECT COUNT(*) FROM lock_file", Integer.class);
        Integer logCount = jdbc.queryForObject("SELECT COUNT(*) FROM request_log", Integer.class);
        assertEquals(1, lockCount);
        assertEquals(3, logCount);
    }

    @Test
    void concurrentRegisterSameArtifactYieldsSingleSuccess() throws Exception {
        List<MvcResult> results = runConcurrently(i ->
                register("req-reg-" + i, "a", 1, List.of()));
        long created = results.stream()
                .filter(r -> r.getResponse().getStatus() == 201).count();
        long conflict = results.stream()
                .filter(r -> r.getResponse().getStatus() == 409).count();
        assertEquals(1, created);
        assertEquals(THREADS - 1, conflict);
        Integer artifactCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM artifact WHERE name = 'a' AND version = 1", Integer.class);
        assertEquals(1, artifactCount);
        mvc.perform(get("/api/repository"))
                .andExpect(jsonPath("$.repositoryVersion").value(1));
    }

    @Test
    void concurrentLockAndRetractSeeConsistentRepository() throws Exception {
        register("req-lib-1", "lib", 1, List.of());
        register("req-app-1", "app", 1, List.of(Map.of(
                "name", "lib", "minVersion", 1, "maxVersion", 1)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<MvcResult> retractFuture = pool.submit(() -> {
            ready.countDown();
            start.await();
            return retract("req-retract-1", "lib", 1);
        });
        Future<MvcResult> lockFuture = pool.submit(() -> {
            ready.countDown();
            start.await();
            return lock("req-lock-1", "app", 1, 2);
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        MvcResult retractResult = retractFuture.get(30, TimeUnit.SECONDS);
        MvcResult lockResult = lockFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        // 撤回一定成功；锁定要么先于撤回完成（201），要么看到撤回后的仓库版本（409）。
        assertEquals(200, retractResult.getResponse().getStatus());
        int lockStatus = lockResult.getResponse().getStatus();
        assertTrue(lockStatus == 201 || lockStatus == 409,
                "锁定状态应为 201 或 409，实际: " + lockStatus);
        mvc.perform(get("/api/repository"))
                .andExpect(jsonPath("$.repositoryVersion").value(3));
        if (lockStatus == 201) {
            JsonNode body = objectMapper.readTree(lockResult.getResponse().getContentAsString());
            assertEquals(2, body.get("repositoryVersion").longValue());
            long lockFileId = body.get("lockFileId").longValue();
            // 先完成的锁文件在制品撤回后仍可查询且不被改写。
            mvc.perform(get("/api/locks/{id}", lockFileId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.entries[1].name").value("lib"))
                    .andExpect(jsonPath("$.entries[1].version").value(1));
        } else {
            mvc.perform(get("/api/locks")).andExpect(jsonPath("$.length()").value(0));
        }
    }

    @Test
    void concurrentDistinctRegistrationsAllPersist() throws Exception {
        List<MvcResult> results = runConcurrently(i ->
                register("req-reg-" + i, "name" + i, 1, List.of()));
        for (MvcResult result : results) {
            assertEquals(201, result.getResponse().getStatus());
        }
        mvc.perform(get("/api/repository"))
                .andExpect(jsonPath("$.repositoryVersion").value(THREADS))
                .andExpect(jsonPath("$.artifacts.length()").value(THREADS));
    }

    private List<MvcResult> runConcurrently(ThrowingTask task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return task.run(index);
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        List<MvcResult> results = new ArrayList<>();
        for (Future<MvcResult> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        return results;
    }

    private MvcResult register(String requestId, String name, int version,
                               List<Map<String, Object>> deps) throws Exception {
        return mvc.perform(post("/api/artifacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", requestId,
                                "name", name,
                                "version", version,
                                "dependencies", deps))))
                .andReturn();
    }

    private MvcResult retract(String requestId, String name, int version) throws Exception {
        return mvc.perform(post("/api/artifacts/{name}/versions/{version}/retract", name, version)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("requestId", requestId))))
                .andReturn();
    }

    private MvcResult lock(String requestId, String rootName, int rootVersion,
                           long expectedRepositoryVersion) throws Exception {
        return mvc.perform(post("/api/locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", requestId,
                                "rootName", rootName,
                                "rootVersion", rootVersion,
                                "expectedRepositoryVersion", expectedRepositoryVersion))))
                .andReturn();
    }

    @FunctionalInterface
    private interface ThrowingTask {

        MvcResult run(int index) throws Exception;
    }
}
