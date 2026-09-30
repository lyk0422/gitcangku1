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
 * 团队名单锁定 HTTP 接口端到端测试：真实 Spring MVC + Service + H2，
 * 覆盖锁定、跨队409、退赛、封榜快照只读与幂等重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class TeamRosterApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 名单锁定退赛到封榜团队快照全链路() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-team","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/races/race-team/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"jia","finishTimeMs":1000,"expectedVersion":1,"requestId":"req-jia"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-team/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"yi","finishTimeMs":2000,"expectedVersion":2,"requestId":"req-yi"}
                                """))
                .andExpect(status().isCreated());

        // 队A锁定甲乙（版本3），成员成绩随版本固化。
        mockMvc.perform(post("/api/races/race-team/teams/A/roster-locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"members":["jia","yi"],"expectedVersion":3,"requestId":"req-lock-a1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rosterVersion").value(1))
                .andExpect(jsonPath("$.current").value(true))
                .andExpect(jsonPath("$.members[0].bib").value("jia"))
                .andExpect(jsonPath("$.members[0].totalTimeMs").value(1000))
                .andExpect(jsonPath("$.members[0].entryStatus").value("RANKED"))
                .andExpect(jsonPath("$.members[0].withdrawn").value(false));

        // 队B锁入甲：跨队409。
        mockMvc.perform(post("/api/races/race-team/teams/B/roster-locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"members":["jia"],"expectedVersion":4,"requestId":"req-lock-b"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));

        // 同键换名单409。
        mockMvc.perform(post("/api/races/race-team/teams/A/roster-locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"members":["jia","yi"],"expectedVersion":3,"requestId":"req-lock-a1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rosterVersion").value(1));
        mockMvc.perform(post("/api/races/race-team/teams/A/roster-locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"members":["yi"],"expectedVersion":3,"requestId":"req-lock-a1"}
                                """))
                .andExpect(status().isConflict());

        // 请求体校验：空名单400。
        mockMvc.perform(post("/api/races/race-team/teams/A/roster-locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"members":[],"expectedVersion":4,"requestId":"req-lock-empty"}
                                """))
                .andExpect(status().isBadRequest());

        // 甲退赛：第2场生效。
        mockMvc.perform(post("/api/races/race-team/teams/A/members/jia/withdrawal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"effectiveSession":2,"reason":"伤病","requestId":"req-wd-jia"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bib").value("jia"))
                .andExpect(jsonPath("$.effectiveSession").value(2));

        // 场次1：甲仍计分，总分3000。
        mockMvc.perform(get("/api/races/race-team/team-results").param("session", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.teams[0].totalTimeMs").value(3000))
                .andExpect(jsonPath("$.teams[0].scoringCount").value(2));
        // 场次2：甲不计分，总分2000。
        mockMvc.perform(get("/api/races/race-team/team-results").param("session", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teams[0].totalTimeMs").value(2000))
                .andExpect(jsonPath("$.teams[0].members[0].scoring").value(false))
                .andExpect(jsonPath("$.teams[0].members[0].withdrawn").value(true));

        // 当前名单反映退赛；历史版本原样。
        mockMvc.perform(get("/api/races/race-team/teams/A/roster"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].withdrawn").value(true))
                .andExpect(jsonPath("$.withdrawals[0].reason").value("伤病"));
        mockMvc.perform(get("/api/races/race-team/teams/A/roster/versions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(true))
                .andExpect(jsonPath("$.members[0].withdrawn").value(false))
                .andExpect(jsonPath("$.withdrawals").isArray())
                .andExpect(jsonPath("$.withdrawals").isEmpty());

        // 封榜（当前赛事版本4）：封榜时甲退赛已生效，团队总分2000。
        mockMvc.perform(post("/api/races/race-team/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":4,"requestId":"req-seal"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/races/race-team/team-snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.teams[0].rosterVersion").value(1))
                .andExpect(jsonPath("$.teams[0].totalTimeMs").value(2000))
                .andExpect(jsonPath("$.teams[0].scoringCount").value(1))
                .andExpect(jsonPath("$.teams[0].members[0].withdrawn").value(true))
                .andExpect(jsonPath("$.teams[0].members[0].scoring").value(false));

        // 封榜后锁定名单409。
        mockMvc.perform(post("/api/races/race-team/teams/A/roster-locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"members":["jia","yi"],"expectedVersion":5,"requestId":"req-lock-after"}
                                """))
                .andExpect(status().isConflict());
        // 不存在的赛事查询团队快照为404。
        mockMvc.perform(get("/api/races/missing/team-snapshot"))
                .andExpect(status().isNotFound());
    }
}
