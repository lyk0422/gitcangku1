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
 * 罚时执行凭证 API 的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 校验合成场景成绩、状态码（201/200/409/422）与封榜冻结。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class ExecutionApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 登记确认执行凭证到封榜全链路() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-exec-api","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-exec-api/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":100000,"expectedVersion":1,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-exec-api/penalties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"penaltyId":"p-1","bib":"a","type":"ADD_TIME","amountMs":10000,
                                 "expectedVersion":2,"requestId":"req-pen"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amountMs").value(10000))
                .andExpect(jsonPath("$.executedMs").value(0));

        // 登记执行凭证 [1000000,1006000)，长度6000毫秒
        mockMvc.perform(post("/api/races/race-exec-api/executions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"executionId":"ex-1","bib":"a","penaltyId":"p-1",
                                 "startMs":1000000,"endMs":1006000,"judge":"judge-1",
                                 "expectedVersion":3,"requestId":"req-ex-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.creditedMs").doesNotExist());

        // 另一名裁判确认，抵扣6000
        mockMvc.perform(post("/api/races/race-exec-api/executions/ex-1/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"judge":"judge-2","expectedVersion":4,"requestId":"req-ex-1-c"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.creditedMs").value(6000))
                .andExpect(jsonPath("$.confirmedBy").value("judge-2"));

        // 成绩：原罚时10000不改写，已执行6000，净成绩104000
        mockMvc.perform(get("/api/races/race-exec-api/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].penaltyMs").value(10000))
                .andExpect(jsonPath("$.entries[0].executedMs").value(6000))
                .andExpect(jsonPath("$.entries[0].totalTimeMs").value(104000));

        // 同键同参重放首次响应；异键重复确认409，不再抵扣
        mockMvc.perform(post("/api/races/race-exec-api/executions/ex-1/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"judge":"judge-2","expectedVersion":4,"requestId":"req-ex-1-c"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditedMs").value(6000));
        mockMvc.perform(post("/api/races/race-exec-api/executions/ex-1/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"judge":"judge-3","expectedVersion":5,"requestId":"req-ex-1-c2"}
                                """))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/races/race-exec-api/results"))
                .andExpect(jsonPath("$.entries[0].executedMs").value(6000))
                .andExpect(jsonPath("$.entries[0].totalTimeMs").value(104000));

        // 封榜冻结后禁止新确认
        mockMvc.perform(post("/api/races/race-exec-api/executions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"executionId":"ex-2","bib":"a","penaltyId":"p-1",
                                 "startMs":2000000,"endMs":2006000,"judge":"judge-1",
                                 "expectedVersion":5,"requestId":"req-ex-2"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-exec-api/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":6,"requestId":"req-seal"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].executedMs").value(6000))
                .andExpect(jsonPath("$.entries[0].totalTimeMs").value(104000));
        mockMvc.perform(post("/api/races/race-exec-api/executions/ex-2/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"judge":"judge-2","expectedVersion":7,"requestId":"req-ex-2-c"}
                                """))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/races/race-exec-api/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].penaltyMs").value(10000))
                .andExpect(jsonPath("$.entries[0].executedMs").value(6000))
                .andExpect(jsonPath("$.entries[0].totalTimeMs").value(104000));
    }

    @Test
    void 跨选手取消资格与无效区间返回422() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-exec-422","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-exec-422/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":100000,"expectedVersion":1,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-exec-422/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b","finishTimeMs":90000,"expectedVersion":2,"requestId":"req-b"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-exec-422/penalties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"penaltyId":"p-1","bib":"a","type":"ADD_TIME","amountMs":10000,
                                 "expectedVersion":3,"requestId":"req-pen"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-exec-422/penalties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"penaltyId":"p-dq","bib":"a","type":"DISQUALIFY",
                                 "expectedVersion":4,"requestId":"req-dq"}
                                """))
                .andExpect(status().isCreated());

        // 跨选手引用 -> 422
        mockMvc.perform(post("/api/races/race-exec-422/executions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"executionId":"ex-x1","bib":"b","penaltyId":"p-1",
                                 "startMs":0,"endMs":6000,"judge":"judge-1",
                                 "expectedVersion":5,"requestId":"req-x1"}
                                """))
                .andExpect(status().isUnprocessableEntity());
        // 取消资格处罚 -> 422
        mockMvc.perform(post("/api/races/race-exec-422/executions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"executionId":"ex-x2","bib":"a","penaltyId":"p-dq",
                                 "startMs":0,"endMs":6000,"judge":"judge-1",
                                 "expectedVersion":5,"requestId":"req-x2"}
                                """))
                .andExpect(status().isUnprocessableEntity());
        // 无效区间 -> 422
        mockMvc.perform(post("/api/races/race-exec-422/executions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"executionId":"ex-x3","bib":"a","penaltyId":"p-1",
                                 "startMs":6000,"endMs":6000,"judge":"judge-1",
                                 "expectedVersion":5,"requestId":"req-x3"}
                                """))
                .andExpect(status().isUnprocessableEntity());
        // 失败不占键：同 requestId 携带合法参数可成功
        mockMvc.perform(post("/api/races/race-exec-422/executions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"executionId":"ex-ok","bib":"a","penaltyId":"p-1",
                                 "startMs":0,"endMs":6000,"judge":"judge-1",
                                 "expectedVersion":5,"requestId":"req-x3"}
                                """))
                .andExpect(status().isCreated());
    }
}