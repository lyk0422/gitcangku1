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
 * 赛道登记与纪录认定 API 的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 校验状态码（201/200/404/409/422）、JSON 字段、历史链与幂等重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class CourseApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 登记认定到历史链查询全链路() throws Exception {
        // 登记赛道：初始无纪录
        mockMvc.perform(post("/api/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"courseKey":"c-api","requestId":"req-course"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.courseKey").value("c-api"))
                .andExpect(jsonPath("$.currentRecordId").doesNotExist());

        mockMvc.perform(get("/api/courses/c-api/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").doesNotExist())
                .andExpect(jsonPath("$.history").isEmpty());

        // 建赛、登记、封榜
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r-api","courseKey":"c-api","requestId":"req-race"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r-api/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":5000,"expectedVersion":1,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated());

        // 未封榜认定 409
        mockMvc.perform(post("/api/courses/c-api/record-claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r-api","bib":"a","recordClaimKey":"claim-1","requestId":"req-claim-1"}
                                """))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/races/r-api/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":2,"requestId":"req-seal"}
                                """))
                .andExpect(status().isOk());

        // 认定成功 201
        mockMvc.perform(post("/api/courses/c-api/record-claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r-api","bib":"a","recordClaimKey":"claim-1","requestId":"req-claim-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.courseKey").value("c-api"))
                .andExpect(jsonPath("$.seq").value(1))
                .andExpect(jsonPath("$.raceId").value("r-api"))
                .andExpect(jsonPath("$.bib").value("a"))
                .andExpect(jsonPath("$.timeMs").value(5000))
                .andExpect(jsonPath("$.claimedAt").exists());

        // 当前纪录与历史链
        mockMvc.perform(get("/api/courses/c-api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentRecordId").exists());
        mockMvc.perform(get("/api/courses/c-api/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.timeMs").value(5000))
                .andExpect(jsonPath("$.history.length()").value(1))
                .andExpect(jsonPath("$.history[0].bib").value("a"));
    }

    @Test
    void 更优认定替换当前纪录且历史链保留旧纪录() throws Exception {
        seedSealedRace("c-seq", "r1", "a", 5000L, "req-1");
        seedSealedRace("c-seq", "r2", "b", 4000L, "req-2");

        claim("c-seq", "r1", "a", "claim-1", "req-c1")
                .andExpect(status().isCreated());
        claim("c-seq", "r2", "b", "claim-2", "req-c2")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seq").value(2))
                .andExpect(jsonPath("$.timeMs").value(4000));

        mockMvc.perform(get("/api/courses/c-seq/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.timeMs").value(4000))
                .andExpect(jsonPath("$.current.bib").value("b"))
                .andExpect(jsonPath("$.history.length()").value(2))
                .andExpect(jsonPath("$.history[0].timeMs").value(5000))
                .andExpect(jsonPath("$.history[1].timeMs").value(4000));
    }

    @Test
    void 未严格更优返回422并携带实际当前纪录() throws Exception {
        seedSealedRace("c-422", "r1", "a", 5000L, "req-1");
        seedSealedRace("c-422", "r2", "b", 6000L, "req-2");

        claim("c-422", "r1", "a", "claim-1", "req-c1")
                .andExpect(status().isCreated());
        claim("c-422", "r2", "b", "claim-2", "req-c2")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"))
                .andExpect(jsonPath("$.currentRecord.timeMs").value(5000))
                .andExpect(jsonPath("$.currentRecord.bib").value("a"));

        // 历史链未增长
        mockMvc.perform(get("/api/courses/c-422/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history.length()").value(1));
    }

    @Test
    void 认定错误分支状态码与recordClaimKey幂等() throws Exception {
        seedSealedRace("c-err", "r1", "a", 5000L, "req-1");

        // 赛道不存在 404
        claim("ghost-course", "r1", "a", "claim-g", "req-g")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        // 选手不在快照 404
        claim("c-err", "r1", "ghost", "claim-gh", "req-gh")
                .andExpect(status().isNotFound());
        // 参数校验失败 400：缺 recordClaimKey
        mockMvc.perform(post("/api/courses/c-err/record-claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r1","bib":"a","requestId":"req-bad"}
                                """))
                .andExpect(status().isBadRequest());

        claim("c-err", "r1", "a", "claim-1", "req-c1")
                .andExpect(status().isCreated());
        // 同 recordClaimKey 同参（不同 requestId）：幂等返回首次结果
        claim("c-err", "r1", "a", "claim-1", "req-c1-replay")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seq").value(1))
                .andExpect(jsonPath("$.timeMs").value(5000));
        // 同 recordClaimKey 异参：409
        claim("c-err", "r1", "other-bib", "claim-1", "req-c1-diff")
                .andExpect(status().isConflict());
        // 同 requestId 异参：409
        claim("c-err", "r1", "a", "claim-other", "req-c1")
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/courses/c-err/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history.length()").value(1));
    }

    private void seedSealedRace(String courseKey, String raceId, String bib, long finishMs,
                                String requestPrefix) throws Exception {
        // 赛道可能已由同场景的前一次调用登记：201 或 409 均可
        int courseStatus = mockMvc.perform(post("/api/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"courseKey":"%s","requestId":"%s-course"}
                                """.formatted(courseKey, requestPrefix)))
                .andReturn().getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(courseStatus).isIn(201, 409);
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"%s","courseKey":"%s","requestId":"%s-race"}
                                """.formatted(raceId, courseKey, requestPrefix)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/" + raceId + "/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"%s","finishTimeMs":%d,"expectedVersion":1,"requestId":"%s-reg"}
                                """.formatted(bib, finishMs, requestPrefix)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/" + raceId + "/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":2,"requestId":"%s-seal"}
                                """.formatted(requestPrefix)))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions claim(
            String courseKey, String raceId, String bib, String claimKey, String requestId)
            throws Exception {
        return mockMvc.perform(post("/api/courses/" + courseKey + "/record-claims")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"raceId":"%s","bib":"%s","recordClaimKey":"%s","requestId":"%s"}
                        """.formatted(raceId, bib, claimKey, requestId)));
    }
}
