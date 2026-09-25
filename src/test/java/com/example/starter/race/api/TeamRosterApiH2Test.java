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
 * 队伍名单锁定 API 的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 校验状态码（201/200/404/409/422）、JSON 字段与封榜后只读快照。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class TeamRosterApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    private void postJson(String url, String body, int expectedStatus) throws Exception {
        mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus));
    }

    private void setupLockedRace() throws Exception {
        postJson("/api/races", """
                {"raceId":"race-team-api","requestId":"req-create"}
                """, 201);
        postJson("/api/races/race-team-api/runners", """
                {"bib":"a","finishTimeMs":1000,"expectedVersion":1,"requestId":"req-a"}
                """, 201);
        postJson("/api/races/race-team-api/runners", """
                {"bib":"b","finishTimeMs":2000,"expectedVersion":2,"requestId":"req-b"}
                """, 201);
        postJson("/api/races/race-team-api/runners", """
                {"bib":"c","finishTimeMs":3000,"expectedVersion":3,"requestId":"req-c"}
                """, 201);
        postJson("/api/races/race-team-api/runners", """
                {"bib":"d","finishTimeMs":4000,"expectedVersion":4,"requestId":"req-d"}
                """, 201);
        postJson("/api/races/race-team-api/teams", """
                {"teamId":"t1","captainBib":"a","expectedVersion":5,"requestId":"req-t1"}
                """, 201);
        postJson("/api/races/race-team-api/teams", """
                {"teamId":"t2","captainBib":"c","expectedVersion":6,"requestId":"req-t2"}
                """, 201);
        postJson("/api/races/race-team-api/roster-locks", """
                {"locks":[{"teamId":"t1","captainBib":"a","members":["a","b"]},
                          {"teamId":"t2","captainBib":"c","members":["c","d"]}],
                 "expectedVersion":7,"requestId":"req-lock"}
                """, 201);
    }

    @Test
    void 建队锁定查询到封榜快照全链路() throws Exception {
        setupLockedRace();

        mockMvc.perform(get("/api/races/race-team-api/teams/t1/roster"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOCKED"))
                .andExpect(jsonPath("$.rosterVersion").value(1))
                .andExpect(jsonPath("$.members[0]").value("a"))
                .andExpect(jsonPath("$.members[1]").value("b"))
                .andExpect(jsonPath("$.lockRaceVersion").value(8));

        mockMvc.perform(get("/api/races/race-team-api/runners/b/team"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamId").value("t1"))
                .andExpect(jsonPath("$.locked").value(true));

        mockMvc.perform(get("/api/races/race-team-api/team-standings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(8))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.teams[0].teamId").value("t1"))
                .andExpect(jsonPath("$.teams[0].teamScoreMs").value(3000))
                .andExpect(jsonPath("$.teams[0].teamRank").value(1))
                .andExpect(jsonPath("$.teams[1].teamId").value("t2"))
                .andExpect(jsonPath("$.teams[1].teamScoreMs").value(7000));

        // 同键同参重放首次结果
        mockMvc.perform(post("/api/races/race-team-api/roster-locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"locks":[{"teamId":"t1","captainBib":"a","members":["a","b"]},
                                          {"teamId":"t2","captainBib":"c","members":["c","d"]}],
                                 "expectedVersion":7,"requestId":"req-lock"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(8));

        // 解锁后重锁生成新版本
        postJson("/api/races/race-team-api/teams/t1/roster-unlock", """
                {"reason":"裁判更正","expectedVersion":8,"requestId":"req-unlock"}
                """, 200);
        mockMvc.perform(get("/api/races/race-team-api/teams/t1/roster"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNLOCKED"))
                .andExpect(jsonPath("$.unlockReason").value("裁判更正"));
        postJson("/api/races/race-team-api/roster-locks", """
                {"locks":[{"teamId":"t1","captainBib":"a","members":["a","b"]}],
                 "expectedVersion":9,"requestId":"req-relock"}
                """, 201);
        mockMvc.perform(get("/api/races/race-team-api/teams/t1/roster"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rosterVersion").value(2));

        // 封榜后快照固化名单版本、成绩版本与团队得分
        postJson("/api/races/race-team-api/seal", """
                {"expectedVersion":10,"requestId":"req-seal"}
                """, 200);
        mockMvc.perform(get("/api/races/race-team-api/team-snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultVersion").value(11))
                .andExpect(jsonPath("$.teams[0].teamId").value("t1"))
                .andExpect(jsonPath("$.teams[0].rosterVersion").value(2))
                .andExpect(jsonPath("$.teams[0].teamScoreMs").value(3000))
                .andExpect(jsonPath("$.teams[0].teamRank").value(1))
                .andExpect(jsonPath("$.teams[1].teamId").value("t2"))
                .andExpect(jsonPath("$.teams[1].rosterVersion").value(1));

        // 封榜后解锁/改名单/新增成员均 409
        postJson("/api/races/race-team-api/teams/t1/roster-unlock", """
                {"reason":"封榜后解锁","expectedVersion":11,"requestId":"req-unlock-2"}
                """, 409);
        postJson("/api/races/race-team-api/teams/t1/members", """
                {"bib":"c","expectedVersion":11,"requestId":"req-add-2"}
                """, 409);
        postJson("/api/races/race-team-api/teams/t1/members/b/removal", """
                {"expectedVersion":11,"requestId":"req-rm-2"}
                """, 409);
    }

    @Test
    void 批量锁定校验失败整批422() throws Exception {
        postJson("/api/races", """
                {"raceId":"race-team-api","requestId":"req-create"}
                """, 201);
        postJson("/api/races/race-team-api/runners", """
                {"bib":"a","expectedVersion":1,"requestId":"req-a"}
                """, 201);
        postJson("/api/races/race-team-api/runners", """
                {"bib":"b","expectedVersion":2,"requestId":"req-b"}
                """, 201);
        postJson("/api/races/race-team-api/teams", """
                {"teamId":"t1","captainBib":"a","expectedVersion":3,"requestId":"req-t1"}
                """, 201);

        // 成员无有效报名 → 422
        mockMvc.perform(post("/api/races/race-team-api/roster-locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"locks":[{"teamId":"t1","captainBib":"a","members":["a","ghost"]}],
                                 "expectedVersion":4,"requestId":"req-lock-bad"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));

        // 人数不足 → 422
        mockMvc.perform(post("/api/races/race-team-api/roster-locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"locks":[{"teamId":"t1","captainBib":"a","members":["a"]}],
                                 "expectedVersion":4,"requestId":"req-lock-small"}
                                """))
                .andExpect(status().isUnprocessableEntity());

        // 失败后赛事版本未推进，可用正确参数继续
        mockMvc.perform(post("/api/races/race-team-api/roster-locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"locks":[{"teamId":"t1","captainBib":"a","members":["a","b"]}],
                                 "expectedVersion":4,"requestId":"req-lock-ok"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.locks[0].rosterVersion").value(1));

        // 锁定后普通增删成员 → 409
        postJson("/api/races/race-team-api/teams/t1/members", """
                {"bib":"c","expectedVersion":5,"requestId":"req-add"}
                """, 409);
    }
}
