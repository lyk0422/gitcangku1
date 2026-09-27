package com.example.starter.calibration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 不确定度预算 H2 集成测试：登记/整批替换、RSS 固化、名称重复 422、数值非法 422/400、
 * 预算版本过期 410、完整后态校验失败不留部分结果、幂等重放/异参 409/失败不占键。
 */
class UncertaintyBudgetApiTest extends AbstractCalibrationApiTest {

    private void prepare(String key) throws Exception {
        createCertificate("INS-1", "2026-01-01T00:00:00Z", "2028-01-01T00:00:00Z", "1", "0");
        submitMeasurement(key, "INS-1", "2026-06-01T00:00:00Z", "1", "0", "9", "alice");
    }

    @Test
    void registerBudgetCombinesByRssAndSortsByNameStable() throws Exception {
        prepare("B-1");
        // sqrt(0.3^2 + 0.4^2) = 0.5000
        registerBudget("B-1", "alice", budgetJson("u_ref", "0.4", "u_rep", "0.3"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgetVersion").value(1))
                .andExpect(jsonPath("$.measurementVersion").value(1))
                .andExpect(jsonPath("$.components[0].name").value("u_ref"))
                .andExpect(jsonPath("$.components[1].name").value("u_rep"))
                .andExpect(jsonPath("$.combinedUncertainty").value("0.5000"))
                .andExpect(jsonPath("$.calculationMethod").value("RSS_HALF_UP_4"));

        mvc.perform(get("/api/measurements/{key}/budget", "B-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgetVersion").value(1))
                .andExpect(jsonPath("$.combinedUncertainty").value("0.5000"));

        mvc.perform(get("/api/measurements/{key}", "B-1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.budget.budgetVersion").value(1))
                .andExpect(jsonPath("$.budget.combinedUncertainty").value("0.5000"))
                .andExpect(jsonPath("$.budget.components.length()").value(2));
    }

    @Test
    void emptyBudgetBeforeRegistrationReadsAsVersionZero() throws Exception {
        prepare("B-2");
        mvc.perform(get("/api/measurements/{key}/budget", "B-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgetVersion").value(0))
                .andExpect(jsonPath("$.components.length()").value(0))
                .andExpect(jsonPath("$.combinedUncertainty").value("0.0000"));
    }

    @Test
    void duplicateComponentNameReturns422() throws Exception {
        prepare("B-3");
        registerBudget("B-3", "alice", budgetJson("u", "0.1", "u", "0.2"), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DUPLICATE_COMPONENT_NAME"));
        // trim 后同名也视为重复
        registerBudget("B-3", "alice",
                "{\"components\":[{\"name\":\"u\",\"magnitude\":\"0.1\"},{\"name\":\" u \",\"magnitude\":\"0.2\"}]}",
                null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DUPLICATE_COMPONENT_NAME"));
    }

    @Test
    void negativeMagnitudeReturns422AndMalformedReturns400() throws Exception {
        prepare("B-4");
        registerBudget("B-4", "alice", budgetJson("u", "-0.1"), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_NONNEGATIVE_VALUE"));
        registerBudget("B-4", "alice", budgetJson("u", "abc"), null)
                .andExpect(status().isBadRequest());
        registerBudget("B-4", "alice", budgetJson("u", "0.1234567"), null)
                .andExpect(status().isBadRequest());
        registerBudget("B-4", "alice", "{\"components\":[]}", null)
                .andExpect(status().isBadRequest());
    }

    @Test
    void budgetUnknownMeasurementReturns404() throws Exception {
        registerBudget("NO-SUCH", "alice", budgetJson("u", "0.1"), null)
                .andExpect(status().isNotFound());
    }

    @Test
    void failedFullValidationLeavesNoNewVersionAndReleaseStateUntouched() throws Exception {
        prepare("B-5");
        registerBudget("B-5", "alice", budgetJson("u_a", "0.3", "u_b", "0.4"), null)
                .andExpect(status().isOk());
        // 第二项名称与第一项重复：完整后态校验失败
        registerBudget("B-5", "alice", budgetJson("u_a", "0.1", "u_a", "0.2", "u_c", "-1"), null)
                .andExpect(status().isUnprocessableEntity());

        mvc.perform(get("/api/measurements/{key}/budget", "B-5"))
                .andExpect(jsonPath("$.budgetVersion").value(1))
                .andExpect(jsonPath("$.components.length()").value(2));
        Integer v2Rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uncertainty_component uc JOIN measurement m ON m.id = uc.measurement_id "
                        + "WHERE m.measurement_key = 'B-5' AND uc.budget_version = 2", Integer.class);
        assertEquals(0, v2Rows, "失败的整批替换不得写入任何新版本分量");
        mvc.perform(get("/api/measurements/{key}", "B-5"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void staleExpectedBudgetVersionReturns410() throws Exception {
        prepare("B-6");
        registerBudget("B-6", "alice", budgetJson("u", "0.1"), null).andExpect(status().isOk());
        // 当前版本已是 1，再要求基于 0 → 410
        registerBudget("B-6", "alice", budgetJson("u", "0.2"), 0L)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("BUDGET_VERSION_STALE"));
        // 不带 expected 强制最新：成功升到 2
        registerBudget("B-6", "alice", budgetJson("u", "0.2"), null).andExpect(status().isOk());
        // 再要求基于 1 → 410
        registerBudget("B-6", "alice", budgetJson("u", "0.3"), 1L)
                .andExpect(status().isGone());
        mvc.perform(get("/api/measurements/{key}/budget", "B-6"))
                .andExpect(jsonPath("$.budgetVersion").value(2))
                .andExpect(jsonPath("$.components[0].magnitude").value("0.2"));
    }

    @Test
    void idempotentReplayReturnsFirstSnapshotAndDifferentParamsConflictAndFailureDoesNotOccupyKey()
            throws Exception {
        prepare("B-7");
        String body = budgetJson("u_a", "0.3", "u_b", "0.4");
        // 首次成功
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/measurements/{key}/budget", "B-7")
                        .header("X-Actor-Id", "alice").header("X-Request-Id", "REQ-B1")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgetVersion").value(1))
                .andExpect(jsonPath("$.combinedUncertainty").value("0.5000"));
        // 同参重放：返回首次快照，不产生新版本
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/measurements/{key}/budget", "B-7")
                        .header("X-Actor-Id", "alice").header("X-Request-Id", "REQ-B1")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgetVersion").value(1))
                .andExpect(jsonPath("$.combinedUncertainty").value("0.5000"));
        Integer versionRows = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT budget_version) FROM uncertainty_component uc "
                        + "JOIN measurement m ON m.id = uc.measurement_id WHERE m.measurement_key = 'B-7'",
                Integer.class);
        assertEquals(1, versionRows, "重放不得产生新预算版本");

        // 异参 → 409
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/measurements/{key}/budget", "B-7")
                        .header("X-Actor-Id", "alice").header("X-Request-Id", "REQ-B1")
                        .contentType("application/json").content(budgetJson("u_a", "0.9")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PARAM_CONFLICT"));

        // 失败不占键：REQ-B2 先用于非法请求（422），随后同 requestId 的合法请求应成功
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/measurements/{key}/budget", "B-7")
                        .header("X-Actor-Id", "alice").header("X-Request-Id", "REQ-B2")
                        .contentType("application/json").content(budgetJson("u_a", "-1")))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/measurements/{key}/budget", "B-7")
                        .header("X-Actor-Id", "alice").header("X-Request-Id", "REQ-B2")
                        .contentType("application/json").content(budgetJson("u_a", "0.12")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgetVersion").value(2));
    }
}
