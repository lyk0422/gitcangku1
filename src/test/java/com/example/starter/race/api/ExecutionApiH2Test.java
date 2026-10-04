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
 * 执行凭证 API 的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 校验状态码（201/200/409/422）、JSON 字段与幂等重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class ExecutionApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 登记确认凭证到成绩与处罚查询全链路() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-api-exec","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-api-exec/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":100000,"expectedVersion":1,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-api-exec/penalties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"penaltyId":"p-1","bib":"a","type":"ADD_TIME","amountMs":10000,
                                 "expectedVersion":2,"requestId":"req-pen"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amountMs").value(10000))
                .andExpect(jsonPath("$.servedMs").value(0));

        // 登记凭证 [1000000,1006000) 长度6000
        mockMvc.perform(post("/api/races/race-api-exec/penalties/p-1/executions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"voucherId":"v-1","bib":"a","startUtcMs":1000000,"endUtcMs":1006000,
                                 "referee":"ref-a","expectedVersion":3,"requestId":"req-ex-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.creditedMs").value(0));

        // 另一名裁判确认 -> 抵扣6000
        mockMvc.perform(post("/api/races/race-api-exec/penalties/p-1/executions/v-1/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"confirmedBy":"ref-b","expectedVersion":4,"requestId":"req-cf-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedBy").value("ref-b"))
                .andExpect(jsonPath("$.creditedMs").value(6000));

        // 成绩：原罚时10000、已执行6000、净成绩104000
        mockMvc.perform(get("/api/races/race-api-exec/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].penaltyMs").value(10000))
                .andExpect(jsonPath("$.entries[0].servedMs").value(6000))
                .andExpect(jsonPath("$.entries[0].totalTimeMs").value(104000));

        // 处罚查询同时返回原罚时与已执行量
        mockMvc.perform(get("/api/races/race-api-exec/penalties"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].penaltyId").value("p-1"))
                .andExpect(jsonPath("$[0].amountMs").value(10000))
                .andExpect(jsonPath("$[0].servedMs").value(6000));

        // 同 requestId 重放首次确认响应，不再抵扣
        mockMvc.perform(post("/api/races/race-api-exec/penalties/p-1/executions/v-1/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"confirmedBy":"ref-b","expectedVersion":4,"requestId":"req-cf-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditedMs").value(6000));
        mockMvc.perform(get("/api/races/race-api-exec/results"))
                .andExpect(jsonPath("$.entries[0].servedMs").value(6000))
                .andExpect(jsonPath("$.entries[0].totalTimeMs").value(104000));

        // 不同 requestId 重复确认 -> 409
        mockMvc.perform(post("/api/races/race-api-exec/penalties/p-1/executions/v-1/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"confirmedBy":"ref-c","expectedVersion":5,"requestId":"req-cf-2"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void 无效区间与跨选手引用返回422() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-api-422","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-api-422/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":100000,"expectedVersion":1,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-api-422/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b","finishTimeMs":100000,"expectedVersion":2,"requestId":"req-b"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-api-422/penalties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"penaltyId":"p-1","bib":"a","type":"ADD_TIME","amountMs":10000,
                                 "expectedVersion":3,"requestId":"req-pen"}
                                """))
                .andExpect(status().isCreated());

        // 无效区间 end<=start -> 422
        mockMvc.perform(post("/api/races/race-api-422/penalties/p-1/executions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"voucherId":"v-bad","bib":"a","startUtcMs":2000,"endUtcMs":1000,
                                 "referee":"ref-a","expectedVersion":4,"requestId":"req-ex-bad"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));

        // 跨选手引用 -> 422
        mockMvc.perform(post("/api/races/race-api-422/penalties/p-1/executions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"voucherId":"v-x","bib":"b","startUtcMs":1000,"endUtcMs":2000,
                                 "referee":"ref-a","expectedVersion":4,"requestId":"req-ex-x"}
                                """))
                .andExpect(status().isUnprocessableEntity());

        // 失败请求不写入：版本仍为4，凭证不存在
        mockMvc.perform(get("/api/races/race-api-422/results"))
                .andExpect(jsonPath("$.version").value(4));
    }
}