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
 * 冲线证据 HTTP 接口端到端测试：真实 Spring MVC + Service + H2，
 * 校验登记、裁决、撤回、退赛、查询与封榜冻结的状态码和 JSON 字段。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class RaceEvidenceApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 证据登记裁决查询到封榜冻结全链路() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-ev-api","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-ev-api/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":1000,"expectedVersion":1,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-ev-api/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b","finishTimeMs":1000,"expectedVersion":2,"requestId":"req-b"}
                                """))
                .andExpect(status().isCreated());

        // 裁决前：并列第1
        mockMvc.perform(get("/api/races/race-ev-api/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].bib").value("a"))
                .andExpect(jsonPath("$.entries[0].rank").value(1))
                .andExpect(jsonPath("$.entries[1].bib").value("b"))
                .andExpect(jsonPath("$.entries[1].rank").value(1));

        // 登记证据
        mockMvc.perform(post("/api/races/race-ev-api/evidences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evidenceId":"ev-1","finishTimeMs":1000,
                                 "suggestedOrder":["a","b"],"operator":"judge-1",
                                 "capturedAt":1700000000000,"expectedVersion":3,"requestId":"req-ev1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.evidenceId").value("ev-1"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.rulingId").doesNotExist());

        // 建议顺序重复候选人 -> 422，错误原因可区分
        mockMvc.perform(post("/api/races/race-ev-api/evidences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evidenceId":"ev-bad","finishTimeMs":1000,
                                 "suggestedOrder":["a","a"],"operator":"judge-1",
                                 "capturedAt":1700000000000,"expectedVersion":4,"requestId":"req-evbad"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("重复候选人")));

        // 裁决：b 第一、a 第二
        mockMvc.perform(post("/api/races/race-ev-api/evidence-rulings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rulingId":"ruling-1","finishTimeMs":1000,
                                 "evidenceIds":["ev-1"],"orderedBibs":["b","a"],
                                 "operator":"judge-1","expectedVersion":4,"requestId":"req-ruling"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rulingId").value("ruling-1"))
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.orderedBibs[0]").value("b"));

        // 实时排名按证据顺序赋 1/2
        mockMvc.perform(get("/api/races/race-ev-api/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.entries[0].bib").value("b"))
                .andExpect(jsonPath("$.entries[0].rank").value(1))
                .andExpect(jsonPath("$.entries[1].bib").value("a"))
                .andExpect(jsonPath("$.entries[1].rank").value(2));

        // 证据列表与裁决快照查询
        mockMvc.perform(get("/api/races/race-ev-api/evidences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ADJUDICATED"))
                .andExpect(jsonPath("$[0].rulingId").value("ruling-1"));
        mockMvc.perform(get("/api/races/race-ev-api/evidence-rulings/ruling-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidenceIds[0]").value("ev-1"));

        // 已裁决证据不可撤回 -> 409
        mockMvc.perform(post("/api/races/race-ev-api/evidences/ev-1/revocation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"judge-2","expectedVersion":5,"requestId":"req-revoke"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("已裁决证据不可撤回")));

        // v6 封榜
        mockMvc.perform(post("/api/races/race-ev-api/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":5,"requestId":"req-seal"}
                                """))
                .andExpect(status().isOk());

        // 封榜后登记/裁决证据 -> 409
        mockMvc.perform(post("/api/races/race-ev-api/evidences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evidenceId":"ev-2","finishTimeMs":1000,
                                 "suggestedOrder":["a","b"],"operator":"judge-1",
                                 "capturedAt":1700000000000,"expectedVersion":6,"requestId":"req-ev2"}
                                """))
                .andExpect(status().isConflict());
        // 封榜固化证据名次
        mockMvc.perform(get("/api/races/race-ev-api/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.entries[0].bib").value("b"))
                .andExpect(jsonPath("$.entries[0].rank").value(1));
    }

    @Test
    void 退赛接口与撤回未裁决证据留痕() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-ev-wd","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-ev-wd/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":1000,"expectedVersion":1,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-ev-wd/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b","finishTimeMs":1000,"expectedVersion":2,"requestId":"req-b"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-ev-wd/evidences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evidenceId":"ev-1","finishTimeMs":1000,
                                 "suggestedOrder":["a","b"],"operator":"judge-1",
                                 "capturedAt":1700000000000,"expectedVersion":3,"requestId":"req-ev1"}
                                """))
                .andExpect(status().isCreated());

        // b 退赛：v5
        mockMvc.perform(post("/api/races/race-ev-wd/runners/b/withdrawal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"marshal-1","expectedVersion":4,"requestId":"req-wd"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entryStatus").value("WITHDRAWN"));

        // 引用已退赛候选人的证据裁决 -> 422
        mockMvc.perform(post("/api/races/race-ev-wd/evidence-rulings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rulingId":"ruling-x","finishTimeMs":1000,
                                 "evidenceIds":["ev-1"],"orderedBibs":["a","b"],
                                 "operator":"judge-1","expectedVersion":5,"requestId":"req-ruling-x"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("已退赛")));

        // 撤回未裁决证据 -> 200 且证据 REVOKED
        mockMvc.perform(post("/api/races/race-ev-wd/evidences/ev-1/revocation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"judge-1","expectedVersion":5,"requestId":"req-revoke"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokedAt").isNumber());
        mockMvc.perform(get("/api/races/race-ev-wd/evidences/ev-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        // 榜单中 b 为 WITHDRAWN
        mockMvc.perform(get("/api/races/race-ev-wd/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[1].bib").value("b"))
                .andExpect(jsonPath("$.entries[1].status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.entries[1].rank").doesNotExist());
    }
}
