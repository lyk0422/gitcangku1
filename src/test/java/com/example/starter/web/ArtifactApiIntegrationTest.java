package com.example.starter.web;

import com.example.starter.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 制品登记/撤回 H2 集成测试：主流程、参数失败、限额与写幂等边界。
 */
class ArtifactApiIntegrationTest extends AbstractIntegrationTest {

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

    @Test
    void registerPersistsArtifactDependenciesAndIncrementsRepositoryVersion() throws Exception {
        mockMvc.perform(post("/api/artifacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("req-1", "app", 1,
                                List.of(dep("zlib", 1, 3), dep("alib", 2, 2)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("app"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.dependencies[0].name").value("alib"))
                .andExpect(jsonPath("$.dependencies[1].name").value("zlib"));

        Integer artifactCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact", Integer.class);
        Integer depCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_dependency", Integer.class);
        Long repoVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM repository_version WHERE id = 1", Long.class);
        org.assertj.core.api.Assertions.assertThat(artifactCount).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(depCount).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(repoVersion).isEqualTo(1L);
    }

    @Test
    void duplicateNameVersionReturns409() throws Exception {
        mockMvc.perform(post("/api/artifacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("req-a", "app", 1, List.of())))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/artifacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("req-b", "app", 1, List.of())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ARTIFACT_ALREADY_EXISTS"));
    }

    @Test
    void invalidDependencyRangeReturns400() throws Exception {
        mockMvc.perform(post("/api/artifacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("req-invalid-range", "app", 1,
                                List.of(dep("lib", 5, 2)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DEPENDENCY_RANGE"));
    }

    @Test
    void duplicateDependencyNamesReturns400() throws Exception {
        mockMvc.perform(post("/api/artifacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("req-dup-dep", "app", 1,
                                List.of(dep("lib", 1, 2), dep("lib", 3, 4)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_DEPENDENCY_NAME"));
    }

    @Test
    void moreThanTenDependenciesReturns400() throws Exception {
        List<Map<String, Object>> deps = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            deps.add(dep("lib" + i, 1, 1));
        }
        mockMvc.perform(post("/api/artifacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("req-11-deps", "app", 1, deps)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sixthVersionOfSameNameReturns422() throws Exception {
        for (int v = 1; v <= 5; v++) {
            mockMvc.perform(post("/api/artifacts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(registerJson("req-v" + v, "limited", v, List.of())))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(post("/api/artifacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("req-v6", "limited", 6, List.of())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VERSION_LIMIT_EXCEEDED"));
    }

    @Test
    void twentyFirstNameReturns422() throws Exception {
        for (int i = 1; i <= 20; i++) {
            mockMvc.perform(post("/api/artifacts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(registerJson("req-name-" + i, "name" + i, 1, List.of())))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(post("/api/artifacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("req-name-21", "name21", 1, List.of())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NAME_LIMIT_EXCEEDED"));
    }

    @Test
    void withdrawMarksVersionWithoutDeletingAndIncrementsVersion() throws Exception {
        mockMvc.perform(post("/api/artifacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("req-reg", "app", 1,
                                List.of(dep("lib", 1, 1)))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/artifacts/app/versions/1/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-withdraw\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dependencies[0].name").value("lib"));

        Boolean withdrawn = jdbcTemplate.queryForObject(
                "SELECT withdrawn FROM artifact WHERE name = 'app' AND version = 1",
                Boolean.class);
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact", Integer.class);
        Long repoVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM repository_version WHERE id = 1", Long.class);
        org.assertj.core.api.Assertions.assertThat(withdrawn).isTrue();
        org.assertj.core.api.Assertions.assertThat(rowCount).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(repoVersion).isEqualTo(2L);

        // 重复撤回 409
        mockMvc.perform(post("/api/artifacts/app/versions/1/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-withdraw-2\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ARTIFACT_ALREADY_WITHDRAWN"));
    }

    @Test
    void withdrawUnknownArtifactReturns404() throws Exception {
        mockMvc.perform(post("/api/artifacts/ghost/versions/9/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-ghost\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ARTIFACT_NOT_FOUND"));
    }

    @Test
    void sameRequestIdSameParamsReplaysSuccessWithoutDuplicatingData() throws Exception {
        String json = registerJson("idem-1", "app", 1, List.of(dep("lib", 1, 2)));
        mockMvc.perform(post("/api/artifacts").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/artifacts").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("app"));

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM artifact", Integer.class);
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);
    }

    @Test
    void sameRequestIdDifferentParamsReturns409() throws Exception {
        mockMvc.perform(post("/api/artifacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("idem-2", "app", 1, List.of())))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/artifacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("idem-2", "app", 2, List.of())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PARAM_MISMATCH"));
    }

    @Test
    void failedRequestDoesNotOccupyIdempotencyKey() throws Exception {
        // 首次参数非法失败
        mockMvc.perform(post("/api/artifacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("idem-3", "app", 1,
                                List.of(dep("lib", 9, 1)))))
                .andExpect(status().isBadRequest());
        // 同键换为合法参数应当成功
        mockMvc.perform(post("/api/artifacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("idem-3", "app", 1, List.of())))
                .andExpect(status().isCreated());
    }
}
