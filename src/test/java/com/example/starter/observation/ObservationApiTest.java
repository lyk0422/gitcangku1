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
 * 现场观测离线合并 API 的主流程、失败分支与幂等边界测试（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ObservationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    private ResultActions create(String requestId, String observationId,
                                 String location, String reading, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
        return mockMvc.perform(post("/api/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions merge(String requestId, String observationId, int baseVersion,
                                String location, String reading, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
        return mockMvc.perform(post("/api/observations/{id}/merge", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions delete(String requestId, String observationId, int expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedVersion", expectedVersion);
        return mockMvc.perform(post("/api/observations/{id}/delete", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getCurrent(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}", observationId));
    }

    private ResultActions getVersion(String observationId, int version) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/versions/{version}", observationId, version));
    }

    private int versionCount(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = ?", Integer.class, observationId);
        return count == null ? 0 : count;
    }

    // ---------- 创建 ----------

    @Test
    void createReturnsVersionOne() throws Exception {
        create("req-c1", "obs-1", "站点A", "12.345", "首次记录")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.observationId").value("obs-1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.location").value("站点A"))
                .andExpect(jsonPath("$.reading").value("12.345"))
                .andExpect(jsonPath("$.note").value("首次记录"));

        getCurrent("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.location").value("站点A"));
        assertThat(versionCount("obs-1")).isEqualTo(1);
    }

    @Test
    void createDuplicateObservationIdReturns409() throws Exception {
        create("req-c2", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        create("req-c3", "obs-1", "站点B", "2.0", "其他")
                .andExpect(status().isConflict());
        getCurrent("obs-1").andExpect(jsonPath("$.location").value("站点A"));
        assertThat(versionCount("obs-1")).isEqualTo(1);
    }

    @Test
    void createWithInvalidReadingReturns400() throws Exception {
        create("req-c4", "obs-1", "站点A", "1.2345", "四位小数")
                .andExpect(status().isBadRequest());
        create("req-c5", "obs-1", "站点A", "abc", "非数字")
                .andExpect(status().isBadRequest());
        assertThat(versionCount("obs-1")).isZero();
    }

    // ---------- 三方合并 ----------

    @Test
    void mergeAcceptsCandidateWhenCurrentUnchanged() throws Exception {
        create("req-m1", "obs-1", "站点A", "1.0", "旧备注").andExpect(status().isCreated());
        merge("req-m2", "obs-1", 1, "站点B", "2.5", "新备注")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.location").value("站点B"))
                .andExpect(jsonPath("$.reading").value("2.5"))
                .andExpect(jsonPath("$.note").value("新备注"));
        assertThat(versionCount("obs-1")).isEqualTo(2);
        getVersion("obs-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location").value("站点A"));
    }

    @Test
    void mergeKeepsCurrentWhenCandidateUnchanged() throws Exception {
        create("req-m3", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        // 服务端前进到版本 2：地点改为站点B
        merge("req-m4", "obs-1", 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        // 离线端基于版本 1 提交：地点未改（仍为站点A），只改备注
        merge("req-m5", "obs-1", 1, "站点A", "1.0", "离线备注")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.location").value("站点B"))
                .andExpect(jsonPath("$.note").value("离线备注"));
    }

    @Test
    void mergeAcceptsWhenBothSidesChangedToSameValue() throws Exception {
        create("req-m6", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-m7", "obs-1", 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        // 离线端也改成站点B：两边相同，接受且与当前相同，不加版本
        merge("req-m8", "obs-1", 1, "站点B", "1.0", "备注")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.location").value("站点B"));
        assertThat(versionCount("obs-1")).isEqualTo(2);
    }

    @Test
    void mergeConflictReturns409AndWritesNothing() throws Exception {
        create("req-m9", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-m10", "obs-1", 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        // 离线端基于版本 1 把地点改为站点C：与服务端的站点B冲突
        merge("req-m11", "obs-1", 1, "站点C", "1.0", "离线备注")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("location"))
                .andExpect(jsonPath("$.currentVersion").value(2));
        // 不写入任何部分结果：当前内容与版本不变，离线备注也未生效
        getCurrent("obs-1")
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.location").value("站点B"))
                .andExpect(jsonPath("$.note").value("备注"));
        assertThat(versionCount("obs-1")).isEqualTo(2);
    }

    @Test
    void mergeReadingComparedNumerically() throws Exception {
        create("req-m12", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        // 候选读数 1.00 与基线 1.0 数值相等：视为未改，保留当前；结果与当前相同，不加版本
        merge("req-m13", "obs-1", 1, "站点A", "1.00", "备注")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.reading").value("1.0"));
        assertThat(versionCount("obs-1")).isEqualTo(1);
    }

    @Test
    void mergeIdenticalToCurrentDoesNotAddVersion() throws Exception {
        create("req-m14", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        // 离线端把备注改为“新备注”后又改回：候选与基线相同，合并结果等于当前
        merge("req-m15", "obs-1", 1, "站点A", "1.0", "备注")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        assertThat(versionCount("obs-1")).isEqualTo(1);
    }

    @Test
    void mergeWithMissingBaseVersionReturns404() throws Exception {
        create("req-m16", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-m17", "obs-1", 9, "站点B", "2.0", "备注")
                .andExpect(status().isNotFound());
        assertThat(versionCount("obs-1")).isEqualTo(1);
    }

    @Test
    void mergeOnMissingObservationReturns404() throws Exception {
        merge("req-m18", "obs-x", 1, "站点B", "2.0", "备注")
                .andExpect(status().isNotFound());
    }

    // ---------- 删除与墓碑 ----------

    @Test
    void deleteCreatesTombstoneVersion() throws Exception {
        create("req-d1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-d2", "obs-1", 1, "站点B", "2.0", "备注2").andExpect(status().isOk());

        delete("req-d3", "obs-1", 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.location").doesNotExist())
                .andExpect(jsonPath("$.reading").doesNotExist())
                .andExpect(jsonPath("$.note").doesNotExist());

        // 当前查询：墓碑只返回删除状态和版本
        getCurrent("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.location").doesNotExist());
        // 墓碑历史版本同样不带业务字段
        getVersion("obs-1", 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.location").doesNotExist());
        // 历史版本仍可读取业务字段
        getVersion("obs-1", 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location").value("站点B"))
                .andExpect(jsonPath("$.deleted").value(false));
        assertThat(versionCount("obs-1")).isEqualTo(3);
    }

    @Test
    void deleteWithMismatchedVersionReturns409() throws Exception {
        create("req-d4", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        delete("req-d5", "obs-1", 7)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(1));
        getCurrent("obs-1")
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void deletedObservationRejectsMergeAndDeleteWith410() throws Exception {
        create("req-d6", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        delete("req-d7", "obs-1", 1).andExpect(status().isOk());
        merge("req-d8", "obs-1", 1, "站点B", "2.0", "备注")
                .andExpect(status().isGone());
        delete("req-d9", "obs-1", 2)
                .andExpect(status().isGone());
        // 墓碑不被复活
        getCurrent("obs-1")
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.version").value(2));
        assertThat(versionCount("obs-1")).isEqualTo(2);
    }

    @Test
    void deleteOnMissingObservationReturns404() throws Exception {
        delete("req-d10", "obs-x", 1).andExpect(status().isNotFound());
    }

    // ---------- 查询 ----------

    @Test
    void getMissingObservationReturns404() throws Exception {
        getCurrent("obs-x").andExpect(status().isNotFound());
        getVersion("obs-x", 1).andExpect(status().isNotFound());
    }

    @Test
    void getMissingVersionReturns404() throws Exception {
        create("req-q1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        getVersion("obs-1", 5).andExpect(status().isNotFound());
    }

    // ---------- 幂等 ----------

    @Test
    void sameRequestIdAndParamsReplaysOriginalResult() throws Exception {
        create("req-i1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        // 同键同参重放：返回原成功结果，不产生新版本
        create("req-i1", "obs-1", "站点A", "1.0", "备注")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
        assertThat(versionCount("obs-1")).isEqualTo(1);

        merge("req-i2", "obs-1", 1, "站点B", "2.0", "备注").andExpect(status().isOk());
        merge("req-i2", "obs-1", 1, "站点B", "2.0", "备注")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        assertThat(versionCount("obs-1")).isEqualTo(2);

        delete("req-i3", "obs-1", 2).andExpect(status().isOk());
        delete("req-i3", "obs-1", 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.deleted").value(true));
        assertThat(versionCount("obs-1")).isEqualTo(3);
    }

    @Test
    void sameRequestIdWithDifferentParamsReturns409() throws Exception {
        create("req-i4", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        create("req-i4", "obs-2", "站点A", "1.0", "备注")
                .andExpect(status().isConflict());
        create("req-i4", "obs-1", "站点B", "1.0", "备注")
                .andExpect(status().isConflict());
        assertThat(versionCount("obs-1")).isEqualTo(1);
        assertThat(versionCount("obs-2")).isZero();
    }

    @Test
    void failedRequestDoesNotOccupyRequestId() throws Exception {
        create("req-f1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        // 冲突失败：不占键
        merge("req-f2", "obs-1", 9, "站点B", "2.0", "备注").andExpect(status().isNotFound());
        // 同一 requestId 换上合法参数后应正常执行
        merge("req-f2", "obs-1", 1, "站点B", "2.0", "备注")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }
}
