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
 * 团队名单锁定 API 的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 覆盖锁定/退赛/查询/封榜团队榜的状态码、JSON 字段与409分支。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class TeamRosterApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 名单锁定跨队409退赛与封榜团队榜全链路() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"E","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/E/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"jia","finishTimeMs":1000,"expectedVersion":1,"requestId":"r1"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/E/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"yi","finishTimeMs":2000,"expectedVersion":2,"requestId":"r2"}
                                """))
                .andExpect(status().isCreated());

        // A 锁定版本1=[甲,乙]
        mockMvc.perform(post("/api/races/E/teams/A/rosters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"members":["jia","yi"],"expectedVersion":3,"requestId":"lock-a1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.teamPoints").value(19))
                .andExpect(jsonPath("$.members[0].bib").value("jia"))
                .andExpect(jsonPath("$.members[0].rank").value(1))
                .andExpect(jsonPath("$.members[0].memberPoints").value(10));

        // 甲已属 A，B 再锁入甲 -> 409
        mockMvc.perform(post("/api/races/E/teams/B/rosters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"members":["jia"],"expectedVersion":4,"requestId":"lock-b-jia"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));

        // 当前名单
        mockMvc.perform(get("/api/races/E/teams/A/roster"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersionNo").value(1))
                .andExpect(jsonPath("$.members[0].bib").value("jia"))
                .andExpect(jsonPath("$.members[0].state").value("ACTIVE"))
                .andExpect(jsonPath("$.members[0].effectiveSession").doesNotExist());

        // 指定锁定版本
        mockMvc.perform(get("/api/races/E/teams/A/rosters/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.teamPoints").value(19));
        // 不存在版本 404
        mockMvc.perform(get("/api/races/E/teams/A/rosters/9"))
                .andExpect(status().isNotFound());

        // 退赛（OPEN，版本 4->5）
        mockMvc.perform(post("/api/races/E/teams/A/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"jia","effectiveSession":"S3","reason":"injury",
                                 "expectedVersion":4,"requestId":"wd-jia"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("WITHDRAWN"))
                .andExpect(jsonPath("$.effectiveSession").value("S3"))
                .andExpect(jsonPath("$.reason").value("injury"));

        // 当前名单反映退赛但不删人
        mockMvc.perform(get("/api/races/E/teams/A/roster"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.members[0].state").value("WITHDRAWN"));

        // 实时团队榜剔除甲后 A=9
        mockMvc.perform(get("/api/races/E/team-results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.teams[0].teamId").value("A"))
                .andExpect(jsonPath("$.teams[0].teamPoints").value(9))
                .andExpect(jsonPath("$.teams[0].memberCount").value(1));

        // 封榜（版本5->6）
        mockMvc.perform(post("/api/races/E/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":5,"requestId":"seal"}
                                """))
                .andExpect(status().isOk());

        // 封榜团队榜只读：A@1 积分9，不随后续变化
        mockMvc.perform(get("/api/races/E/team-snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.version").value(6))
                .andExpect(jsonPath("$.teams[0].teamId").value("A"))
                .andExpect(jsonPath("$.teams[0].rosterVersionNo").value(1))
                .andExpect(jsonPath("$.teams[0].teamPoints").value(9));

        // 封榜后锁定名单 409
        mockMvc.perform(post("/api/races/E/teams/B/rosters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"members":["yi"],"expectedVersion":6,"requestId":"lock-b-after"}
                                """))
                .andExpect(status().isConflict());

        // 未封榜查询团队快照 404（用新赛事验证）
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"E2","requestId":"req-create-2"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/races/E2/team-snapshot"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 空名单与参数缺失返回400() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"V","requestId":"c"}
                                """))
                .andExpect(status().isCreated());
        // 空成员列表
        mockMvc.perform(post("/api/races/V/teams/A/rosters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"members":[],"expectedVersion":1,"requestId":"empty"}
                                """))
                .andExpect(status().isBadRequest());
        // 缺 expectedVersion
        mockMvc.perform(post("/api/races/V/teams/A/rosters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"members":["x"],"requestId":"noversion"}
                                """))
                .andExpect(status().isBadRequest());
        // 退赛缺生效场次
        mockMvc.perform(post("/api/races/V/teams/A/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"x","reason":"r","expectedVersion":1,"requestId":"wd"}
                                """))
                .andExpect(status().isBadRequest());
    }
}
