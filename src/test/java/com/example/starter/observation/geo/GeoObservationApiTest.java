package com.example.starter.observation.geo;

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
 * 二维坐标观测 API 的主流程、失败分支与幂等边界测试（真实 H2 内存库，MySQL 兼容模式）。
 * 坐标与协方差矩阵作为一个完整字段组参与三方合并。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GeoObservationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM consistency_confirmation");
        jdbcTemplate.update("DELETE FROM geo_observation_version");
        jdbcTemplate.update("DELETE FROM geo_observation_current");
    }

    private ResultActions create(String requestId, String observationId, String x, String y,
                                 String a, String b, String c, String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("x", x);
        body.put("y", y);
        body.put("a", a);
        body.put("b", b);
        body.put("c", c);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/geo-observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions merge(String requestId, String observationId, int baseVersion, String x, String y,
                                String a, String b, String c, String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("x", x);
        body.put("y", y);
        body.put("a", a);
        body.put("b", b);
        body.put("c", c);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/geo-observations/{id}/merge", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions delete(String requestId, String observationId, int expectedVersion,
                                 String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedVersion", expectedVersion);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/geo-observations/{id}/delete", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getCurrent(String observationId) throws Exception {
        return mockMvc.perform(get("/api/geo-observations/{id}", observationId));
    }

    private ResultActions getVersion(String observationId, int version) throws Exception {
        return mockMvc.perform(get("/api/geo-observations/{id}/versions/{version}", observationId, version));
    }

    private int versionCount(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM geo_observation_version WHERE observation_id = ?",
                Integer.class, observationId);
        return count == null ? 0 : count;
    }

    // ---------- 创建 ----------

    @Test
    void createReturnsVersionOne() throws Exception {
        create("req-g1", "geo-1", "10.5", "-3.25", "1.5", "0.25", "2", "op-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.observationId").value("geo-1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.x").value("10.5"))
                .andExpect(jsonPath("$.y").value("-3.25"))
                .andExpect(jsonPath("$.a").value("1.5"))
                .andExpect(jsonPath("$.b").value("0.25"))
                .andExpect(jsonPath("$.c").value("2"));
        getCurrent("geo-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.x").value("10.5"));
        assertThat(versionCount("geo-1")).isEqualTo(1);
    }

    @Test
    void createWithInvalidMatrixReturns400() throws Exception {
        // a 非正
        create("req-g2", "geo-1", "0", "0", "0", "0", "1", "op-1").andExpect(status().isBadRequest());
        // c 非正
        create("req-g3", "geo-1", "0", "0", "1", "0", "-1", "op-1").andExpect(status().isBadRequest());
        // a*c == b*b：不满足严格大于
        create("req-g4", "geo-1", "0", "0", "1", "1", "1", "op-1").andExpect(status().isBadRequest());
        // a*c < b*b
        create("req-g5", "geo-1", "0", "0", "1", "2", "1", "op-1").andExpect(status().isBadRequest());
        assertThat(versionCount("geo-1")).isZero();
    }

    @Test
    void createWithInvalidDecimalOrBlankOperatorReturns400() throws Exception {
        // 小数超过三位
        create("req-g6", "geo-1", "1.2345", "0", "1", "0", "1", "op-1").andExpect(status().isBadRequest());
        // 非数字
        create("req-g7", "geo-1", "abc", "0", "1", "0", "1", "op-1").andExpect(status().isBadRequest());
        // 操作者为空
        create("req-g8", "geo-1", "0", "0", "1", "0", "1", "").andExpect(status().isBadRequest());
        create("req-g9", "geo-1", "0", "0", "1", "0", "1", null).andExpect(status().isBadRequest());
        assertThat(versionCount("geo-1")).isZero();
    }

    @Test
    void createDuplicateObservationIdReturns409() throws Exception {
        create("req-g10", "geo-1", "0", "0", "1", "0", "1", "op-1").andExpect(status().isCreated());
        create("req-g11", "geo-1", "1", "1", "1", "0", "1", "op-1").andExpect(status().isConflict());
        getCurrent("geo-1").andExpect(jsonPath("$.x").value("0"));
        assertThat(versionCount("geo-1")).isEqualTo(1);
    }

    // ---------- 三方合并（坐标+矩阵整组） ----------

    @Test
    void mergeAcceptsCandidateWhenCurrentUnchanged() throws Exception {
        create("req-g12", "geo-1", "0", "0", "1", "0", "1", "op-1").andExpect(status().isCreated());
        merge("req-g13", "geo-1", 1, "4", "0", "1", "0", "1", "op-2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.x").value("4"));
        assertThat(versionCount("geo-1")).isEqualTo(2);
        getVersion("geo-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.x").value("0"));
    }

    @Test
    void mergeKeepsCurrentWhenCandidateUnchanged() throws Exception {
        create("req-g14", "geo-1", "0", "0", "1", "0", "1", "op-1").andExpect(status().isCreated());
        merge("req-g15", "geo-1", 1, "4", "0", "1", "0", "1", "op-2").andExpect(status().isOk());
        // 离线端基于版本 1 提交且整组未改：保留当前（版本 2 的值），结果与当前相同，不加版本
        merge("req-g16", "geo-1", 1, "0", "0", "1", "0", "1", "op-3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.x").value("4"));
        assertThat(versionCount("geo-1")).isEqualTo(2);
    }

    @Test
    void mergeConflictReturns409AndWritesNothing() throws Exception {
        create("req-g17", "geo-1", "0", "0", "1", "0", "1", "op-1").andExpect(status().isCreated());
        // 服务端整组改为 (4,0)
        merge("req-g18", "geo-1", 1, "4", "0", "1", "0", "1", "op-2").andExpect(status().isOk());
        // 离线端基于版本 1 只改了矩阵 b：字段组整体仍视为“双方不同改动”，整组冲突，不逐字段拼装
        merge("req-g19", "geo-1", 1, "0", "0", "1", "0.5", "1", "op-3")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("geo"))
                .andExpect(jsonPath("$.currentVersion").value(2));
        getCurrent("geo-1")
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.x").value("4"))
                .andExpect(jsonPath("$.b").value("0"));
        assertThat(versionCount("geo-1")).isEqualTo(2);
    }

    @Test
    void mergeAcceptsWhenBothSidesChangedToSameValue() throws Exception {
        create("req-g20", "geo-1", "0", "0", "1", "0", "1", "op-1").andExpect(status().isCreated());
        merge("req-g21", "geo-1", 1, "4", "0", "1", "0", "1", "op-2").andExpect(status().isOk());
        // 两边改为相同整组值（十进制按数值：4.0 与 4 相等）：接受且与当前相同，不加版本
        merge("req-g22", "geo-1", 1, "4.0", "0.00", "1.0", "0", "1", "op-3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.x").value("4"));
        assertThat(versionCount("geo-1")).isEqualTo(2);
    }

    @Test
    void mergeWithInvalidMatrixReturns400() throws Exception {
        create("req-g23", "geo-1", "0", "0", "1", "0", "1", "op-1").andExpect(status().isCreated());
        merge("req-g24", "geo-1", 1, "0", "0", "1", "1", "1", "op-2").andExpect(status().isBadRequest());
        assertThat(versionCount("geo-1")).isEqualTo(1);
    }

    @Test
    void mergeOnTombstoneReturns410() throws Exception {
        create("req-g25", "geo-1", "0", "0", "1", "0", "1", "op-1").andExpect(status().isCreated());
        delete("req-g26", "geo-1", 1, "op-2").andExpect(status().isOk());
        merge("req-g27", "geo-1", 1, "4", "0", "1", "0", "1", "op-3").andExpect(status().isGone());
        assertThat(versionCount("geo-1")).isEqualTo(2);
    }

    // ---------- 删除与墓碑 ----------

    @Test
    void deleteCreatesTombstoneAndHistoryStaysReadable() throws Exception {
        create("req-g28", "geo-1", "1", "2", "1", "0", "1", "op-1").andExpect(status().isCreated());
        merge("req-g29", "geo-1", 1, "5", "6", "2", "0.5", "2", "op-2").andExpect(status().isOk());

        delete("req-g30", "geo-1", 2, "op-3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.x").doesNotExist())
                .andExpect(jsonPath("$.a").doesNotExist());

        getCurrent("geo-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.x").doesNotExist());
        // 历史版本仍返回当时完整快照，不从当前字段补齐
        getVersion("geo-1", 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.x").value("5"))
                .andExpect(jsonPath("$.y").value("6"))
                .andExpect(jsonPath("$.deleted").value(false));
        assertThat(versionCount("geo-1")).isEqualTo(3);
    }

    @Test
    void deleteWithMismatchedVersionReturns409() throws Exception {
        create("req-g31", "geo-1", "0", "0", "1", "0", "1", "op-1").andExpect(status().isCreated());
        delete("req-g32", "geo-1", 7, "op-2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(1));
        getCurrent("geo-1")
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.version").value(1));
    }

    // ---------- 幂等 ----------

    @Test
    void sameRequestIdAndParamsReplaysOriginalResult() throws Exception {
        create("req-g33", "geo-1", "1.0", "0", "1", "0", "1", "op-1").andExpect(status().isCreated());
        // 十进制按值比较：1.00 与 1.0 视为同参，重放首次完整结果，不产生新版本
        create("req-g33", "geo-1", "1.00", "0.0", "1.0", "0.00", "1", "op-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.x").value("1.0"));
        assertThat(versionCount("geo-1")).isEqualTo(1);

        merge("req-g34", "geo-1", 1, "4", "0", "1", "0", "1", "op-2").andExpect(status().isOk());
        merge("req-g34", "geo-1", 1, "4.000", "0", "1", "0", "1", "op-2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        assertThat(versionCount("geo-1")).isEqualTo(2);

        delete("req-g35", "geo-1", 2, "op-3").andExpect(status().isOk());
        delete("req-g35", "geo-1", 2, "op-3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.deleted").value(true));
        assertThat(versionCount("geo-1")).isEqualTo(3);
    }

    @Test
    void sameRequestIdWithDifferentParamsReturns409() throws Exception {
        create("req-g36", "geo-1", "0", "0", "1", "0", "1", "op-1").andExpect(status().isCreated());
        // 改资源
        create("req-g36", "geo-2", "0", "0", "1", "0", "1", "op-1").andExpect(status().isConflict());
        // 改参数（十进制值不同）
        create("req-g36", "geo-1", "1", "0", "1", "0", "1", "op-1").andExpect(status().isConflict());
        // 改操作者
        create("req-g36", "geo-1", "0", "0", "1", "0", "1", "op-2").andExpect(status().isConflict());
        assertThat(versionCount("geo-1")).isEqualTo(1);
        assertThat(versionCount("geo-2")).isZero();
    }

    @Test
    void failedRequestDoesNotOccupyRequestId() throws Exception {
        create("req-g37", "geo-1", "0", "0", "1", "0", "1", "op-1").andExpect(status().isCreated());
        // 基线版本不存在：失败不占键
        merge("req-g38", "geo-1", 9, "4", "0", "1", "0", "1", "op-2").andExpect(status().isNotFound());
        merge("req-g38", "geo-1", 1, "4", "0", "1", "0", "1", "op-2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }
}
