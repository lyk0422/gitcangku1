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
 * 退赛登记 API 的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 覆盖登记/撤销/清单/选手状态查询与错误状态码。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class WithdrawalApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 退赛登记撤销与查询全链路() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-wd-api","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-wd-api/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkpoints":[{"checkpointCode":"cp1","position":1},
                                                {"checkpointCode":"cp2","position":2}],
                                 "expectedVersion":1,"requestId":"req-cps"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-wd-api/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":1000,"expectedVersion":2,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-wd-api/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b","expectedVersion":3,"requestId":"req-b"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-wd-api/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"t-a-cp1","checkpointCode":"cp1","elapsedMillis":100,
                                 "expectedVersion":4,"requestId":"req-t-a1"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-wd-api/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"t-a-cp2","checkpointCode":"cp2","elapsedMillis":200,
                                 "expectedVersion":5,"requestId":"req-t-a2"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-wd-api/runners/b/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"t-b-cp1","checkpointCode":"cp1","elapsedMillis":150,
                                 "expectedVersion":6,"requestId":"req-t-b1"}
                                """))
                .andExpect(status().isCreated());

        // DNF 指定非顺序最大检查点 -> 422
        mockMvc.perform(post("/api/races/race-wd-api/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b","status":"DNF","reason":"受伤","lastCheckpointCode":"cp2",
                                 "expectedVersion":7,"requestId":"req-wd-bad","withdrawalKey":"w-bad"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));

        // DNF 主流程 -> 201，版本加一
        mockMvc.perform(post("/api/races/race-wd-api/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b","status":"DNF","reason":"受伤","lastCheckpointCode":"cp1",
                                 "expectedVersion":7,"requestId":"req-wd-b","withdrawalKey":"w-b"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.withdrawalKey").value("w-b"))
                .andExpect(jsonPath("$.status").value("DNF"))
                .andExpect(jsonPath("$.lastCheckpointCode").value("cp1"))
                .andExpect(jsonPath("$.revoked").value(false));

        // 即时成绩排除退赛选手：b 不占名次
        mockMvc.perform(get("/api/races/race-wd-api/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(8))
                .andExpect(jsonPath("$.entries[0].bib").value("a"))
                .andExpect(jsonPath("$.entries[0].rank").value(1))
                .andExpect(jsonPath("$.entries[1].bib").value("b"))
                .andExpect(jsonPath("$.entries[1].status").value("DNF"))
                .andExpect(jsonPath("$.entries[1].rank").doesNotExist());

        // 退赛清单
        mockMvc.perform(get("/api/races/race-wd-api/withdrawals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.withdrawals.length()").value(1))
                .andExpect(jsonPath("$.withdrawals[0].withdrawalKey").value("w-b"));

        // 选手状态查询
        mockMvc.perform(get("/api/races/race-wd-api/runners/b/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DNF"))
                .andExpect(jsonPath("$.lastCheckpointCode").value("cp1"))
                .andExpect(jsonPath("$.activeWithdrawal.withdrawalKey").value("w-b"));

        // 退赛后禁止新增分段 -> 409
        mockMvc.perform(post("/api/races/race-wd-api/runners/b/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"t-b-cp2","checkpointCode":"cp2","elapsedMillis":300,
                                 "expectedVersion":8,"requestId":"req-t-b2"}
                                """))
                .andExpect(status().isConflict());

        // 撤销退赛 -> 200，选手恢复 UNTIMED
        mockMvc.perform(post("/api/races/race-wd-api/withdrawals/w-b/revocation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":8,"requestId":"req-revoke-b"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));
        mockMvc.perform(get("/api/races/race-wd-api/runners/b/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNTIMED"))
                .andExpect(jsonPath("$.activeWithdrawal").doesNotExist());

        // 重复撤销 -> 409
        mockMvc.perform(post("/api/races/race-wd-api/withdrawals/w-b/revocation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":9,"requestId":"req-revoke-b2"}
                                """))
                .andExpect(status().isConflict());

        // 空白原因 -> 400（Bean Validation）
        mockMvc.perform(post("/api/races/race-wd-api/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b","status":"DNS","reason":" ",
                                 "expectedVersion":9,"requestId":"req-wd-blank","withdrawalKey":"w-blank"}
                                """))
                .andExpect(status().isBadRequest());
    }
}
