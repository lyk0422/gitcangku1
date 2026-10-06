package com.example.starter.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 观测点（新类型观测）API 的主流程、失败分支与幂等边界测试（真实 H2 内存库，MySQL 兼容模式）。
 * 覆盖：坐标与协方差矩阵字段组的创建/三方合并/删除、矩阵合法性 400、
 * 一致性确认的超限 422 / 版本旧 409 / 墓碑 422、历史冻结与 requestId 幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PositionedObservationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM observation_confirmation");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM positioned_observation_version");
        jdbcTemplate.update("DELETE FROM positioned_observation_current");
    }

    private ResultActions create(String requestId, String observationId,
                                 String x, String y, String a, String b, String c,
                                 String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("x", x);
        body.put("y", y);
        body.put("a", a);
        body.put("b", b);
        body.put("c", c);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/positioned-observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions merge(String requestId, String observationId, int baseVersion,
                                String x, String y, String a, String b, String c,
                                String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("x", x);
        body.put("y", y);
        body.put("a", a);
        body.put("b", b);
        body.put("c", c);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/positioned-observations/{id}/merge", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions deleteObservation(String requestId, String observationId,
                                            int expectedVersion, String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedVersion", expectedVersion);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/positioned-observations/{id}/delete", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions confirm(String requestId, String firstId, int firstVersion,
                                  String secondId, int secondVersion, String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("firstObservationId", firstId);
        body.put("firstExpectedVersion", firstVersion);
        body.put("secondObservationId", secondId);
        body.put("secondExpectedVersion", secondVersion);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/positioned-observations/confirmations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getCurrent(String observationId) throws Exception {
        return mockMvc.perform(get("/api/positioned-observations/{id}", observationId));
    }

    private ResultActions getVersion(String observationId, int version) throws Exception {
        return mockMvc.perform(get("/api/positioned-observations/{id}/versions/{version}",
                observationId, version));
    }

    private int versionCount(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM positioned_observation_version WHERE observation_id = ?",
                Integer.class, observationId);
        return count == null ? 0 : count;
    }

    private int confirmationCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_confirmation", Integer.class);
        return count == null ? 0 : count;
    }

    // ---------- 创建 ----------

    @Test
    void createReturnsVersionOne() throws Exception {
        create("req-p1", "pt-1", "1.5", "-2.25", "1.0", "0.1", "2.0", "op-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.observationId").value("pt-1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.x").value("1.500"))
                .andExpect(jsonPath("$.y").value("-2.250"))
                .andExpect(jsonPath("$.a").value("1.000"))
                .andExpect(jsonPath("$.b").value("0.100"))
                .andExpect(jsonPath("$.c").value("2.000"));

        getCurrent("pt-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.x").value("1.500"));
        getVersion("pt-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.c").value("2.000"));
        assertThat(versionCount("pt-1")).isEqualTo(1);
    }

    @Test
    void createInvalidMatrixReturns400() throws Exception {
        // a 非正
        create("req-m1", "pt-m1", "0", "0", "0", "0", "1.0", "op-1").andExpect(status().isBadRequest());
        // c 非正
        create("req-m2", "pt-m2", "0", "0", "1.0", "0", "-0.5", "op-1").andExpect(status().isBadRequest());
        // a*c 等于 b^2（要求严格大于）
        create("req-m3", "pt-m3", "0", "0", "1.0", "1.0", "1.0", "op-1").andExpect(status().isBadRequest());
        // a*c 小于 b^2
        create("req-m4", "pt-m4", "0", "0", "1.0", "1.5", "1.0", "op-1").andExpect(status().isBadRequest());
        // 小数位超过三位
        create("req-m5", "pt-m5", "1.0001", "0", "1.0", "0", "1.0", "op-1")
                .andExpect(status().isBadRequest());
        assertThat(versionCount("pt-m1")).isZero();
    }

    @Test
    void createDuplicateReturns409() throws Exception {
        create("req-d1", "pt-d1", "0", "0", "1.0", "0", "1.0", "op-1").andExpect(status().isCreated());
        create("req-d2", "pt-d1", "1", "1", "1.0", "0", "1.0", "op-1").andExpect(status().isConflict());
        getCurrent("pt-d1").andExpect(jsonPath("$.x").value("0.000"));
        assertThat(versionCount("pt-d1")).isEqualTo(1);
    }

    @Test
    void createIdempotentReplayAndDecimalByValue() throws Exception {
        create("req-i1", "pt-i1", "4.0", "0", "1.0", "0", "1.0", "op-1").andExpect(status().isCreated());
        // 同键同参重放：十进制按值比较，4.00 与 4.0 视为同参
        create("req-i1", "pt-i1", "4.00", "0.0", "1.00", "0.000", "1", "op-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.x").value("4.000"));
        assertThat(versionCount("pt-i1")).isEqualTo(1);
    }

    @Test
    void createSameRequestIdDifferentParamsReturns409() throws Exception {
        create("req-i2", "pt-i2", "0", "0", "1.0", "0", "1.0", "op-1").andExpect(status().isCreated());
        create("req-i2", "pt-i2", "9", "9", "1.0", "0", "1.0", "op-1").andExpect(status().isConflict());
        assertThat(versionCount("pt-i2")).isEqualTo(1);
    }

    @Test
    void failedCreateDoesNotOccupyRequestId() throws Exception {
        create("req-i3", "pt-i3", "0", "0", "-1.0", "0", "1.0", "op-1").andExpect(status().isBadRequest());
        // 失败不占键：同一 requestId 修正参数后可成功
        create("req-i3", "pt-i3", "0", "0", "1.0", "0", "1.0", "op-1").andExpect(status().isCreated());
        assertThat(versionCount("pt-i3")).isEqualTo(1);
    }

    // ---------- 三方合并（坐标与矩阵作为完整字段组） ----------

    @Test
    void mergeAcceptsCandidateWhenCurrentUnchanged() throws Exception {
        create("req-g1", "pt-g1", "0", "0", "1.0", "0", "1.0", "op-1").andExpect(status().isCreated());
        merge("req-g2", "pt-g1", 1, "1.5", "0", "1.0", "0", "1.0", "op-2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.x").value("1.500"));
        // 历史版本保留旧字段组
        getVersion("pt-g1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.x").value("0.000"));
        assertThat(versionCount("pt-g1")).isEqualTo(2);
    }

    @Test
    void mergeCandidateUnchangedKeepsCurrentWithoutBump() throws Exception {
        create("req-g3", "pt-g2", "0", "0", "1.0", "0", "1.0", "op-1").andExpect(status().isCreated());
        merge("req-g4", "pt-g2", 1, "2", "0", "1.0", "0", "1.0", "op-2").andExpect(status().isOk());
        // 离线端基于 v1 提交但未改动字段组：保留当前，不加版本
        merge("req-g5", "pt-g2", 1, "0", "0", "1.0", "0", "1.0", "op-3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.x").value("2.000"));
        assertThat(versionCount("pt-g2")).isEqualTo(2);
    }

    @Test
    void mergeBothChangedDifferentlyConflictsAsWholeGroup() throws Exception {
        create("req-g6", "pt-g3", "0", "0", "1.0", "0", "1.0", "op-1").andExpect(status().isCreated());
        // 服务端只改 x（组内单字段）
        merge("req-g7", "pt-g3", 1, "1", "0", "1.0", "0", "1.0", "op-2").andExpect(status().isOk());
        // 离线端基于 v1 只改 a：双方对同一字段组做了不同改动，整组冲突，不逐字段拼装
        merge("req-g8", "pt-g3", 1, "0", "0", "2.0", "0", "1.0", "op-3")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("position"))
                .andExpect(jsonPath("$.currentVersion").value(2));
        // 冲突不落任何部分结果：当前仍是服务端版本，a 未被拼装为 2
        getCurrent("pt-g3")
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.x").value("1.000"))
                .andExpect(jsonPath("$.a").value("1.000"));
        assertThat(versionCount("pt-g3")).isEqualTo(2);
    }

    @Test
    void mergeBothChangedToSameValueAccepted() throws Exception {
        create("req-g9", "pt-g4", "0", "0", "1.0", "0", "1.0", "op-1").andExpect(status().isCreated());
        merge("req-ga", "pt-g4", 1, "3", "3", "2.0", "0.5", "2.0", "op-2").andExpect(status().isOk());
        // 离线端基于 v1 改为与服务端完全相同的字段组：接受且内容无变化，不加版本
        merge("req-gb", "pt-g4", 1, "3.0", "3.00", "2.00", "0.5", "2", "op-3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.x").value("3.000"));
        assertThat(versionCount("pt-g4")).isEqualTo(2);
    }

    @Test
    void mergeInvalidMatrixReturns400() throws Exception {
        create("req-gc", "pt-g5", "0", "0", "1.0", "0", "1.0", "op-1").andExpect(status().isCreated());
        merge("req-gd", "pt-g5", 1, "0", "0", "1.0", "2.0", "1.0", "op-2")
                .andExpect(status().isBadRequest());
        getCurrent("pt-g5").andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void mergeBaseVersionNotFoundReturns404() throws Exception {
        create("req-ge", "pt-g6", "0", "0", "1.0", "0", "1.0", "op-1").andExpect(status().isCreated());
        merge("req-gf", "pt-g6", 7, "0", "0", "1.0", "0", "1.0", "op-2").andExpect(status().isNotFound());
    }

    // ---------- 删除与墓碑 ----------

    @Test
    void deleteVersionMismatchReturns409() throws Exception {
        create("req-t1", "pt-t1", "0", "0", "1.0", "0", "1.0", "op-1").andExpect(status().isCreated());
        deleteObservation("req-t2", "pt-t1", 3, "op-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(1));
        assertThat(versionCount("pt-t1")).isEqualTo(1);
    }

    @Test
    void deleteCreatesTombstoneAndMergeReturns410() throws Exception {
        create("req-t3", "pt-t2", "1", "1", "1.0", "0", "1.0", "op-1").andExpect(status().isCreated());
        deleteObservation("req-t4", "pt-t2", 1, "op-2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.x").doesNotExist());
        getCurrent("pt-t2")
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.x").doesNotExist());
        // 墓碑版本不带业务字段；旧版本快照保持原值，不能从当前字段补齐
        getVersion("pt-t2", 2)
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.x").doesNotExist());
        getVersion("pt-t2", 1)
                .andExpect(jsonPath("$.x").value("1.000"));
        merge("req-t5", "pt-t2", 1, "2", "2", "1.0", "0", "1.0", "op-3").andExpect(status().isGone());
        deleteObservation("req-t6", "pt-t2", 2, "op-3").andExpect(status().isGone());
    }

    // ---------- 一致性确认 ----------

    @Test
    void confirmSyntheticIdentityMatricesAccepted() throws Exception {
        // 合成场景：两矩阵均为单位阵，坐标差 (4,0)：左侧 2*16=32 不超过右侧 9*4=36
        create("req-cf1", "pt-a", "0", "0", "1.0", "0", "1.0", "op-1").andExpect(status().isCreated());
        create("req-cf2", "pt-b", "4", "0", "1.0", "0", "1.0", "op-1").andExpect(status().isCreated());

        String body = confirm("req-cf3", "pt-a", 1, "pt-b", 1, "op-9")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.firstObservationId").value("pt-a"))
                .andExpect(jsonPath("$.firstVersion").value(1))
                .andExpect(jsonPath("$.secondObservationId").value("pt-b"))
                .andExpect(jsonPath("$.secondVersion").value(1))
                .andExpect(jsonPath("$.first.x").value("0.000"))
                .andExpect(jsonPath("$.second.x").value("4.000"))
                .andExpect(jsonPath("$.diffU").value("-4.000"))
                .andExpect(jsonPath("$.diffV").value("0.000"))
                .andExpect(jsonPath("$.sumA").value("2.000"))
                .andExpect(jsonPath("$.sumB").value("0.000"))
                .andExpect(jsonPath("$.sumC").value("2.000"))
                .andExpect(jsonPath("$.lhsValue").value("32.000000000"))
                .andExpect(jsonPath("$.rhsValue").value("36.000000"))
                .andExpect(jsonPath("$.operator").value("op-9"))
                .andReturn().getResponse().getContentAsString();
        String confirmationId = objectMapper.readTree(body).get("confirmationId").asText();

        // 确认只保存关系，不改动源观测点
        getCurrent("pt-a").andExpect(jsonPath("$.version").value(1));
        getCurrent("pt-b").andExpect(jsonPath("$.version").value(1));
        assertThat(versionCount("pt-a")).isEqualTo(1);
        assertThat(confirmationCount()).isEqualTo(1);

        mockMvc.perform(get("/api/positioned-observations/confirmations/{id}", confirmationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmationId").value(confirmationId))
                .andExpect(jsonPath("$.lhsValue").value("32.000000000"));
        mockMvc.perform(get("/api/positioned-observations/{id}/confirmations", "pt-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].confirmationId").value(confirmationId));
    }

    @Test
    void confirmExceededReturns422AndFailureDoesNotOccupyRequestId() throws Exception {
        // 合成场景：坐标差 (4.243,0)，左侧 2*4.243^2=36.006098 超过右侧 36，应拒绝
        create("req-ce1", "pt-c", "0", "0", "1.0", "0", "1.0", "op-1").andExpect(status().isCreated());
        create("req-ce2", "pt-d", "4.243", "0", "1.0", "0", "1.0", "op-1").andExpect(status().isCreated());

        confirm("req-ce3", "pt-c", 1, "pt-d", 1, "op-9").andExpect(status().isUnprocessableEntity());
        assertThat(confirmationCount()).isZero();

        // 失败不占键：修正位置后同一 requestId 携带新期望版本可成功
        merge("req-ce4", "pt-d", 1, "4", "0", "1.0", "0", "1.0", "op-2").andExpect(status().isOk());
        confirm("req-ce3", "pt-c", 1, "pt-d", 2, "op-9")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secondVersion").value(2))
                .andExpect(jsonPath("$.lhsValue").value("32.000000000"));
        assertThat(confirmationCount()).isEqualTo(1);
    }

    @Test
    void confirmSameObservationReturns400() throws Exception {
        create("req-cs1", "pt-e", "0", "0", "1.0", "0", "1.0", "op-1").andExpect(status().isCreated());
        confirm("req-cs2", "pt-e", 1, "pt-e", 1, "op-9").andExpect(status().isBadRequest());
        assertThat(confirmationCount()).isZero();
    }

    @Test
    void confirmMissingObservationReturns404() throws Exception {
        create("req-cn1", "pt-f", "0", "0", "1.0", "0", "1.0", "op-1").andExpect(status().isCreated());
        confirm("req-cn2", "pt-f", 1, "pt-missing", 1, "op-9").andExpect(status().isNotFound());
    }

    @Test
    void confirmStaleVersionReturns409() throws Exception {
        create("req-cv1", "pt-g", "0", "0", "1.0", "0", "1.0", "op-1").andExpect(status().isCreated());
        create("req-cv2", "pt-h", "1", "0", "1.0", "0", "1.0", "op-1").andExpect(status().isCreated());
        confirm("req-cv3", "pt-g", 2, "pt-h", 1, "op-9")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(1));
        assertThat(confirmationCount()).isZero();
    }

    @Test
    void confirmTombstoneReturns422() throws Exception {
        create("req-ct1", "pt-i", "0", "0", "1.0", "0", "1.0", "op-1").andExpect(status().isCreated());
        create("req-ct2", "pt-j", "1", "0", "1.0", "0", "1.0", "op-1").andExpect(status().isCreated());
        deleteObservation("req-ct3", "pt-j", 1, "op-2").andExpect(status().isOk());
        // 墓碑不能参与确认，也不能从当前字段补齐旧版本
        confirm("req-ct4", "pt-i", 1, "pt-j", 2, "op-9").andExpect(status().isUnprocessableEntity());
        assertThat(confirmationCount()).isZero();
    }

    @Test
    void confirmIdempotentReplayAndParamChange409() throws Exception {
        create("req-ci1", "pt-k", "0", "0", "1.0", "0", "1.0", "op-1").andExpect(status().isCreated());
        create("req-ci2", "pt-l", "4", "0", "1.0", "0", "1.0", "op-1").andExpect(status().isCreated());

        String body = confirm("req-ci3", "pt-k", 1, "pt-l", 1, "op-9")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String confirmationId = objectMapper.readTree(body).get("confirmationId").asText();

        // 同键同参重放：返回首次完整结果（同一 confirmationId），不新增记录
        confirm("req-ci3", "pt-k", 1, "pt-l", 1, "op-9")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.confirmationId").value(confirmationId));
        assertThat(confirmationCount()).isEqualTo(1);

        // 同键异参：409
        confirm("req-ci3", "pt-k", 1, "pt-l", 1, "op-other").andExpect(status().isConflict());
        assertThat(confirmationCount()).isEqualTo(1);
    }

    @Test
    void confirmationFrozenAfterLaterChanges() throws Exception {
        create("req-cz1", "pt-m", "0", "0", "1.0", "0", "1.0", "op-1").andExpect(status().isCreated());
        create("req-cz2", "pt-n", "4", "0", "1.0", "0", "1.0", "op-1").andExpect(status().isCreated());
        String body = confirm("req-cz3", "pt-m", 1, "pt-n", 1, "op-9")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String confirmationId = objectMapper.readTree(body).get("confirmationId").asText();

        // 后续改测：pt-m 移到远处并换矩阵
        merge("req-cz4", "pt-m", 1, "100", "100", "3.0", "0", "3.0", "op-2")
                .andExpect(status().isOk());

        // 历史确认记录保持冻结值，不随源观测点变化
        mockMvc.perform(get("/api/positioned-observations/confirmations/{id}", confirmationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstVersion").value(1))
                .andExpect(jsonPath("$.first.x").value("0.000"))
                .andExpect(jsonPath("$.first.a").value("1.000"))
                .andExpect(jsonPath("$.lhsValue").value("32.000000000"))
                .andExpect(jsonPath("$.rhsValue").value("36.000000"));
        mockMvc.perform(get("/api/positioned-observations/{id}/confirmations", "pt-m"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/positioned-observations/confirmations/{id}", "missing"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/positioned-observations/{id}/confirmations", "missing"))
                .andExpect(status().isNotFound());
    }
}