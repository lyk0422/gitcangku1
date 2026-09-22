package com.example.starter.web;

import com.example.starter.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 依赖锁定 H2 集成测试：主流程、409/422/404 失败分支、撤回后历史可读、幂等边界。
 */
class LockApiIntegrationTest extends AbstractIntegrationTest {

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

    private void register(String requestId, String name, int version,
                          List<Map<String, Object>> deps) throws Exception {
        mockMvc.perform(post("/api/artifacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(requestId, name, version, deps)))
                .andExpect(status().isCreated());
    }

    private String lockJson(String requestId, String root, int version, long expected) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("rootName", root);
        body.put("rootVersion", version);
        body.put("expectedRepositoryVersion", expected);
        return objectMapper.writeValueAsString(body);
    }

    @Test
    void successfulLockPersistsRootItemsAndRepositoryVersionSortedByName() throws Exception {
        // 仓库版本：app(1) -> lib(2) -> lib(1)，最终版本为3
        register("r-app", "app", 1, List.of(dep("lib", 1, 2)));
        register("r-lib2", "lib", 2, List.of());
        register("r-lib1", "lib", 1, List.of());

        mockMvc.perform(post("/api/locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lockJson("l-1", "app", 1, 3)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rootName").value("app"))
                .andExpect(jsonPath("$.rootVersion").value(1))
                .andExpect(jsonPath("$.repositoryVersion").value(3))
                .andExpect(jsonPath("$.items[0].name").value("app"))
                .andExpect(jsonPath("$.items[0].version").value(1))
                .andExpect(jsonPath("$.items[1].name").value("lib"))
                .andExpect(jsonPath("$.items[1].version").value(2));

        Integer itemCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file_item", Integer.class);
        org.assertj.core.api.Assertions.assertThat(itemCount).isEqualTo(2);
    }

    @Test
    void staleExpectedRepositoryVersionReturns409AndSavesNothing() throws Exception {
        register("r-app", "app", 1, List.of());

        mockMvc.perform(post("/api/locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lockJson("l-stale", "app", 1, 99)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPOSITORY_VERSION_MISMATCH"));

        Integer lockCount = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class);
        Integer idemCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotency_record WHERE request_id = 'l-stale'", Integer.class);
        org.assertj.core.api.Assertions.assertThat(lockCount).isZero();
        // 失败不占键：同 requestId 可在版本修正后成功
        org.assertj.core.api.Assertions.assertThat(idemCount).isZero();

        mockMvc.perform(post("/api/locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lockJson("l-stale", "app", 1, 1)))
                .andExpect(status().isCreated());
    }

    @Test
    void missingRootReturns404() throws Exception {
        mockMvc.perform(post("/api/locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lockJson("l-miss", "ghost", 1, 0)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROOT_NOT_FOUND"));
    }

    @Test
    void withdrawnRootReturns409() throws Exception {
        register("r-w", "app", 1, List.of());
        mockMvc.perform(post("/api/artifacts/app/versions/1/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w-1\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lockJson("l-w", "app", 1, 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROOT_WITHDRAWN"));
    }

    @Test
    void noFeasibleCombinationReturns422AndSavesNothing() throws Exception {
        // app1 要求 lib[2,2]，但 lib 仅有1（未撤回），不可行
        register("r-app", "app", 1, List.of(dep("lib", 2, 2)));
        register("r-lib", "lib", 1, List.of());

        mockMvc.perform(post("/api/locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lockJson("l-422", "app", 1, 2)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_FEASIBLE_RESOLUTION"));

        Integer lockCount = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class);
        Integer itemCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file_item", Integer.class);
        org.assertj.core.api.Assertions.assertThat(lockCount).isZero();
        org.assertj.core.api.Assertions.assertThat(itemCount).isZero();
    }

    @Test
    void backtrackingPicksLowerVersionWhenHighestBreaksTransitiveConstraint() throws Exception {
        // app1 -> lib[1,2], lib2v1 -> lib[1,1]；lib2 最高2 与 lib2 冲突，需回退选1
        register("r-app", "app", 1, List.of(dep("lib", 1, 2), dep("lib2", 1, 1)));
        register("r-lib2", "lib", 2, List.of());
        register("r-lib1", "lib", 1, List.of());
        register("r-l2", "lib2", 1, List.of(dep("lib", 1, 1)));

        mockMvc.perform(post("/api/locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lockJson("l-bt", "app", 1, 4)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[?(@.name=='lib')].version").value(1));
    }

    @Test
    void completedLockRemainsQueryableAfterArtifactWithdrawal() throws Exception {
        register("r-app", "app", 1, List.of(dep("lib", 1, 1)));
        register("r-lib", "lib", 1, List.of());

        String response = mockMvc.perform(post("/api/locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lockJson("l-hist", "app", 1, 2)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long lockId = objectMapper.readTree(response).get("id").asLong();

        // 撤回 lib 后仓库版本变为3
        mockMvc.perform(post("/api/artifacts/lib/versions/1/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w-lib\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/locks/" + lockId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoryVersion").value(2))
                .andExpect(jsonPath("$.items[?(@.name=='lib')].version").value(1));
    }

    @Test
    void listLocksCanFilterByRootName() throws Exception {
        register("r-a", "app", 1, List.of());
        register("r-b", "tool", 1, List.of());
        mockMvc.perform(post("/api/locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lockJson("la", "app", 1, 2)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lockJson("lb", "tool", 1, 2)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/locks").param("rootName", "app"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].rootName").value("app"));

        mockMvc.perform(get("/api/locks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void getUnknownLockReturns404() throws Exception {
        mockMvc.perform(get("/api/locks/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOCK_NOT_FOUND"));
    }

    @Test
    void lockSameRequestIdSameParamsReplaysOriginalResult() throws Exception {
        register("r-a", "app", 1, List.of());
        String json = lockJson("l-replay", "app", 1, 1);
        String first = mockMvc.perform(post("/api/locks")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/api/locks")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(
                objectMapper.readTree(second).get("id").asLong())
                .isEqualTo(objectMapper.readTree(first).get("id").asLong());
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class);
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);
    }

    @Test
    void lockSameRequestIdDifferentParamsReturns409() throws Exception {
        register("r-a", "app", 1, List.of());
        register("r-b", "app", 2, List.of());
        mockMvc.perform(post("/api/locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lockJson("l-conflict", "app", 1, 2)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lockJson("l-conflict", "app", 2, 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PARAM_MISMATCH"));
    }
}
