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
 * 校验状态码（201/200/400/404/409/422）、JSON 字段与 rosterKey 幂等重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class TeamRosterApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 建队锁定查询解锁到封榜全链路() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-api-team","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());

        registerRunner("a", 1000, 1);
        registerRunner("b", 2000, 2);
        registerRunner("c", 3000, 3);
        registerRunner("d", 4000, 4);

        // 创建队伍（队长须已报名）
        mockMvc.perform(post("/api/races/race-api-team/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamId":"t1","captainBib":"a","expectedVersion":5,"requestId":"req-team-t1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.rosterVersion").value(0))
                .andExpect(jsonPath("$.raceVersion").value(6));

        mockMvc.perform(post("/api/races/race-api-team/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamId":"t2","captainBib":"c","expectedVersion":6,"requestId":"req-team-t2"}
                                """))
                .andExpect(status().isCreated());

        // 维护名单
        mockMvc.perform(post("/api/races/race-api-team/teams/t1/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","expectedVersion":7,"requestId":"req-m-a"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.members[0]").value("a"));
        mockMvc.perform(post("/api/races/race-api-team/teams/t1/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b","expectedVersion":8,"requestId":"req-m-b"}
                                """))
                .andExpect(status().isCreated());

        // 队长锁定名单：rosterKey 幂等
        String lockBody = """
                {"captainBib":"a","members":["b","a"],"expectedVersion":9}
                """;
        mockMvc.perform(post("/api/races/race-api-team/teams/t1/roster-lock")
                        .contentType(MediaType.APPLICATION_JSON).content(lockBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rosterVersion").value(1))
                .andExpect(jsonPath("$.raceVersion").value(10))
                .andExpect(jsonPath("$.members[0]").value("a"))
                .andExpect(jsonPath("$.members[1]").value("b"))
                .andExpect(jsonPath("$.rosterKey").isString());
        // 同键同参重放首次结果
        mockMvc.perform(post("/api/races/race-api-team/teams/t1/roster-lock")
                        .contentType(MediaType.APPLICATION_JSON).content(lockBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rosterVersion").value(1))
                .andExpect(jsonPath("$.raceVersion").value(10));

        // 锁定后普通增删成员 -> 409
        mockMvc.perform(post("/api/races/race-api-team/teams/t1/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"c","expectedVersion":10,"requestId":"req-m-c"}
                                """))
                .andExpect(status().isConflict());

        // 查询名单版本与归属
        mockMvc.perform(get("/api/races/race-api-team/teams/t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOCKED"))
                .andExpect(jsonPath("$.rosterVersion").value(1))
                .andExpect(jsonPath("$.locks[0].unlocked").value(false));
        mockMvc.perform(get("/api/races/race-api-team/runners/b/team"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamId").value("t1"))
                .andExpect(jsonPath("$.teamStatus").value("LOCKED"));
        mockMvc.perform(get("/api/races/race-api-team/runners/d/team"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamId").doesNotExist());

        // 团队得分：a=1000 + b=2000
        mockMvc.perform(get("/api/races/race-api-team/team-standings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teams[0].teamId").value("t1"))
                .andExpect(jsonPath("$.teams[0].totalTimeMs").value(3000))
                .andExpect(jsonPath("$.teams[0].raceVersion").value(10));

        // 批量锁定 t2 成员跨队 -> 整批422
        mockMvc.perform(post("/api/races/race-api-team/roster-locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":10,"requestId":"req-batch",
                                 "teams":[{"teamId":"t2","captainBib":"c","members":["b","c"]}]}
                                """))
                .andExpect(status().isUnprocessableEntity());
        // 批量锁定 t2 合法 -> 201
        mockMvc.perform(post("/api/races/race-api-team/roster-locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":10,"requestId":"req-batch-2",
                                 "teams":[{"teamId":"t2","captainBib":"c","members":["c","d"]}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.raceVersion").value(11))
                .andExpect(jsonPath("$.locks[0].rosterVersion").value(1));

        // 解锁缺少原因 -> 400
        mockMvc.perform(post("/api/races/race-api-team/teams/t1/roster-unlock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":11,"requestId":"req-unlock-0"}
                                """))
                .andExpect(status().isBadRequest());
        // 裁判解锁 -> 200
        mockMvc.perform(post("/api/races/race-api-team/teams/t1/roster-unlock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"裁判更正","expectedVersion":11,"requestId":"req-unlock"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));

        // 封榜 -> 团队快照固化；封榜后解锁 -> 409
        mockMvc.perform(post("/api/races/race-api-team/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":12,"requestId":"req-seal"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/races/race-api-team/team-standings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.teams[0].teamId").value("t2"))
                .andExpect(jsonPath("$.teams[0].rosterVersion").value(1))
                .andExpect(jsonPath("$.teams[0].totalTimeMs").value(7000));
        mockMvc.perform(post("/api/races/race-api-team/teams/t2/roster-unlock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"封榜后尝试","expectedVersion":13,"requestId":"req-unlock-2"}
                                """))
                .andExpect(status().isConflict());

        // 不存在的队伍 -> 404
        mockMvc.perform(get("/api/races/race-api-team/teams/t-x"))
                .andExpect(status().isNotFound());
    }

    private void registerRunner(String bib, long finishMs, int expectedVersion) throws Exception {
        mockMvc.perform(post("/api/races/race-api-team/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"%s","finishTimeMs":%d,"expectedVersion":%d,"requestId":"req-reg-%s"}
                                """.formatted(bib, finishMs, expectedVersion, bib)))
                .andExpect(status().isCreated());
    }
}
