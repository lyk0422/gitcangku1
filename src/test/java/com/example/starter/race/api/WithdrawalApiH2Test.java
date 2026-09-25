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
 * 退赛能力的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 覆盖 DNS/DNF 登记、422/409/400 失败分支、幂等重放、名次重排、撤销、
 * 退赛清单、选手状态查询与封榜固化。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class WithdrawalApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void DNS登记幂等重放与撤销全链路() throws Exception {
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

        // b 登记 DNS（v4）
        mockMvc.perform(post("/api/races/race-wd-api/runners/b/withdrawal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"withdrawalKey":"key-b","status":"DNS","reason":"illness",
                                 "expectedVersion":3,"requestId":"req-wd-b"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DNS"))
                .andExpect(jsonPath("$.revoked").value(false))
                .andExpect(jsonPath("$.lastCheckpointCode").doesNotExist());

        // 同 requestId 重放：原样返回首次结果
        mockMvc.perform(post("/api/races/race-wd-api/runners/b/withdrawal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"withdrawalKey":"key-b","status":"DNS","reason":"illness",
                                 "expectedVersion":3,"requestId":"req-wd-b"}
                                """))
                .andExpect(status().isCreated());

        // 同键异参 409
        mockMvc.perform(post("/api/races/race-wd-api/runners/b/withdrawal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"withdrawalKey":"key-b","status":"DNS","reason":"other",
                                 "expectedVersion":4,"requestId":"req-wd-other"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));

        // 相互改写 DNF 409
        mockMvc.perform(post("/api/races/race-wd-api/runners/b/withdrawal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"withdrawalKey":"key-b2","status":"DNF","reason":"fall",
                                 "lastCheckpointCode":"c1","expectedVersion":4,"requestId":"req-dnf-b"}
                                """))
                .andExpect(status().isConflict());

        // 即时成绩：a 第1，b 为 DNS 不占名次，单独列后
        mockMvc.perform(get("/api/races/race-wd-api/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].bib").value("a"))
                .andExpect(jsonPath("$.entries[0].rank").value(1))
                .andExpect(jsonPath("$.entries[1].bib").value("b"))
                .andExpect(jsonPath("$.entries[1].status").value("DNS"))
                .andExpect(jsonPath("$.entries[1].rank").doesNotExist());

        // 选手状态查询
        mockMvc.perform(get("/api/races/race-wd-api/runners/b/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DNS"))
                .andExpect(jsonPath("$.withdrawal.withdrawalKey").value("key-b"));

        // 退赛清单
        mockMvc.perform(get("/api/races/race-wd-api/withdrawals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.withdrawals.length()").value(1))
                .andExpect(jsonPath("$.withdrawals[0].bib").value("b"));

        // 撤销（v4 -> v5）
        mockMvc.perform(post("/api/races/race-wd-api/runners/b/withdrawal/revocation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"withdrawalKey":"key-b","expectedVersion":4,"requestId":"req-revoke"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));

        // 已撤销不能再次撤销
        mockMvc.perform(post("/api/races/race-wd-api/runners/b/withdrawal/revocation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"withdrawalKey":"key-b","expectedVersion":5,"requestId":"req-revoke2"}
                                """))
                .andExpect(status().isConflict());

        // 撤销后回到 UNTIMED，清单保留历史
        mockMvc.perform(get("/api/races/race-wd-api/runners/b/status"))
                .andExpect(jsonPath("$.status").value("UNTIMED"))
                .andExpect(jsonPath("$.withdrawal").doesNotExist());
        mockMvc.perform(get("/api/races/race-wd-api/withdrawals"))
                .andExpect(jsonPath("$.withdrawals.length()").value(1))
                .andExpect(jsonPath("$.withdrawals[0].revoked").value(true));

        // 重新 DNS 后封榜，快照固化
        mockMvc.perform(post("/api/races/race-wd-api/runners/b/withdrawal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"withdrawalKey":"key-b3","status":"DNS","reason":"illness",
                                 "expectedVersion":5,"requestId":"req-wd-b3"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-wd-api/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":6,"requestId":"req-seal"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[1].status").value("DNS"));

        // 封榜后禁止撤销
        mockMvc.perform(post("/api/races/race-wd-api/runners/b/withdrawal/revocation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"withdrawalKey":"key-b3","expectedVersion":7,"requestId":"req-rev-sealed"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void DNF最后检查点非顺序最大者返回422且缺少检查点返回400() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-dnf-api","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-dnf-api/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","expectedVersion":1,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-dnf-api/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkpoints":[{"checkpointCode":"c1","position":1},
                                                {"checkpointCode":"c2","position":2}],
                                 "expectedVersion":2,"requestId":"req-cfg"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-dnf-api/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"t1","checkpointCode":"c1","elapsedMillis":100,
                                 "expectedVersion":3,"requestId":"req-t1"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-dnf-api/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"t2","checkpointCode":"c2","elapsedMillis":200,
                                 "expectedVersion":4,"requestId":"req-t2"}
                                """))
                .andExpect(status().isCreated());

        // 指定非最大顺序检查点 c1 -> 422
        mockMvc.perform(post("/api/races/race-dnf-api/runners/a/withdrawal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"withdrawalKey":"key-bad","status":"DNF","reason":"injury",
                                 "lastCheckpointCode":"c1","expectedVersion":5,"requestId":"req-wd-bad"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));

        // 缺少最后检查点 -> 400
        mockMvc.perform(post("/api/races/race-dnf-api/runners/a/withdrawal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"withdrawalKey":"key-nocp","status":"DNF","reason":"injury",
                                 "expectedVersion":5,"requestId":"req-wd-nocp"}
                                """))
                .andExpect(status().isBadRequest());

        // 合法 DNF（顺序最大者 c2）-> 201
        mockMvc.perform(post("/api/races/race-dnf-api/runners/a/withdrawal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"withdrawalKey":"key-ok","status":"DNF","reason":"injury",
                                 "lastCheckpointCode":"c2","expectedVersion":5,"requestId":"req-wd-ok"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lastCheckpointCode").value("c2"))
                .andExpect(jsonPath("$.lastCheckpointPosition").value(2));

        // 退赛后处罚加时 -> 409
        mockMvc.perform(post("/api/races/race-dnf-api/penalties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"penaltyId":"p1","bib":"a","type":"ADD_TIME","amountMs":10,
                                 "expectedVersion":6,"requestId":"req-pen"}
                                """))
                .andExpect(status().isConflict());
    }
}
