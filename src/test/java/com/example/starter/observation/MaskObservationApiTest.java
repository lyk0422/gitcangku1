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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 4x4 栅格观测 API 的主流程、失败分支与幂等边界（真实 H2 内存库，MODE=MySQL，逐格落表）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MaskObservationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM mask_version_cell");
        jdbcTemplate.update("DELETE FROM mask_observation_version");
        jdbcTemplate.update("DELETE FROM mask_observation_current");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    /** 按行字符串构造 16 格：'1' 勾选，其余为 0。 */
    private static List<Integer> cells(String... rows) {
        List<Integer> values = new ArrayList<>();
        for (String row : rows) {
            for (int col = 0; col < MaskGrid.SIZE; col++) {
                values.add(row.charAt(col) == '1' ? 1 : 0);
            }
        }
        return values;
    }

    private ResultActions create(String requestId, String id, String operator, List<Integer> cells) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", id);
        body.put("operator", operator);
        body.put("cells", cells);
        return mockMvc.perform(post("/api/mask-observations")
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions merge(String requestId, String id, int baseVersion, String operator,
                                List<Integer> cells) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("operator", operator);
        body.put("cells", cells);
        return mockMvc.perform(post("/api/mask-observations/{id}/merge", id)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions delete(String requestId, String id, int expectedVersion, String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedVersion", expectedVersion);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/mask-observations/{id}/delete", id)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getCurrent(String id) throws Exception {
        return mockMvc.perform(get("/api/mask-observations/{id}", id));
    }

    @Test
    void createPersistsSixteenCellsAtVersionOne() throws Exception {
        create("req-c1", "mask-1", "alice", cells("1100", "1100", "0000", "0000"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.baseVersion").doesNotExist())
                .andExpect(jsonPath("$.operator").value("alice"))
                .andExpect(jsonPath("$.cells.length()").value(16))
                .andExpect(jsonPath("$.cells[0]").value(1))
                .andExpect(jsonPath("$.cells[5]").value(1));
        Integer cellRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mask_version_cell WHERE observation_id = 'mask-1' AND version = 1",
                Integer.class);
        assertThat(cellRows).isEqualTo(16);
    }

    @Test
    void createWithDisconnectedOrEmptyCellsReturns400() throws Exception {
        create("req-c2", "mask-1", "alice", cells("1000", "0100", "0000", "0000"))
                .andExpect(status().isBadRequest());
        create("req-c3", "mask-1", "alice", cells("0000", "0000", "0000", "0000"))
                .andExpect(status().isBadRequest());
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mask_observation_current", Integer.class);
        assertThat(rows).isZero();
    }

    @Test
    void headlineDiagonalDeletionsReturn422AndKeepServerVersion() throws Exception {
        // 基版：(1,1)(1,2)(2,1)(2,2) 田字
        create("req-h0", "mask-h", "root", cells("1100", "1100", "0000", "0000"))
                .andExpect(status().isCreated());
        // 服务器删 (2,1)=下标4，服务端版本前进到 2，剩余三格仍连通
        merge("req-h1", "mask-h", 1, "server", cells("1100", "0100", "0000", "0000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.baseVersion").value(1))
                .andExpect(jsonPath("$.operator").value("server"));
        // 客户端基于版本 1 删 (1,2)=下标1，客户端全图自身连通；合并后剩斜角两格
        merge("req-h2", "mask-h", 1, "client", cells("1000", "1100", "0000", "0000"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.currentVersion").value(2));
        // 版本和全部格值不变，不补桥：当前仍是服务器版 2 的三格
        getCurrent("mask-h")
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.cells[1]").value(1))
                .andExpect(jsonPath("$.cells[4]").value(0))
                .andExpect(jsonPath("$.cells[5]").value(1));
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mask_observation_version WHERE observation_id = 'mask-h'", Integer.class);
        assertThat(versionRows).isEqualTo(2);
    }

    @Test
    void clientCanResubmitFullGraphBasedOnNewCurrentVersion() throws Exception {
        create("req-r0", "mask-r", "root", cells("1100", "1100", "0000", "0000"))
                .andExpect(status().isCreated());
        merge("req-r1", "mask-r", 1, "server", cells("1100", "0100", "0000", "0000"))
                .andExpect(status().isOk());
        merge("req-r2", "mask-r", 1, "client", cells("1000", "1100", "0000", "0000"))
                .andExpect(status().isUnprocessableEntity());
        // 显式基于新当前版 2 重提完整连通图：成功，版本前进到 3
        merge("req-r3", "mask-r", 2, "client", cells("1110", "0100", "0000", "0000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.baseVersion").value(2))
                .andExpect(jsonPath("$.cells[2]").value(1));
    }

    @Test
    void mergeTombstoneAndMissingBaseFail() throws Exception {
        create("req-t0", "mask-t", "root", cells("1100", "1100", "0000", "0000"))
                .andExpect(status().isCreated());
        delete("req-t1", "mask-t", 1, "root").andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.cells").doesNotExist());
        // 墓碑不得合并
        merge("req-t2", "mask-t", 1, "alice", cells("1100", "1100", "0000", "0000"))
                .andExpect(status().isGone());
        delete("req-t3", "mask-t", 2, "alice").andExpect(status().isGone());
        // 基版不存在 404；资源不存在 404
        merge("req-t4", "mask-x", 1, "alice", cells("1100", "1100", "0000", "0000"))
                .andExpect(status().isNotFound());
        create("req-t5", "mask-u", "root", cells("1100", "1100", "0000", "0000"))
                .andExpect(status().isCreated());
        merge("req-t6", "mask-u", 9, "alice", cells("1100", "1100", "0000", "0000"))
                .andExpect(status().isNotFound());
    }

    @Test
    void replayKeepsFirstGraphAndDifferentParamsConflict() throws Exception {
        create("req-i0", "mask-i", "root", cells("1100", "1100", "0000", "0000"))
                .andExpect(status().isCreated());
        merge("req-i1", "mask-i", 1, "alice", cells("1110", "1100", "0000", "0000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        merge("req-i2", "mask-i", 2, "bob", cells("1111", "1100", "0000", "0000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3));
        // 当前已前进到版本 3；同参重放 req-i1 必须返回首次图（版本 2），而不是当前图
        merge("req-i1", "mask-i", 1, "alice", cells("1110", "1100", "0000", "0000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.cells[3]").value(0));
        // 同键异参 409
        merge("req-i1", "mask-i", 1, "alice", cells("1100", "1100", "0000", "0000"))
                .andExpect(status().isConflict());
        // 422 失败不占键：同 requestId 换合法参数成功
        merge("req-i3", "mask-i", 1, "carol", cells("1000", "0100", "0000", "0000"))
                .andExpect(status().isUnprocessableEntity());
        merge("req-i3", "mask-i", 3, "carol", cells("1111", "1100", "0000", "0000"))
                .andExpect(status().isOk());
    }
}
