package com.example.starter.race;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 赛事成绩封榜 API 主流程与失败分支测试（真实 H2 数据库，MockMvc 走完整 HTTP 链路）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RaceApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM request_log");
        jdbc.update("DELETE FROM race_snapshot");
        jdbc.update("DELETE FROM penalty");
        jdbc.update("DELETE FROM participant");
        jdbc.update("DELETE FROM race");
    }

    // ---------- HTTP 辅助 ----------

    ResultActions createRace(String requestId, String raceId) throws Exception {
        return mvc.perform(post("/api/races").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + requestId + "\",\"raceId\":\"" + raceId + "\"}"));
    }

    ResultActions register(String raceId, String requestId, String bib, long expectedVersion,
                           String rawTimeMsJson) throws Exception {
        return mvc.perform(post("/api/races/{raceId}/participants", raceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + requestId + "\",\"bib\":\"" + bib
                        + "\",\"expectedVersion\":" + expectedVersion
                        + (rawTimeMsJson == null ? "" : ",\"rawTimeMs\":" + rawTimeMsJson) + "}"));
    }

    ResultActions reviseTime(String raceId, String bib, String requestId, long rawTimeMs,
                             long expectedVersion) throws Exception {
        return mvc.perform(put("/api/races/{raceId}/participants/{bib}/time", raceId, bib)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + requestId + "\",\"rawTimeMs\":" + rawTimeMs
                        + ",\"expectedVersion\":" + expectedVersion + "}"));
    }

    ResultActions addPenalty(String raceId, String requestId, String penaltyId, String bib,
                             String type, String amountMsJson, long expectedVersion)
            throws Exception {
        return mvc.perform(post("/api/races/{raceId}/penalties", raceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + requestId + "\",\"penaltyId\":\"" + penaltyId
                        + "\",\"bib\":\"" + bib + "\",\"type\":\"" + type + "\""
                        + (amountMsJson == null ? "" : ",\"amountMs\":" + amountMsJson)
                        + ",\"expectedVersion\":" + expectedVersion + "}"));
    }

    ResultActions revoke(String raceId, String penaltyId, String requestId, long expectedVersion)
            throws Exception {
        return mvc.perform(post("/api/races/{raceId}/penalties/{penaltyId}/revoke", raceId,
                penaltyId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + requestId + "\",\"expectedVersion\":"
                        + expectedVersion + "}"));
    }

    ResultActions seal(String raceId, String requestId, long expectedVersion) throws Exception {
        return mvc.perform(post("/api/races/{raceId}/seal", raceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + requestId + "\",\"expectedVersion\":"
                        + expectedVersion + "}"));
    }

    ResultActions standings(String raceId) throws Exception {
        return mvc.perform(get("/api/races/{raceId}/standings", raceId));
    }

    ResultActions snapshot(String raceId) throws Exception {
        return mvc.perform(get("/api/races/{raceId}/snapshot", raceId));
    }

    // ---------- 主流程 ----------

    @Test
    void fullFlow_rankingPenaltiesSealAndSnapshot() throws Exception {
        createRace("req-c1", "R1").andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("OPEN"));

        register("R1", "req-r1", "A", 1, "50").andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        register("R1", "req-r2", "B", 2, "50").andExpect(jsonPath("$.version").value(3));
        register("R1", "req-r3", "C", 3, "100").andExpect(jsonPath("$.version").value(4));
        register("R1", "req-r4", "D", 4, null).andExpect(jsonPath("$.version").value(5));

        // 并列同名次，下一名次跳过并列人数：1、1、3；并列按参赛号字典序
        standings("R1").andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.standings", hasSize(4)))
                .andExpect(jsonPath("$.standings[0].bib").value("A"))
                .andExpect(jsonPath("$.standings[0].rank").value(1))
                .andExpect(jsonPath("$.standings[0].totalTimeMs").value(50))
                .andExpect(jsonPath("$.standings[1].bib").value("B"))
                .andExpect(jsonPath("$.standings[1].rank").value(1))
                .andExpect(jsonPath("$.standings[2].bib").value("C"))
                .andExpect(jsonPath("$.standings[2].rank").value(3))
                .andExpect(jsonPath("$.standings[3].bib").value("D"))
                .andExpect(jsonPath("$.standings[3].status").value("UNTIMED"))
                .andExpect(jsonPath("$.standings[3].rank").value(nullValue()));

        // 加时处罚计入总耗时
        addPenalty("R1", "req-p1", "P1", "A", "TIME_ADD", "30", 5)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(6))
                .andExpect(jsonPath("$.revoked").value(false));
        standings("R1")
                .andExpect(jsonPath("$.standings[0].bib").value("B"))
                .andExpect(jsonPath("$.standings[0].rank").value(1))
                .andExpect(jsonPath("$.standings[1].bib").value("A"))
                .andExpect(jsonPath("$.standings[1].totalTimeMs").value(80))
                .andExpect(jsonPath("$.standings[1].rank").value(2))
                .andExpect(jsonPath("$.standings[2].bib").value("C"))
                .andExpect(jsonPath("$.standings[2].rank").value(3));

        // 取消资格不排名，撤销后恢复
        addPenalty("R1", "req-p2", "P2", "C", "DISQUALIFY", null, 6)
                .andExpect(jsonPath("$.version").value(7));
        standings("R1")
                .andExpect(jsonPath("$.standings[2].bib").value("D"))
                .andExpect(jsonPath("$.standings[3].bib").value("C"))
                .andExpect(jsonPath("$.standings[3].status").value("DISQUALIFIED"))
                .andExpect(jsonPath("$.standings[3].rank").value(nullValue()));
        revoke("R1", "P2", "req-p3", 7).andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true))
                .andExpect(jsonPath("$.version").value(8));
        standings("R1")
                .andExpect(jsonPath("$.standings[2].bib").value("C"))
                .andExpect(jsonPath("$.standings[2].rank").value(3));

        // 计时修订
        reviseTime("R1", "C", "req-t1", 40, 8).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(9));
        standings("R1")
                .andExpect(jsonPath("$.standings[0].bib").value("C"))
                .andExpect(jsonPath("$.standings[0].totalTimeMs").value(40))
                .andExpect(jsonPath("$.standings[1].bib").value("B"))
                .andExpect(jsonPath("$.standings[2].bib").value("A"));

        // 未封榜查询快照返回409
        snapshot("R1").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RACE_NOT_SEALED"));

        // 封榜：原子保存快照并转 SEALED
        seal("R1", "req-s1", 9).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.version").value(9))
                .andExpect(jsonPath("$.snapshot", hasSize(4)))
                .andExpect(jsonPath("$.snapshot[0].bib").value("C"))
                .andExpect(jsonPath("$.snapshot[0].rank").value(1))
                .andExpect(jsonPath("$.snapshot[3].bib").value("D"))
                .andExpect(jsonPath("$.snapshot[3].status").value("UNTIMED"));

        // 快照查询与封榜结果一致
        snapshot("R1").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.snapshot[0].bib").value("C"))
                .andExpect(jsonPath("$.snapshot[1].bib").value("B"))
                .andExpect(jsonPath("$.snapshot[2].bib").value("A"))
                .andExpect(jsonPath("$.snapshot[2].totalTimeMs").value(80));

        // 封榜后所有写操作返回409
        register("R1", "req-w1", "E", 9, "10").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RACE_SEALED"));
        reviseTime("R1", "A", "req-w2", 60, 9).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RACE_SEALED"));
        addPenalty("R1", "req-w3", "P9", "A", "TIME_ADD", "10", 9)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RACE_SEALED"));
        revoke("R1", "P1", "req-w4", 9).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RACE_SEALED"));
        seal("R1", "req-w5", 9).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RACE_SEALED"));
    }

    // ---------- 版本冲突 ----------

    @Test
    void versionConflict_returns409() throws Exception {
        createRace("req-c2", "R2").andExpect(status().isOk());
        register("R2", "req-v1", "A", 5, "100").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        register("R2", "req-v2", "A", 1, "100").andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        register("R2", "req-v3", "B", 1, "100").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        reviseTime("R2", "A", "req-v4", 200, 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        seal("R2", "req-v5", 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    // ---------- 幂等 ----------

    @Test
    void idempotent_sameKeySameParams_replaysOriginalResponse() throws Exception {
        createRace("req-c3", "R3").andExpect(status().isOk());
        MvcResult first = register("R3", "req-i1", "A", 1, "100")
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = register("R3", "req-i1", "A", 1, "100")
                .andExpect(status().isOk()).andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(
                first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 重放不产生第二次业务变更：版本仍为2，选手仅一名
        standings("R3")
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.standings", hasSize(1)));
    }

    @Test
    void idempotent_sameKeyDifferentParams_returns409() throws Exception {
        createRace("req-c4", "R4").andExpect(status().isOk());
        register("R4", "req-i2", "A", 1, "100").andExpect(status().isOk());
        register("R4", "req-i2", "B", 1, "100").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        register("R4", "req-i2", "A", 1, "200").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void idempotent_failedRequestDoesNotConsumeKey() throws Exception {
        createRace("req-c5", "R5").andExpect(status().isOk());
        // 参数校验失败不占键
        register("R5", "req-i3", "A", 1, "0").andExpect(status().isBadRequest());
        register("R5", "req-i3", "A", 1, "100").andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // 业务失败（版本冲突）不占键
        register("R5", "req-i4", "B", 99, "100").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        register("R5", "req-i4", "B", 2, "100").andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
    }

    // ---------- 参数校验 ----------

    @Test
    void validation_outOfRangeAndMalformedPayloads_return400() throws Exception {
        createRace("req-c6", "R6").andExpect(status().isOk());
        register("R6", "req-b1", "A", 1, "86400001").andExpect(status().isBadRequest());
        register("R6", "req-b2", "A", 1, "-5").andExpect(status().isBadRequest());
        register("R6", "req-b3", "A", 1, "86400000").andExpect(status().isOk());

        addPenalty("R6", "req-b4", "P1", "A", "TIME_ADD", "3600001", 2)
                .andExpect(status().isBadRequest());
        addPenalty("R6", "req-b5", "P1", "A", "TIME_ADD", "0", 2)
                .andExpect(status().isBadRequest());
        addPenalty("R6", "req-b6", "P1", "A", "TIME_ADD", null, 2)
                .andExpect(status().isBadRequest());
        addPenalty("R6", "req-b7", "P1", "A", "DISQUALIFY", "10", 2)
                .andExpect(status().isBadRequest());
        addPenalty("R6", "req-b8", "P1", "A", "TIME_ADD", "3600000", 2)
                .andExpect(status().isOk());

        // 缺少 requestId
        mvc.perform(post("/api/races/{raceId}/participants", "R6")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bib\":\"X\",\"expectedVersion\":3}"))
                .andExpect(status().isBadRequest());
        // 非法 JSON
        mvc.perform(post("/api/races/{raceId}/participants", "R6")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest());
    }

    // ---------- 不存在资源 ----------

    @Test
    void notFound_returns404() throws Exception {
        register("NOPE", "req-n1", "A", 1, "100").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RACE_NOT_FOUND"));
        standings("NOPE").andExpect(status().isNotFound());
        snapshot("NOPE").andExpect(status().isNotFound());

        createRace("req-c7", "R7").andExpect(status().isOk());
        reviseTime("R7", "GHOST", "req-n2", 100, 1).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PARTICIPANT_NOT_FOUND"));
        addPenalty("R7", "req-n3", "P1", "GHOST", "TIME_ADD", "10", 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PARTICIPANT_NOT_FOUND"));
        revoke("R7", "GHOST", "req-n4", 1).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PENALTY_NOT_FOUND"));
    }

    // ---------- 唯一性与状态约束 ----------

    @Test
    void duplicatesAndDoubleRevoke_return409() throws Exception {
        createRace("req-c8", "R8").andExpect(status().isOk());
        createRace("req-c9", "R8").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RACE_EXISTS"));

        register("R8", "req-d1", "A", 1, "100").andExpect(status().isOk());
        register("R8", "req-d2", "A", 2, "200").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARTICIPANT_EXISTS"));

        addPenalty("R8", "req-d3", "P1", "A", "TIME_ADD", "10", 2)
                .andExpect(status().isOk());
        addPenalty("R8", "req-d4", "P1", "A", "TIME_ADD", "20", 3)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PENALTY_EXISTS"));

        revoke("R8", "P1", "req-d5", 3).andExpect(status().isOk());
        revoke("R8", "P1", "req-d6", 4).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PENALTY_ALREADY_REVOKED"));
    }
}
