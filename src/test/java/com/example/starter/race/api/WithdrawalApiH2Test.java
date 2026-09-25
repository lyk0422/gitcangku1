package com.example.starter.race.api;

import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 退赛 API 的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 校验退赛登记/撤销/清单/选手状态查询的状态码与 JSON 字段。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class WithdrawalApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 退赛登记清单状态查询与撤销全链路() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-wd-api","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/races/race-wd-api/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":1000,"expectedVersion":1,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-wd-api/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b","expectedVersion":2,"requestId":"req-b"}
                                """))
                .andExpect(status().isCreated());

        // DNS 登记：201，版本加一
        mockMvc.perform(post("/api/races/race-wd-api/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"withdrawalKey":"w-b","bib":"b","status":"DNS","reason":"未到场",
                                 "expectedVersion":3,"requestId":"req-w-b"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.withdrawalKey").value("w-b"))
                .andExpect(jsonPath("$.status").value("DNS"))
                .andExpect(jsonPath("$.reason").value("未到场"))
                .andExpect(jsonPath("$.revoked").value(false))
                .andExpect(jsonPath("$.createdAt").exists());

        // 即时成绩排除退赛选手：b 不占名次
        mockMvc.perform(get("/api/races/race-wd-api/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.entries[0].bib").value("a"))
                .andExpect(jsonPath("$.entries[0].rank").value(1))
                .andExpect(jsonPath("$.entries[1].bib").value("b"))
                .andExpect(jsonPath("$.entries[1].status").value("DNS"))
                .andExpect(jsonPath("$.entries[1].rank").doesNotExist());

        // 退赛清单
        mockMvc.perform(get("/api/races/race-wd-api/withdrawals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.withdrawals[0].withdrawalKey").value("w-b"))
                .andExpect(jsonPath("$.withdrawals[0].status").value("DNS"));

        // 选手状态查询
        mockMvc.perform(get("/api/races/race-wd-api/runners/b/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DNS"))
                .andExpect(jsonPath("$.withdrawal.withdrawalKey").value("w-b"));
        mockMvc.perform(get("/api/races/race-wd-api/runners/a/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RANKED"))
                .andExpect(jsonPath("$.withdrawal").doesNotExist());

        // 撤销退赛：200，版本加一，选手恢复 UNTIMED
        mockMvc.perform(post("/api/races/race-wd-api/withdrawals/w-b/revocation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":4,"requestId":"req-rv-b"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true))
                .andExpect(jsonPath("$.revokedAt").exists());
        mockMvc.perform(get("/api/races/race-wd-api/runners/b/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNTIMED"))
                .andExpect(jsonPath("$.withdrawal.revoked").value(true));

        // 已撤销的退赛不能再次撤销：409
        mockMvc.perform(post("/api/races/race-wd-api/withdrawals/w-b/revocation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":5,"requestId":"req-rv-again"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void 退赛错误分支状态码() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-wd-err","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-wd-err/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":10000,"expectedVersion":1,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated());

        // 已有完赛计时登记退赛：409
        mockMvc.perform(post("/api/races/race-wd-err/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"withdrawalKey":"w-1","bib":"a","status":"DNS","reason":"未到场",
                                 "expectedVersion":2,"requestId":"req-w-1"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));

        // 选手不存在：404
        mockMvc.perform(post("/api/races/race-wd-err/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"withdrawalKey":"w-2","bib":"ghost","status":"DNS","reason":"未到场",
                                 "expectedVersion":2,"requestId":"req-w-2"}
                                """))
                .andExpect(status().isNotFound());

        // 退赛原因为空：400（Bean Validation）
        mockMvc.perform(post("/api/races/race-wd-err/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"withdrawalKey":"w-3","bib":"a","status":"DNS","reason":"",
                                 "expectedVersion":2,"requestId":"req-w-3"}
                                """))
                .andExpect(status().isBadRequest());

        // 未知退赛状态：400
        mockMvc.perform(post("/api/races/race-wd-err/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"withdrawalKey":"w-4","bib":"a","status":"QUIT","reason":"x",
                                 "expectedVersion":2,"requestId":"req-w-4"}
                                """))
                .andExpect(status().isBadRequest());

        // 撤销不存在的退赛：404
        mockMvc.perform(post("/api/races/race-wd-err/withdrawals/w-ghost/revocation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":2,"requestId":"req-rv-ghost"}
                                """))
                .andExpect(status().isNotFound());

        // 查询不存在赛事的退赛清单：404
        mockMvc.perform(get("/api/races/missing/withdrawals"))
                .andExpect(status().isNotFound());
    }

    @Test
    void DNF检查点校验与封榜后禁止退赛() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-wd-dnf","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-wd-dnf/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":10000,"expectedVersion":1,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-wd-dnf/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkpoints":[{"checkpointCode":"cp1","position":1},
                                                {"checkpointCode":"cp2","position":2}],
                                 "expectedVersion":2,"requestId":"req-cfg"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-wd-dnf/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"t-1","checkpointCode":"cp1","elapsedMillis":100,
                                 "expectedVersion":3,"requestId":"req-t1"}
                                """))
                .andExpect(status().isCreated());
        // 清除完赛计时，使 DNF 前置成立
        mockMvc.perform(post("/api/races/race-wd-dnf/timing-revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":null,"expectedVersion":4,"requestId":"req-clear"}
                                """))
                .andExpect(status().isOk());

        // lastPassedCheckpoint 非顺序最大记录之外的检查点：422
        mockMvc.perform(post("/api/races/race-wd-dnf/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"withdrawalKey":"w-1","bib":"a","status":"DNF","reason":"受伤",
                                 "lastPassedCheckpoint":"cp2","expectedVersion":5,"requestId":"req-w-1"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));

        // 合法 DNF：201
        mockMvc.perform(post("/api/races/race-wd-dnf/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"withdrawalKey":"w-a","bib":"a","status":"DNF","reason":"受伤",
                                 "lastPassedCheckpoint":"cp1","expectedVersion":5,"requestId":"req-w-a"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DNF"))
                .andExpect(jsonPath("$.lastCheckpointCode").value("cp1"));

        // 封榜后禁止新增退赛：409
        mockMvc.perform(post("/api/races/race-wd-dnf/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":6,"requestId":"req-seal"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].status").value("DNF"))
                .andExpect(jsonPath("$.entries[0].lastCheckpointCode").value("cp1"));
        mockMvc.perform(post("/api/races/race-wd-dnf/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"withdrawalKey":"w-2","bib":"a","status":"DNS","reason":"x",
                                 "expectedVersion":7,"requestId":"req-w-2"}
                                """))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/races/race-wd-dnf/withdrawals/w-a/revocation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":7,"requestId":"req-rv-a"}
                                """))
                .andExpect(status().isConflict());
    }
}
