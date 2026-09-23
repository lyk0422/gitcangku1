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
 * 处罚申诉全链路 HTTP 测试：真实 Spring MVC + Service + H2，
 * 校验受理冻结、榜单申诉中标记、两干事裁决、封榜门禁与重算榜单。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class AppealApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    private void seed() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-api-appeal","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-api-appeal/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":1000,"expectedVersion":1,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-api-appeal/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b","finishTimeMs":1000,"expectedVersion":2,"requestId":"req-b"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-api-appeal/penalties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"penaltyId":"pen-b","bib":"b","type":"ADD_TIME","amountMs":500,
                                 "expectedVersion":3,"requestId":"req-pen"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void 申诉受理到REMOVE裁决重算榜单全链路() throws Exception {
        seed();

        // 受理申诉
        mockMvc.perform(post("/api/races/race-api-appeal/penalties/pen-b/appeals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"appealKey":"appeal-1","penaltyVersion":1,
                                 "reason":"计时误判","requestId":"req-appeal"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.frozenResult.leaderboardVersion").value(4))
                .andExpect(jsonPath("$.frozenResult.totalTimeMs").value(1500))
                .andExpect(jsonPath("$.frozenResult.rank").value(2))
                .andExpect(jsonPath("$.beforeLeaderboard.version").value(4))
                .andExpect(jsonPath("$.afterLeaderboard").doesNotExist());

        // 公开榜单仍按原处罚，b 标记申诉中，版本不变（a 第1，b 第2）
        mockMvc.perform(get("/api/races/race-api-appeal/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.entries[1].bib").value("b"))
                .andExpect(jsonPath("$.entries[1].appealPending").value(true))
                .andExpect(jsonPath("$.entries[1].totalTimeMs").value(1500))
                .andExpect(jsonPath("$.entries[0].appealPending").value(false));

        // 待决申诉期间封榜被门禁拦截
        mockMvc.perform(post("/api/races/race-api-appeal/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":4,"requestId":"req-seal"}
                                """))
                .andExpect(status().isConflict());

        // 第一人建议 REMOVE
        mockMvc.perform(post("/api/races/race-api-appeal/appeals/appeal-1/opinions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stewardId":"s1","recommendation":"REMOVE","requestId":"req-first"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstRecommendation").value("REMOVE"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 第二人建议不一致 → 422
        mockMvc.perform(post("/api/races/race-api-appeal/appeals/appeal-1/opinions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stewardId":"s2","recommendation":"UPHOLD","action":"CONFIRM",
                                 "requestId":"req-mismatch"}
                                """))
                .andExpect(status().isUnprocessableEntity());

        // 第二人确认 REMOVE
        mockMvc.perform(post("/api/races/race-api-appeal/appeals/appeal-1/opinions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stewardId":"s2","recommendation":"REMOVE","action":"CONFIRM",
                                 "requestId":"req-second"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REMOVED"))
                .andExpect(jsonPath("$.secondAction").value("CONFIRM"))
                .andExpect(jsonPath("$.afterLeaderboard.version").value(5))
                .andExpect(jsonPath("$.afterLeaderboard.entries[1].bib").value("b"))
                .andExpect(jsonPath("$.afterLeaderboard.entries[1].rank").value(1));

        // 榜单重算：b 撤销加时后与 a 并列第1，版本只推进一次
        mockMvc.perform(get("/api/races/race-api-appeal/results"))
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.entries[1].bib").value("b"))
                .andExpect(jsonPath("$.entries[1].totalTimeMs").value(1000))
                .andExpect(jsonPath("$.entries[1].rank").value(1))
                .andExpect(jsonPath("$.entries[1].appealPending").value(false));

        // 证据只读查询
        mockMvc.perform(get("/api/races/race-api-appeal/appeals/appeal-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REMOVED"))
                .andExpect(jsonPath("$.firstStewardId").value("s1"))
                .andExpect(jsonPath("$.secondStewardId").value("s2"));
        mockMvc.perform(get("/api/races/race-api-appeal/appeals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appeals[0].appealKey").value("appeal-1"));
    }

    @Test
    void REPLACE裁决与双人约束和幂等HTTP状态码() throws Exception {
        seed();

        mockMvc.perform(post("/api/races/race-api-appeal/penalties/pen-b/appeals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"appealKey":"appeal-r","penaltyVersion":1,
                                 "reason":"改判","requestId":"req-appeal-r"}
                                """))
                .andExpect(status().isCreated());

        // 第一人 REPLACE 缺 replacementMs → 400
        mockMvc.perform(post("/api/races/race-api-appeal/appeals/appeal-r/opinions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stewardId":"s1","recommendation":"REPLACE","requestId":"req-no-ms"}
                                """))
                .andExpect(status().isBadRequest());

        // 第一人 REPLACE 200
        mockMvc.perform(post("/api/races/race-api-appeal/appeals/appeal-r/opinions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stewardId":"s1","recommendation":"REPLACE","replacementMs":200,
                                 "requestId":"req-first-r"}
                                """))
                .andExpect(status().isOk());

        // 同一干事确认 → 422
        mockMvc.perform(post("/api/races/race-api-appeal/appeals/appeal-r/opinions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stewardId":"s1","recommendation":"REPLACE","replacementMs":200,
                                 "action":"CONFIRM","requestId":"req-same-steward"}
                                """))
                .andExpect(status().isUnprocessableEntity());

        // 同键同参重放第一人意见 → 200
        mockMvc.perform(post("/api/races/race-api-appeal/appeals/appeal-r/opinions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stewardId":"s1","recommendation":"REPLACE","replacementMs":200,
                                 "requestId":"req-first-r"}
                                """))
                .andExpect(status().isOk());

        // 第二人确认 REPLACE 200
        mockMvc.perform(post("/api/races/race-api-appeal/appeals/appeal-r/opinions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stewardId":"s2","recommendation":"REPLACE","replacementMs":200,
                                 "action":"CONFIRM","requestId":"req-second-r"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPLACED"))
                .andExpect(jsonPath("$.newPenaltyId").isNotEmpty())
                .andExpect(jsonPath("$.afterLeaderboard.entries[1].bib").value("b"))
                .andExpect(jsonPath("$.afterLeaderboard.entries[1].penaltyMs").value(200))
                .andExpect(jsonPath("$.afterLeaderboard.entries[1].rank").value(2));
    }
}
