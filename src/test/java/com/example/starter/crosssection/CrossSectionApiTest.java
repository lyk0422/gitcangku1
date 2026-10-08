package com.example.starter.crosssection;

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
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 断面观测点与水域面积成果 API 的主流程、失败分支、幂等边界（真实 H2 内存库，MODE=MySQL）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CrossSectionApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM cross_section_result_point");
        jdbcTemplate.update("DELETE FROM cross_section_result");
        jdbcTemplate.update("DELETE FROM cross_section_resolution");
        jdbcTemplate.update("DELETE FROM cross_section_pending_conflict");
        jdbcTemplate.update("DELETE FROM cross_section_version");
        jdbcTemplate.update("DELETE FROM cross_section_current");
    }

    private ResultActions createPoint(String reqId, String obsId, String section, Integer x, Integer z)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", reqId);
        body.put("observationId", obsId);
        body.put("sectionNo", section);
        body.put("xMm", x);
        body.put("zMm", z);
        body.put("operator", "op-1");
        return mockMvc.perform(post("/api/cross-sections/points")
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions mergePoint(String reqId, String obsId, int baseVersion,
                                     String section, int x, int z) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", reqId);
        body.put("baseVersion", baseVersion);
        body.put("sectionNo", section);
        body.put("xMm", x);
        body.put("zMm", z);
        body.put("operator", "op-1");
        return mockMvc.perform(post("/api/cross-sections/points/{id}/merge", obsId)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions deletePoint(String reqId, String obsId, int expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", reqId);
        body.put("expectedVersion", expectedVersion);
        body.put("operator", "op-1");
        return mockMvc.perform(post("/api/cross-sections/points/{id}/delete", obsId)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions createResult(String reqId, String resultId, int h,
                                       List<Map<String, Object>> points) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", reqId);
        body.put("resultId", resultId);
        body.put("waterLevelMm", h);
        body.put("points", points);
        body.put("operator", "op-1");
        return mockMvc.perform(post("/api/cross-sections/results")
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private static Map<String, Object> ref(String obsId, int version) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("observationId", obsId);
        ref.put("expectedVersion", version);
        return ref;
    }

    // ---------- 观测点生命周期 ----------

    @Test
    void createPointReturnsVersionOneWithWholeGroup() throws Exception {
        createPoint("req-p1", "pt-1", "S1", 0, 0)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.observationId").value("pt-1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.sectionNo").value("S1"))
                .andExpect(jsonPath("$.xMm").value(0))
                .andExpect(jsonPath("$.zMm").value(0));
    }

    @Test
    void missingGroupFieldReturns422() throws Exception {
        // 缺 zMm：完整版本组不接受缺字段
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "req-p2");
        body.put("observationId", "pt-1");
        body.put("sectionNo", "S1");
        body.put("xMm", 0);
        body.put("operator", "op-1");
        mockMvc.perform(post("/api/cross-sections/points")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity());
        // 空请求体同样 422
        mockMvc.perform(post("/api/cross-sections/points")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void mergeWholeGroupVersionsAllFieldsAndConflictLeavesPending() throws Exception {
        createPoint("req-p3", "pt-1", "S1", 0, 0).andExpect(status().isCreated());
        // 服务端前进到版本 2：改高程
        mergePoint("req-p4", "pt-1", 1, "S1", 0, 10).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // 离线端基于版本 1 同时改横向：与服务端修改不冲突（不同字段），应自动合并出版本 3
        mergePoint("req-p5", "pt-1", 1, "S1", 5, 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.xMm").value(5))
                .andExpect(jsonPath("$.zMm").value(10));

        // 再造冲突：服务端基于版本 3 把高程改为 12（版本 4）；离线端基于版本 3 改高程为 8，两边互异 => 冲突
        mergePoint("req-p6a", "pt-1", 3, "S1", 5, 12).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4));
        mergePoint("req-p6", "pt-1", 3, "S1", 5, 8)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("zMm"))
                .andExpect(jsonPath("$.currentVersion").value(4));
        // 未决标记已留痕；冲突失败不占 requestId，换参数可复用
        mockMvc.perform(get("/api/cross-sections/points/pt-1/pending-conflict"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateZMm").value(8));
        // 当前版本未被污染
        mockMvc.perform(get("/api/cross-sections/points/pt-1"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.zMm").value(12));
    }

    @Test
    void tombstonePointMakesResultFail422AndBlocksMerge() throws Exception {
        createPoint("req-p7", "pt-a", "S1", 0, 0).andExpect(status().isCreated());
        createPoint("req-p8", "pt-b", "S1", 10, 10).andExpect(status().isCreated());
        deletePoint("req-p9", "pt-a", 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
        // 引用墓碑点生成成果：422，不生成成果
        createResult("req-p10", "res-1", 5, List.of(ref("pt-a", 2), ref("pt-b", 1)))
                .andExpect(status().isUnprocessableEntity());
        // 墓碑点拒绝再合并/删除
        mergePoint("req-p11", "pt-a", 1, "S1", 1, 1).andExpect(status().isUnprocessableEntity());
        deletePoint("req-p12", "pt-a", 2).andExpect(status().isUnprocessableEntity());
    }

    // ---------- 成果 ----------

    @Test
    void syntheticCallGivesExactArea25Over2AndFrozenPointList() throws Exception {
        createPoint("req-s1", "pt-0", "S1", 0, 0).andExpect(status().isCreated());
        createPoint("req-s2", "pt-10", "S1", 10, 10).andExpect(status().isCreated());

        createResult("req-s3", "res-syn", 5, List.of(ref("pt-0", 1), ref("pt-10", 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultId").value("res-syn"))
                .andExpect(jsonPath("$.sectionNo").value("S1"))
                .andExpect(jsonPath("$.waterLevelMm").value(5))
                .andExpect(jsonPath("$.areaNumerator").value(25))
                .andExpect(jsonPath("$.areaDenominator").value(2))
                .andExpect(jsonPath("$.areaText").value("12.500"))
                .andExpect(jsonPath("$.points.length()").value(2))
                .andExpect(jsonPath("$.points[0].xMm").value(0))
                .andExpect(jsonPath("$.points[0].zMm").value(0))
                .andExpect(jsonPath("$.points[1].xMm").value(10))
                .andExpect(jsonPath("$.points[1].zMm").value(10))
                .andExpect(jsonPath("$.sourceUpdated").value(false));

        mockMvc.perform(get("/api/cross-sections/results/res-syn"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.areaNumerator").value(25))
                .andExpect(jsonPath("$.areaText").value("12.500"));
    }

    @Test
    void staleExpectedVersionReturns409() throws Exception {
        createPoint("req-v1", "pt-0", "S1", 0, 0).andExpect(status().isCreated());
        createPoint("req-v2", "pt-10", "S1", 10, 10).andExpect(status().isCreated());
        mergePoint("req-v3", "pt-0", 1, "S1", 0, 2).andExpect(status().isOk());
        // 仍按版本 1 引用已前进到版本 2 的点：409
        createResult("req-v4", "res-v", 5, List.of(ref("pt-0", 1), ref("pt-10", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(2));
    }

    @Test
    void differentSectionOrNonIncreasingXReturns422WithoutSorting() throws Exception {
        createPoint("req-o1", "pt-a", "S1", 0, 0).andExpect(status().isCreated());
        createPoint("req-o2", "pt-b", "S2", 10, 10).andExpect(status().isCreated());
        // 不同断面：422
        createResult("req-o3", "res-o1", 5, List.of(ref("pt-a", 1), ref("pt-b", 1)))
                .andExpect(status().isUnprocessableEntity());

        createPoint("req-o4", "pt-c", "S1", 5, 10).andExpect(status().isCreated());
        createPoint("req-o4b", "pt-e", "S1", 10, 10).andExpect(status().isCreated());
        // 提交顺序乱序（10 在 0、5 前）：必须 422，服务端不得排序后接受
        createResult("req-o5", "res-o2", 5,
                List.of(ref("pt-e", 1), ref("pt-c", 1), ref("pt-a", 1)))
                .andExpect(status().isUnprocessableEntity());
        // 正确递增顺序可以成功
        createResult("req-o5b", "res-ok", 5,
                List.of(ref("pt-a", 1), ref("pt-c", 1), ref("pt-e", 1)))
                .andExpect(status().isCreated());
        // 同 x 也不允许：另建一个与 pt-a 同为 x=0 的点（断面相同）
        createPoint("req-o8", "pt-d", "S1", 0, 2).andExpect(status().isCreated());
        createResult("req-o9", "res-o4", 5,
                List.of(ref("pt-a", 1), ref("pt-d", 1)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void unresolvedConflictPointMakesResultFail422() throws Exception {
        createPoint("req-u1", "pt-a", "S1", 0, 0).andExpect(status().isCreated());
        createPoint("req-u2", "pt-b", "S1", 10, 10).andExpect(status().isCreated());
        mergePoint("req-u3", "pt-a", 1, "S1", 0, 3).andExpect(status().isOk());
        // 基于旧版本提交冲突，留下未决标记
        mergePoint("req-u4", "pt-a", 1, "S1", 0, 7)
                .andExpect(status().isConflict());
        createResult("req-u5", "res-u", 5, List.of(ref("pt-a", 2), ref("pt-b", 1)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void sameRequestIdReplaysResultAndDifferentParams409() throws Exception {
        createPoint("req-r1", "pt-0", "S1", 0, 0).andExpect(status().isCreated());
        createPoint("req-r2", "pt-10", "S1", 10, 10).andExpect(status().isCreated());
        List<Map<String, Object>> refs = List.of(ref("pt-0", 1), ref("pt-10", 1));
        createResult("req-r3", "res-r", 5, refs).andExpect(status().isCreated());
        // 同参重放：返回首次完整响应（含冻结点列），不新增成果
        createResult("req-r3", "res-r", 5, refs).andExpect(status().isCreated())
                .andExpect(jsonPath("$.areaNumerator").value(25));
        // 同键异参（改 H）：409
        createResult("req-r3", "res-r", 6, refs).andExpect(status().isConflict());
        Integer results = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cross_section_result WHERE request_id = 'req-r3'", Integer.class);
        org.assertj.core.api.Assertions.assertThat(results).isEqualTo(1);
    }

    @Test
    void failedResultDoesNotOccupyRequestId() throws Exception {        createPoint("req-f1", "pt-0", "S1", 0, 0).andExpect(status().isCreated());
        // 点数量不足：422
        createResult("req-f2", "res-f1", 5, List.of(ref("pt-0", 1)))
                .andExpect(status().isUnprocessableEntity());
        createPoint("req-f3", "pt-10", "S1", 10, 10).andExpect(status().isCreated());
        // 失败后复用同一 requestId 合法提交，应成功并返回首次完整响应
        createResult("req-f2", "res-f2", 5, List.of(ref("pt-0", 1), ref("pt-10", 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.areaText").value("12.500"));
    }

    @Test
    void business422FailureDoesNotOccupyRequestIdNorLeavePartialResult() throws Exception {
        createPoint("req-b1", "pt-0", "S1", 0, 0).andExpect(status().isCreated());
        createPoint("req-b2", "pt-10", "S1", 10, 10).andExpect(status().isCreated());
        deletePoint("req-b3", "pt-10", 1).andExpect(status().isOk());
        // 业务失败（引用墓碑 422）：不占键
        createResult("req-b4", "res-b1", 5, List.of(ref("pt-0", 1), ref("pt-10", 2)))
                .andExpect(status().isUnprocessableEntity());
        // 恢复源点引用（此处新建不同标识），同一 requestId 可成功复用
        createPoint("req-b5", "pt-11", "S1", 10, 10).andExpect(status().isCreated());
        createResult("req-b4", "res-b2", 5, List.of(ref("pt-0", 1), ref("pt-11", 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.areaNumerator").value(25));
        // 失败的半成品成果不存在，成功成果点列完整
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cross_section_result", Integer.class)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cross_section_result_point", Integer.class)).isEqualTo(2);
    }

    @Test
    void conflictMergeFailureDoesNotOccupyRequestId() throws Exception {
        createPoint("req-x1", "pt-1", "S1", 0, 0).andExpect(status().isCreated());
        mergePoint("req-x2", "pt-1", 1, "S1", 0, 10).andExpect(status().isOk());
        mergePoint("req-x3a", "pt-1", 2, "S1", 0, 12).andExpect(status().isOk());
        // 基于版本 2 的冲突提交失败：requestId req-x3 未被占用
        mergePoint("req-x3", "pt-1", 2, "S1", 0, 8).andExpect(status().isConflict());
        // 同一 requestId 换成合法参数（基于版本 3 提交新高程 14）可成功并出版本 4
        mergePoint("req-x3", "pt-1", 3, "S1", 0, 14).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4));
        // 成功的无冲突提交会清除旧未决标记
        mockMvc.perform(get("/api/cross-sections/points/pt-1/pending-conflict"))
                .andExpect(status().isNotFound());
    }
}
