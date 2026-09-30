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
 * 申诉保证金 HTTP 端到端测试（真实 Spring MVC + Service + H2）：
 * 配置100元、余额80元申诉422不锁成绩、足额冻结、ACCEPT只退一次、
 * 终态重复裁决409、封榜快照含申诉裁决依据与流水展示。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class ProtestApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 保证金申诉裁决封榜全链路_含余额不足与终态409() throws Exception {
        // 建赛，响应携带保证金配置0。
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-p","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.depositFen").value(0));

        // 配置保证金100元=10000分，v1->v2。
        mockMvc.perform(post("/api/races/race-p/deposit-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"depositFen":10000,"expectedVersion":1,"requestId":"req-cfg"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.depositFen").value(10000))
                .andExpect(jsonPath("$.version").value(2));

        // 开立两个账户：富人200元、穷人80元。
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"rich","holderName":"富人","requestId":"req-acc-rich"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balanceFen").value(0));
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"poor","requestId":"req-acc-poor"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/accounts/rich/recharges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountFen":20000,"requestId":"req-recharge-rich"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceFen").value(20000));
        mockMvc.perform(post("/api/accounts/poor/recharges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountFen":8000,"requestId":"req-recharge-poor"}
                                """))
                .andExpect(status().isOk());

        // 穷人80元 < 100元：422，不创建申诉、不冻结。
        mockMvc.perform(post("/api/races/race-p/protests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"protestId":"p-poor","accountId":"poor","reason":"不够",
                                 "expectedVersion":2,"requestId":"req-p-poor"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));
        mockMvc.perform(get("/api/accounts/poor/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("RECHARGE"));

        // 富人足额创建申诉：冻结10000分，PENDING，v2->v3。
        mockMvc.perform(post("/api/races/race-p/protests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"protestId":"p-1","accountId":"rich","reason":"计时争议",
                                 "expectedVersion":2,"requestId":"req-p-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.depositFen").value(10000))
                .andExpect(jsonPath("$.decision").doesNotExist());
        mockMvc.perform(get("/api/accounts/rich"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceFen").value(10000));
        mockMvc.perform(get("/api/accounts/rich/ledger"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].type").value("FREEZE"))
                .andExpect(jsonPath("$[1].amountFen").value(10000))
                .andExpect(jsonPath("$[1].balanceAfterFen").value(10000))
                .andExpect(jsonPath("$[1].protestId").value("p-1"));

        // 有未裁决申诉：封榜409。
        mockMvc.perform(post("/api/races/race-p/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":3,"requestId":"req-seal-blocked"}
                                """))
                .andExpect(status().isConflict());

        // ACCEPT 裁决：全额退回，v3->v4。
        mockMvc.perform(post("/api/protests/p-1/adjudication")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"ACCEPT","expectedVersion":3,"requestId":"req-decide"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.decision").value("ACCEPT"))
                .andExpect(jsonPath("$.depositFen").value(10000));
        mockMvc.perform(get("/api/accounts/rich"))
                .andExpect(jsonPath("$.balanceFen").value(20000));
        mockMvc.perform(get("/api/accounts/rich/ledger"))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].type").value("REFUND"))
                .andExpect(jsonPath("$[2].amountFen").value(10000));

        // 终态重复裁决/改判：409，且无新增流水。
        mockMvc.perform(post("/api/protests/p-1/adjudication")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"REJECT","expectedVersion":4,"requestId":"req-decide-other"}
                                """))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/accounts/rich/ledger"))
                .andExpect(jsonPath("$.length()").value(3));

        // 同裁决 requestId 重放首次结果（同参200，不重复退款）。
        mockMvc.perform(post("/api/protests/p-1/adjudication")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"ACCEPT","expectedVersion":3,"requestId":"req-decide"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        mockMvc.perform(get("/api/accounts/rich/ledger"))
                .andExpect(jsonPath("$.length()").value(3));

        // 封榜 v4->v5，快照保留当时申诉与裁决依据。
        mockMvc.perform(post("/api/races/race-p/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":4,"requestId":"req-seal-ok"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.protests.length()").value(1))
                .andExpect(jsonPath("$.protests[0].protestId").value("p-1"))
                .andExpect(jsonPath("$.protests[0].reason").value("计时争议"))
                .andExpect(jsonPath("$.protests[0].depositFen").value(10000))
                .andExpect(jsonPath("$.protests[0].decision").value("ACCEPT"));

        // 申诉查询与赛事申诉列表。
        mockMvc.perform(get("/api/protests/p-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        mockMvc.perform(get("/api/races/race-p/protests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // 封榜后再创建申诉：409。
        mockMvc.perform(post("/api/races/race-p/protests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"protestId":"p-after","accountId":"rich","reason":"封了",
                                 "expectedVersion":5,"requestId":"req-p-after"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void 非法裁决值400_账户与申诉缺失404() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r","requestId":"c"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r/deposit-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"depositFen":10000,"expectedVersion":1,"requestId":"cfg"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"a","requestId":"ca"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/accounts/a/recharges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountFen":20000,"requestId":"ra"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/races/r/protests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"protestId":"p","accountId":"a","reason":"x",
                                 "expectedVersion":2,"requestId":"cp"}
                                """))
                .andExpect(status().isCreated());

        // 未知裁决值：400。
        mockMvc.perform(post("/api/protests/p/adjudication")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"MAYBE","expectedVersion":3,"requestId":"d1"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/protests/nope"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/accounts/nope"))
                .andExpect(status().isNotFound());
    }
}
