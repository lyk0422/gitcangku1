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
 * 冲线证据 API 的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 校验登记/裁决/撤回/查询的状态码（201/200/409/422）与封榜冻结语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class FinishEvidenceApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 登记裁决查询到封榜冻结全链路() throws Exception {
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

        // 建议顺序遗漏候选人 → 422
        mockMvc.perform(post("/api/races/race-ev-api/finish-evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evidenceId":"ev-bad","finishTimeMs":1000,"suggestedOrder":["a"],
                                 "capturedAt":1759000000000,"operator":"photo-judge",
                                 "expectedVersion":3,"requestId":"req-ev-bad"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));

        // 登记证据 → 201
        mockMvc.perform(post("/api/races/race-ev-api/finish-evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evidenceId":"ev-1","finishTimeMs":1000,"suggestedOrder":["b","a"],
                                 "capturedAt":1759000000000,"operator":"photo-judge",
                                 "expectedVersion":3,"requestId":"req-ev-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.evidenceId").value("ev-1"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.finishKey").isString())
                .andExpect(jsonPath("$.capturedAt").value(1759000000000L));

        // 裁决 → 201，名次不重复
        mockMvc.perform(post("/api/races/race-ev-api/finish-adjudications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"adjudicationId":"adj-1","evidenceIds":["ev-1"],
                                 "finalOrder":["b","a"],"operator":"chief-judge",
                                 "expectedVersion":4,"requestId":"req-adj-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.finalOrder[0]").value("b"))
                .andExpect(jsonPath("$.raceVersion").value(5));

        // 实时排名按裁决顺序
        mockMvc.perform(get("/api/races/race-ev-api/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].bib").value("b"))
                .andExpect(jsonPath("$.entries[0].rank").value(1))
                .andExpect(jsonPath("$.entries[1].bib").value("a"))
                .andExpect(jsonPath("$.entries[1].rank").value(2));

        // 已裁决证据不可撤回 → 409
        mockMvc.perform(post("/api/races/race-ev-api/finish-evidence/ev-1/withdrawal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":5,"requestId":"req-wd-1"}
                                """))
                .andExpect(status().isConflict());

        // 证据与裁决快照查询
        mockMvc.perform(get("/api/races/race-ev-api/finish-evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].evidenceId").value("ev-1"))
                .andExpect(jsonPath("$[0].status").value("ADJUDICATED"));
        mockMvc.perform(get("/api/races/race-ev-api/finish-adjudications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].adjudicationId").value("adj-1"));

        // 封榜 → 200
        mockMvc.perform(post("/api/races/race-ev-api/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":5,"requestId":"req-seal"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.entries[0].bib").value("b"));

        // 封榜后登记证据 → 409
        mockMvc.perform(post("/api/races/race-ev-api/finish-evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evidenceId":"ev-2","finishTimeMs":1000,"suggestedOrder":["a","b"],
                                 "capturedAt":1759000000001,"operator":"photo-judge",
                                 "expectedVersion":6,"requestId":"req-ev-2"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }
}
