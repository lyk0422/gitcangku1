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
 * 申诉保证金 HTTP 端到端测试：真实 Spring MVC + Service + H2，
 * 覆盖配置/充值/申诉/裁决/流水/封榜快照，以及 409（暂缓封榜、终态改判、异参幂等）与 422（余额不足）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class ProtestApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 申诉保证金全链路_余额不足暂缓封榜到裁决退回与快照() throws Exception {
        // 建赛 v1
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-p","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.depositAmount").doesNotExist());

        // 配置保证金100 v2
        mockMvc.perform(post("/api/races/race-p/deposit-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"depositAmount":100,"expectedVersion":1,"requestId":"req-dep"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.depositAmount").value(100))
                .andExpect(jsonPath("$.version").value(2));

        // 登记选手 v3
        mockMvc.perform(post("/api/races/race-p/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":1500,"expectedVersion":2,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(0));

        // 充值80 v4
        mockMvc.perform(post("/api/races/race-p/runners/a/wallet-topups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionId":"top-80","amount":80,
                                 "expectedVersion":3,"requestId":"req-top-80"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(80));
        mockMvc.perform(get("/api/races/race-p/runners/a/wallet"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(80));

        // 余额不足：422，不建申诉
        mockMvc.perform(post("/api/races/race-p/runners/a/protests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"protestId":"protest-fail","reason":"异议",
                                 "expectedVersion":4,"requestId":"req-pf"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));
        mockMvc.perform(get("/api/races/race-p/protests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protests").isArray())
                .andExpect(jsonPath("$.protests.length()").value(0));

        // 再充20 v5
        mockMvc.perform(post("/api/races/race-p/runners/a/wallet-topups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionId":"top-20","amount":20,
                                 "expectedVersion":4,"requestId":"req-top-20"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(100));

        // 创建申诉冻结100 v6
        mockMvc.perform(post("/api/races/race-p/runners/a/protests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"protestId":"protest-1","reason":"对加时不服",
                                 "expectedVersion":5,"requestId":"req-p1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.depositAmount").value(100))
                .andExpect(jsonPath("$.decidedAt").doesNotExist());
        mockMvc.perform(get("/api/races/race-p/runners/a/wallet"))
                .andExpect(jsonPath("$.balance").value(0));

        // 未裁决申诉：封榜 409
        mockMvc.perform(post("/api/races/race-p/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":6,"requestId":"req-seal-blocked"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));

        // ACCEPT 全额退回 v7
        mockMvc.perform(post("/api/races/race-p/protests/protest-1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"ACCEPT","reason":"成立",
                                 "expectedVersion":6,"requestId":"req-d1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.depositAmount").value(100))
                .andExpect(jsonPath("$.decisionReason").value("成立"));
        mockMvc.perform(get("/api/races/race-p/runners/a/wallet"))
                .andExpect(jsonPath("$.balance").value(100));

        // 终态改判 REJECT：409，不再没收/退款
        mockMvc.perform(post("/api/races/race-p/protests/protest-1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"REJECT","reason":"改判",
                                 "expectedVersion":7,"requestId":"req-d2"}
                                """))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/races/race-p/runners/a/wallet"))
                .andExpect(jsonPath("$.balance").value(100));

        // 同 requestId 异参（改裁决）：409
        mockMvc.perform(post("/api/races/race-p/protests/protest-1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"REJECT","reason":"x",
                                 "expectedVersion":7,"requestId":"req-d1"}
                                """))
                .andExpect(status().isConflict());

        // 流水：充值2笔 + 冻结 + 退回（按记账顺序），固定金额
        mockMvc.perform(get("/api/races/race-p/runners/a/wallet-transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].type").value("TOPUP"))
                .andExpect(jsonPath("$[0].amount").value(80))
                .andExpect(jsonPath("$[0].balanceAfter").value(80))
                .andExpect(jsonPath("$[1].type").value("TOPUP"))
                .andExpect(jsonPath("$[1].amount").value(20))
                .andExpect(jsonPath("$[2].type").value("FREEZE"))
                .andExpect(jsonPath("$[2].amount").value(100))
                .andExpect(jsonPath("$[2].balanceAfter").value(0))
                .andExpect(jsonPath("$[2].protestId").value("protest-1"))
                .andExpect(jsonPath("$[3].type").value("REFUND"))
                .andExpect(jsonPath("$[3].amount").value(100))
                .andExpect(jsonPath("$[3].balanceAfter").value(100));

        // 裁决后封榜 v8：成绩快照含当时申诉裁决依据
        mockMvc.perform(post("/api/races/race-p/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":7,"requestId":"req-seal"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.protests.length()").value(1))
                .andExpect(jsonPath("$.protests[0].protestId").value("protest-1"))
                .andExpect(jsonPath("$.protests[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.protests[0].depositAmount").value(100))
                .andExpect(jsonPath("$.protests[0].reason").value("对加时不服"))
                .andExpect(jsonPath("$.protests[0].decisionReason").value("成立"));

        mockMvc.perform(get("/api/races/race-p/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protests[0].status").value("ACCEPTED"));
    }

    @Test
    void 申诉不存在与非法裁决返回404和400() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-q","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/races/race-q/protests/missing"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/races/race-q/deposit-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"depositAmount":0,"expectedVersion":1,"requestId":"req-bad"}
                                """))
                .andExpect(status().isBadRequest());
    }
}
