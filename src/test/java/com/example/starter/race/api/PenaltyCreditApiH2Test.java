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
 * 校验合成场景（100000+10000-6000=104000）与 201/200/422/409 状态码。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class PenaltyCreditApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 合成场景全链路_登记确认后净成绩104000() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-credit-api","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-credit-api/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":100000,"expectedVersion":1,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-credit-api/penalties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"penaltyId":"p-1","bib":"a","type":"ADD_TIME","amountMs":10000,
                                 "expectedVersion":2,"requestId":"req-pen"}
                                """))
                .andExpect(status().isCreated());

        // 登记执行凭证：停留 [1000, 7000)，时长6000毫秒
        mockMvc.perform(post("/api/races/race-credit-api/penalty-credits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"creditId":"c-1","penaltyId":"p-1","bib":"a",
                                 "startUtcMs":1000,"endUtcMs":7000,"registeredBy":"ref-1",
                                 "expectedVersion":3,"requestId":"req-c1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.durationMs").value(6000))
                .andExpect(jsonPath("$.creditedMs").doesNotExist());

        // 另一名裁判确认
        mockMvc.perform(post("/api/races/race-credit-api/penalty-credits/c-1/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"confirmedBy":"ref-2","expectedVersion":4,"requestId":"req-cf1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.creditedMs").value(6000))
                .andExpect(jsonPath("$.confirmedBy").value("ref-2"));

        // 查询同时返回原罚时10000与已执行量6000，净成绩104000
        mockMvc.perform(get("/api/races/race-credit-api/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].penaltyMs").value(10000))
                .andExpect(jsonPath("$.entries[0].servedMs").value(6000))
                .andExpect(jsonPath("$.entries[0].totalTimeMs").value(104000));

        // 同键同参重放确认：返回首次响应，不再抵扣
        mockMvc.perform(post("/api/races/race-credit-api/penalty-credits/c-1/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"confirmedBy":"ref-2","expectedVersion":4,"requestId":"req-cf1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditedMs").value(6000));
        mockMvc.perform(get("/api/races/race-credit-api/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].servedMs").value(6000));
    }

    @Test
    void 无效区间422_同裁判确认422_重复确认409() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-credit-err","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-credit-err/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":100000,"expectedVersion":1,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-credit-err/penalties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"penaltyId":"p-1","bib":"a","type":"ADD_TIME","amountMs":10000,
                                 "expectedVersion":2,"requestId":"req-pen"}
                                """))
                .andExpect(status().isCreated());

        // 无效区间：结束等于开始 -> 422
        mockMvc.perform(post("/api/races/race-credit-err/penalty-credits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"creditId":"c-bad","penaltyId":"p-1","bib":"a",
                                 "startUtcMs":1000,"endUtcMs":1000,"registeredBy":"ref-1",
                                 "expectedVersion":3,"requestId":"req-cbad"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));

        mockMvc.perform(post("/api/races/race-credit-err/penalty-credits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"creditId":"c-1","penaltyId":"p-1","bib":"a",
                                 "startUtcMs":1000,"endUtcMs":7000,"registeredBy":"ref-1",
                                 "expectedVersion":3,"requestId":"req-c1"}
                                """))
                .andExpect(status().isCreated());

        // 同一裁判确认 -> 422
        mockMvc.perform(post("/api/races/race-credit-err/penalty-credits/c-1/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"confirmedBy":"ref-1","expectedVersion":4,"requestId":"req-cf-same"}
                                """))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(post("/api/races/race-credit-err/penalty-credits/c-1/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"confirmedBy":"ref-2","expectedVersion":4,"requestId":"req-cf1"}
                                """))
                .andExpect(status().isOk());

        // 不同 requestId 重复确认 -> 409
        mockMvc.perform(post("/api/races/race-credit-err/penalty-credits/c-1/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"confirmedBy":"ref-2","expectedVersion":5,"requestId":"req-cf2"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }
}