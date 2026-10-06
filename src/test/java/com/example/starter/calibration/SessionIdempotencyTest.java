package com.example.starter.calibration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static com.example.starter.calibration.SessionTestSupport.append;
import static com.example.starter.calibration.SessionTestSupport.appendJson;
import static com.example.starter.calibration.SessionTestSupport.close;
import static com.example.starter.calibration.SessionTestSupport.createHysteresisCert;
import static com.example.starter.calibration.SessionTestSupport.createSession;
import static com.example.starter.calibration.SessionTestSupport.releaseSessions;
import static com.example.starter.calibration.SessionTestSupport.sessionJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 幂等去重测试：同键同参重放首次完整响应，异参 409，失败不占键；
 * 身份含操作、操作者、资源、全部参数与期望版本。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SessionIdempotencyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_session");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
        jdbc.update("DELETE FROM idempotency_record");
    }

    @Test
    void sameKeySameParamsReplaysFirstResponse() throws Exception {
        long certId = createHysteresisCert(mvc, "INS-H", "1", "1", "-1");
        String json = sessionJson("REQ-SAME", "S-1", "INS-H", certId, "0", "100", "alice");
        String first = mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        // 重放：完整响应与首次一致，且不重复创建
        String replay = mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertEquals(first, replay, "同键同参必须重放首次完整响应");
        Integer sessions = jdbc.queryForObject("SELECT COUNT(*) FROM calibration_session", Integer.class);
        assertEquals(1, sessions);
        Integer idem = jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class);
        assertEquals(1, idem);
    }

    @Test
    void sameKeyDifferentParamsReturns409() throws Exception {
        long certId = createHysteresisCert(mvc, "INS-H", "1", "1", "-1");
        createSession(mvc, "REQ-X", "S-1", "INS-H", certId, "0", "100", "alice")
                .andExpect(status().isCreated());
        // 同 requestId 但参数不同（上限不同）→ 409
        createSession(mvc, "REQ-X", "S-1", "INS-H", certId, "0", "200", "alice")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        // 同 requestId 但操作者不同 → 409
        createSession(mvc, "REQ-X", "S-1", "INS-H", certId, "0", "100", "bob")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        // 同 requestId 但操作不同（追加复用创建键）→ 409
        append(mvc, "REQ-X", "S-1", 0, "10", "UP", "alice")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void failedRequestDoesNotOccupyKey() throws Exception {
        long certId = createHysteresisCert(mvc, "INS-H", "1", "1", "-1");
        createSession(mvc, "REQ-C1", "S-1", "INS-H", certId, "0", "100", "alice")
                .andExpect(status().isCreated());
        // 版本冲突失败（409）不占键
        append(mvc, "REQ-A1", "S-1", 7, "10", "UP", "alice").andExpect(status().isConflict());
        // 参数非法失败（400）不占键
        append(mvc, "REQ-A2", "S-1", 0, "1.0000001", "UP", "alice").andExpect(status().isBadRequest());
        Integer idem = jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class);
        assertEquals(1, idem, "失败请求不得写入幂等记录");

        // 同一 requestId 修正参数后成功
        append(mvc, "REQ-A1", "S-1", 0, "10", "UP", "alice").andExpect(status().isCreated());
        append(mvc, "REQ-A2", "S-1", 1, "12", null, "alice").andExpect(status().isCreated());
    }

    @Test
    void appendReplayDoesNotDuplicateEntry() throws Exception {
        long certId = createHysteresisCert(mvc, "INS-H", "1", "1", "-1");
        createSession(mvc, "REQ-C1", "S-1", "INS-H", certId, "0", "100", "alice")
                .andExpect(status().isCreated());
        String json = appendJson("REQ-A1", 0, "10", "UP", "alice");
        String first = mvc.perform(post("/api/sessions/{key}/entries", "S-1")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(post("/api/sessions/{key}/entries", "S-1")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertEquals(first, replay);
        // 版本只推进一次，成员只有一条
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/sessions/{key}", "S-1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.entries.length()").value(1));
    }

    @Test
    void closeAndReleaseAreIdempotent() throws Exception {
        long certId = createHysteresisCert(mvc, "INS-H", "1", "1", "-1");
        createSession(mvc, "REQ-C1", "S-1", "INS-H", certId, "0", "100", "alice")
                .andExpect(status().isCreated());
        append(mvc, "REQ-A1", "S-1", 0, "10", "UP", "alice").andExpect(status().isCreated());

        close(mvc, "REQ-CL1", "S-1", 1, "alice").andExpect(status().isOk());
        // 同键同参重放关闭响应（不会触发重复关闭 409）
        close(mvc, "REQ-CL1", "S-1", 1, "alice").andExpect(status().isOk());

        String first = releaseSessions(mvc, "REQ-R1", "carol", "S-1")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String replay = releaseSessions(mvc, "REQ-R1", "carol", "S-1")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(first, replay);
        Integer releaseCount = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        assertEquals(1, releaseCount, "重放不得重复放行");
    }
}
