package com.example.starter.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import org.springframework.test.web.servlet.ResultActions;

/**
 * 制品登记/撤回/锁定的主流程与失败分支测试，使用 H2 内存库（MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Timeout(60)
class ArtifactApiTests {

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
    void registerAndLockMainFlow() throws Exception {
        register("req-lib-1", "lib", 1, List.of()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.repositoryVersion").value(1));
        register("req-lib-2", "lib", 2, List.of()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.repositoryVersion").value(2));
        register("req-app-1", "app", 1, List.of(dep("lib", 1, 2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.repositoryVersion").value(3))
                .andExpect(jsonPath("$.retracted").value(false));

        MvcResult locked = lock("req-lock-1", "app", 1, 3)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rootName").value("app"))
                .andExpect(jsonPath("$.rootVersion").value(1))
                .andExpect(jsonPath("$.repositoryVersion").value(3))
                .andExpect(jsonPath("$.entries[0].name").value("app"))
                .andExpect(jsonPath("$.entries[0].version").value(1))
                .andExpect(jsonPath("$.entries[1].name").value("lib"))
                .andExpect(jsonPath("$.entries[1].version").value(2))
                .andReturn();
        long lockFileId = objectMapper.readTree(locked.getResponse().getContentAsString())
                .get("lockFileId").longValue();

        mvc.perform(get("/api/locks/{id}", lockFileId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].name").value("app"))
                .andExpect(jsonPath("$.entries[1].name").value("lib"))
                .andExpect(jsonPath("$.entries[1].version").value(2));
        mvc.perform(get("/api/locks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].lockFileId").value(lockFileId))
                .andExpect(jsonPath("$[0].repositoryVersion").value(3));
        mvc.perform(get("/api/repository"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoryVersion").value(3))
                .andExpect(jsonPath("$.artifacts.length()").value(3));
    }

    @Test
    void registerDuplicateNameVersionConflict() throws Exception {
        register("req-a-1", "a", 1, List.of()).andExpect(status().isCreated());
        register("req-a-1-again", "a", 1, List.of()).andExpect(status().isConflict());
    }

    @Test
    void repositoryLimitsEnforced() throws Exception {
        for (int v = 1; v <= 5; v++) {
            register("req-a-" + v, "a", v, List.of()).andExpect(status().isCreated());
        }
        register("req-a-6", "a", 6, List.of()).andExpect(status().isUnprocessableEntity());

        for (int i = 1; i <= 19; i++) {
            register("req-n-" + i, "name" + i, 1, List.of()).andExpect(status().isCreated());
        }
        register("req-n-20", "name20", 1, List.of()).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void invalidDependencyDeclarationsRejected() throws Exception {
        List<Map<String, Object>> elevenDeps = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            elevenDeps.add(dep("d" + i, 1, 1));
        }
        register("req-many-deps", "a", 1, elevenDeps).andExpect(status().isBadRequest());

        register("req-dup-deps", "a", 1, List.of(dep("x", 1, 1), dep("x", 1, 1)))
                .andExpect(status().isUnprocessableEntity());

        register("req-bad-range", "a", 1, List.of(dep("x", 3, 1)))
                .andExpect(status().isUnprocessableEntity());

        register("req-zero-version", "a", 0, List.of()).andExpect(status().isBadRequest());
    }

    @Test
    void retractFlowAndErrors() throws Exception {
        register("req-lib-1", "lib", 1, List.of()).andExpect(status().isCreated());
        retract("req-retract-1", "lib", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retracted").value(true))
                .andExpect(jsonPath("$.repositoryVersion").value(2));
        retract("req-retract-2", "lib", 1).andExpect(status().isUnprocessableEntity());
        retract("req-retract-3", "lib", 9).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/repository"))
                .andExpect(jsonPath("$.repositoryVersion").value(2))
                .andExpect(jsonPath("$.artifacts[0].retracted").value(true));
    }

    @Test
    void lockRepositoryVersionMismatchConflict() throws Exception {
        register("req-app-1", "app", 1, List.of()).andExpect(status().isCreated());
        lock("req-lock-bad", "app", 1, 0).andExpect(status().isConflict());
        lock("req-lock-bad-2", "app", 1, 99).andExpect(status().isConflict());
    }

    @Test
    void lockRootMissingOrRetracted() throws Exception {
        lock("req-lock-missing", "ghost", 1, 0).andExpect(status().isUnprocessableEntity());

        register("req-app-1", "app", 1, List.of()).andExpect(status().isCreated());
        retract("req-retract-1", "app", 1).andExpect(status().isOk());
        lock("req-lock-retracted", "app", 1, 2).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void infeasibleLockSavesNothing() throws Exception {
        register("req-lib-1", "lib", 1, List.of()).andExpect(status().isCreated());
        register("req-app-1", "app", 1, List.of(dep("lib", 5, 6)))
                .andExpect(status().isCreated());
        lock("req-lock-inf", "app", 1, 2).andExpect(status().isUnprocessableEntity());

        mvc.perform(get("/api/locks")).andExpect(jsonPath("$.length()").value(0));
        Integer lockCount = jdbc.queryForObject("SELECT COUNT(*) FROM lock_file", Integer.class);
        Integer entryCount = jdbc.queryForObject("SELECT COUNT(*) FROM lock_entry", Integer.class);
        Integer logCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-lock-inf'", Integer.class);
        assertEquals(0, lockCount);
        assertEquals(0, entryCount);
        assertEquals(0, logCount);
    }

    @Test
    void backtrackingSelectsLowerVersion() throws Exception {
        register("req-c-1", "c", 1, List.of()).andExpect(status().isCreated());
        register("req-b-1", "b", 1, List.of()).andExpect(status().isCreated());
        register("req-b-2", "b", 2, List.of(dep("c", 2, 2))).andExpect(status().isCreated());
        register("req-app-1", "app", 1, List.of(dep("b", 1, 2), dep("c", 1, 1)))
                .andExpect(status().isCreated());

        // 贪心取 b=2 会导致 c 的区间 [2,2] 与根约束 [1,1] 冲突，必须回退到 b=1。
        lock("req-lock-1", "app", 1, 4)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entries[0].name").value("app"))
                .andExpect(jsonPath("$.entries[0].version").value(1))
                .andExpect(jsonPath("$.entries[1].name").value("b"))
                .andExpect(jsonPath("$.entries[1].version").value(1))
                .andExpect(jsonPath("$.entries[2].name").value("c"))
                .andExpect(jsonPath("$.entries[2].version").value(1));
    }

    @Test
    void cyclicDependenciesResolveOrFailCleanly() throws Exception {
        register("req-a-1", "a", 1, List.of(dep("b", 1, 1))).andExpect(status().isCreated());
        register("req-b-1", "b", 1, List.of(dep("a", 1, 1))).andExpect(status().isCreated());
        lock("req-lock-cycle", "a", 1, 2)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entries[0].name").value("a"))
                .andExpect(jsonPath("$.entries[0].version").value(1))
                .andExpect(jsonPath("$.entries[1].name").value("b"))
                .andExpect(jsonPath("$.entries[1].version").value(1));
    }

    @Test
    void infeasibleCycleFails() throws Exception {
        register("req-a-1", "a", 1, List.of(dep("b", 1, 1))).andExpect(status().isCreated());
        register("req-b-1", "b", 1, List.of(dep("a", 2, 2))).andExpect(status().isCreated());
        lock("req-lock-cycle-inf", "a", 1, 2).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/locks")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void retractedVersionNotSelected() throws Exception {
        register("req-lib-1", "lib", 1, List.of()).andExpect(status().isCreated());
        register("req-lib-2", "lib", 2, List.of()).andExpect(status().isCreated());
        register("req-app-1", "app", 1, List.of(dep("lib", 1, 2)))
                .andExpect(status().isCreated());
        retract("req-retract-lib-2", "lib", 2).andExpect(status().isOk());

        lock("req-lock-1", "app", 1, 4)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entries[1].name").value("lib"))
                .andExpect(jsonPath("$.entries[1].version").value(1));
    }

    @Test
    void lockFileSurvivesLaterRetract() throws Exception {
        register("req-lib-1", "lib", 1, List.of()).andExpect(status().isCreated());
        register("req-lib-2", "lib", 2, List.of()).andExpect(status().isCreated());
        register("req-app-1", "app", 1, List.of(dep("lib", 1, 2)))
                .andExpect(status().isCreated());
        MvcResult locked = lock("req-lock-1", "app", 1, 3)
                .andExpect(status().isCreated())
                .andReturn();
        long lockFileId = objectMapper.readTree(locked.getResponse().getContentAsString())
                .get("lockFileId").longValue();

        retract("req-retract-lib-2", "lib", 2).andExpect(status().isOk());

        // 先完成的锁文件仍可查询且不被改写。
        mvc.perform(get("/api/locks/{id}", lockFileId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoryVersion").value(3))
                .andExpect(jsonPath("$.entries[1].name").value("lib"))
                .andExpect(jsonPath("$.entries[1].version").value(2));

        // 撤回后仓库版本已变，旧期望版本 409，新版本可再次锁定并避开已撤回版本。
        lock("req-lock-2", "app", 1, 3).andExpect(status().isConflict());
        lock("req-lock-3", "app", 1, 4)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.repositoryVersion").value(4))
                .andExpect(jsonPath("$.entries[1].version").value(1));
    }

    @Test
    void idempotentReplayReturnsOriginalResult() throws Exception {
        MvcResult first = register("req-idem", "a", 1, List.of(dep("x", 1, 2)))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult replay = register("req-idem", "a", 1, List.of(dep("x", 1, 2)))
                .andExpect(status().isCreated())
                .andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        mvc.perform(get("/api/repository"))
                .andExpect(jsonPath("$.repositoryVersion").value(1))
                .andExpect(jsonPath("$.artifacts.length()").value(1));

        register("req-lib-1", "x", 1, List.of()).andExpect(status().isCreated());
        MvcResult lockFirst = lock("req-idem-lock", "a", 1, 2)
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult lockReplay = lock("req-idem-lock", "a", 1, 2)
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode firstNode = objectMapper.readTree(lockFirst.getResponse().getContentAsString());
        JsonNode replayNode = objectMapper.readTree(lockReplay.getResponse().getContentAsString());
        assertEquals(firstNode.get("lockFileId").longValue(),
                replayNode.get("lockFileId").longValue());
        mvc.perform(get("/api/locks")).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void sameRequestIdDifferentParamsConflict() throws Exception {
        register("req-dup", "a", 1, List.of()).andExpect(status().isCreated());
        register("req-dup", "a", 2, List.of()).andExpect(status().isConflict());
        retract("req-dup", "a", 1).andExpect(status().isConflict());
    }

    @Test
    void failedRequestDoesNotConsumeRequestId() throws Exception {
        register("req-fail", "a", 1, List.of(dep("x", 1, 1), dep("x", 1, 1)))
                .andExpect(status().isUnprocessableEntity());
        register("req-fail", "a", 1, List.of()).andExpect(status().isCreated());

        lock("req-fail-lock", "a", 1, 5).andExpect(status().isConflict());
        lock("req-fail-lock", "a", 1, 1).andExpect(status().isCreated());
    }

    private Map<String, Object> dep(String name, int min, int max) {
        return Map.of("name", name, "minVersion", min, "maxVersion", max);
    }

    private ResultActions register(String requestId, String name, int version,
                                   List<Map<String, Object>> deps) throws Exception {
        return mvc.perform(post("/api/artifacts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "requestId", requestId,
                        "name", name,
                        "version", version,
                        "dependencies", deps))));
    }

    private ResultActions retract(String requestId, String name, int version) throws Exception {
        return mvc.perform(post("/api/artifacts/{name}/versions/{version}/retract", name, version)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("requestId", requestId))));
    }

    private ResultActions lock(String requestId, String rootName, int rootVersion,
                               long expectedRepositoryVersion) throws Exception {
        return mvc.perform(post("/api/locks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "requestId", requestId,
                        "rootName", rootName,
                        "rootVersion", rootVersion,
                        "expectedRepositoryVersion", expectedRepositoryVersion))));
    }
}
