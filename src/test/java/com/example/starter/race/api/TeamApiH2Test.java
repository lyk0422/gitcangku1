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
 * 团队计分 API 的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 校验状态码（201/200/400/404/409）、团队成绩 JSON 与封榜快照。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class TeamApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    private void createRace(String raceId) throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"raceId\":\"" + raceId + "\",\"requestId\":\"req-create\"}"))
                .andExpect(status().isCreated());
    }

    private void register(String raceId, String bib, int expectedVersion) throws Exception {
        mockMvc.perform(post("/api/races/" + raceId + "/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bib\":\"" + bib + "\",\"expectedVersion\":"
                                + expectedVersion + ",\"requestId\":\"req-reg-" + bib + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void 团队创建查询到封榜快照全链路() throws Exception {
        createRace("race-team-api");
        register("race-team-api", "a", 1);
        register("race-team-api", "b", 2);
        register("race-team-api", "c", 3);

        mockMvc.perform(post("/api/races/race-team-api/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamCode":"T1","members":["c","a","b"],
                                 "expectedVersion":4,"requestId":"req-team-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.teamCode").value("T1"))
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.members[0]").value("a"))
                .andExpect(jsonPath("$.members[2]").value("c"));

        // 同键同参换序重放：原样返回首次结果
        mockMvc.perform(post("/api/races/race-team-api/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamCode":"T1","members":["b","c","a"],
                                 "expectedVersion":4,"requestId":"req-team-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.teamCode").value("T1"))
                .andExpect(jsonPath("$.version").value(5));

        // 登记时未计时，此处补计时使团队完整
        mockMvc.perform(post("/api/races/race-team-api/timing-revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":300,"expectedVersion":5,"requestId":"req-ta"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/races/race-team-api/timing-revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b","finishTimeMs":100,"expectedVersion":6,"requestId":"req-tb"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/races/race-team-api/timing-revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"c","finishTimeMs":200,"expectedVersion":7,"requestId":"req-tc"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/races/race-team-api/teams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(8))
                .andExpect(jsonPath("$.teams[0].teamCode").value("T1"))
                .andExpect(jsonPath("$.teams[0].rank").value(1))
                .andExpect(jsonPath("$.teams[0].status").value("COMPLETE"))
                .andExpect(jsonPath("$.teams[0].totalTimeMs").value(600))
                .andExpect(jsonPath("$.teams[0].members[0].bib").value("a"))
                .andExpect(jsonPath("$.teams[0].members[0].scoring").value(true))
                .andExpect(jsonPath("$.teams[0].members[0].scoringTimeMs").value(300));

        mockMvc.perform(post("/api/races/race-team-api/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":8,"requestId":"req-seal"}
                                """))
                .andExpect(status().isOk());

        // 封榜后团队查询只返回快照，版本与个人快照一致
        mockMvc.perform(get("/api/races/race-team-api/teams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.version").value(9))
                .andExpect(jsonPath("$.sealedAt").exists())
                .andExpect(jsonPath("$.teams[0].teamCode").value("T1"))
                .andExpect(jsonPath("$.teams[0].rank").value(1))
                .andExpect(jsonPath("$.teams[0].totalTimeMs").value(600))
                .andExpect(jsonPath("$.teams[0].members[1].bib").value("b"))
                .andExpect(jsonPath("$.teams[0].members[1].scoringTimeMs").value(100));
    }

    @Test
    void 团队错误分支状态码() throws Exception {
        // 赛事不存在：404
        mockMvc.perform(get("/api/races/missing/teams"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        mockMvc.perform(post("/api/races/missing/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamCode":"T1","members":["a","b","c"],
                                 "expectedVersion":1,"requestId":"req-t404"}
                                """))
                .andExpect(status().isNotFound());

        createRace("race-team-err");
        register("race-team-err", "a", 1);
        register("race-team-err", "b", 2);
        register("race-team-err", "c", 3);
        register("race-team-err", "d", 4);

        // 成员数量非法：400
        mockMvc.perform(post("/api/races/race-team-err/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamCode":"T1","members":["a","b"],
                                 "expectedVersion":5,"requestId":"req-t400"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        // 成员未登记：404
        mockMvc.perform(post("/api/races/race-team-err/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamCode":"T1","members":["a","b","ghost"],
                                 "expectedVersion":5,"requestId":"req-t404b"}
                                """))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/races/race-team-err/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamCode":"T1","members":["a","b","c"],
                                 "expectedVersion":5,"requestId":"req-t1"}
                                """))
                .andExpect(status().isCreated());

        // 成员已入队：409
        mockMvc.perform(post("/api/races/race-team-err/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamCode":"T2","members":["a","c","d"],
                                 "expectedVersion":6,"requestId":"req-t2"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));

        // 版本冲突：409
        mockMvc.perform(post("/api/races/race-team-err/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamCode":"T3","members":["b","c","d"],
                                 "expectedVersion":1,"requestId":"req-t3"}
                                """))
                .andExpect(status().isConflict());
    }
}
