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
 * 团队计分能力的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 覆盖建队、实时计分、处罚影响、幂等换序重放、封榜同版本快照与 400/404/409。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class TeamApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 建队实时计分到封榜团队快照全链路() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"t","requestId":"t0"}
                                """))
                .andExpect(status().isCreated());

        // 6 名计时缺失选手登记，版本 1 -> 7。
        for (int i = 0; i < 6; i++) {
            String bib = String.valueOf((char) ('a' + i));
            int expected = i + 1;
            mockMvc.perform(post("/api/races/t/runners")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"bib":"%s","expectedVersion":%d,"requestId":"tr-%s"}
                                    """.formatted(bib, expected, bib)))
                    .andExpect(status().isCreated());
        }

        // 成员故意乱序提交，创建后成员按参赛号字典序返回，初始 INCOMPLETE。
        mockMvc.perform(post("/api/races/t/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamCode":"T1","memberBibs":["c","a","b"],
                                 "expectedVersion":7,"requestId":"tt1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.teamCode").value("T1"))
                .andExpect(jsonPath("$.status").value("INCOMPLETE"))
                .andExpect(jsonPath("$.rank").doesNotExist())
                .andExpect(jsonPath("$.members[0].bib").value("a"))
                .andExpect(jsonPath("$.members[0].scored").value(false));

        // 同键同参、集合换序重放：仍 201 且不重复建队。
        mockMvc.perform(post("/api/races/t/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamCode":"T1","memberBibs":["a","b","c"],
                                 "expectedVersion":7,"requestId":"tt1"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/races/t/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamCode":"T2","memberBibs":["d","e","f"],
                                 "expectedVersion":8,"requestId":"tt2"}
                                """))
                .andExpect(status().isCreated());

        // 修订计时，版本 9 -> 15。
        long[] times = {1000, 1100, 1200, 100, 200, 300};
        for (int i = 0; i < 6; i++) {
            String bib = String.valueOf((char) ('a' + i));
            int expected = 9 + i;
            mockMvc.perform(post("/api/races/t/timing-revisions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"bib":"%s","finishTimeMs":%d,"expectedVersion":%d,
                                     "requestId":"tv-%s"}
                                    """.formatted(bib, times[i], expected, bib)))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/races/t/teams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(15))
                .andExpect(jsonPath("$.teams[0].teamCode").value("T2"))
                .andExpect(jsonPath("$.teams[0].rank").value(1))
                .andExpect(jsonPath("$.teams[0].totalTimeMs").value(600))
                .andExpect(jsonPath("$.teams[1].teamCode").value("T1"))
                .andExpect(jsonPath("$.teams[1].rank").value(2))
                .andExpect(jsonPath("$.teams[1].totalTimeMs").value(3300));

        // d 加时50 -> T2=650。
        mockMvc.perform(post("/api/races/t/penalties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"penaltyId":"tpd","bib":"d","type":"ADD_TIME","amountMs":50,
                                 "expectedVersion":15,"requestId":"tpd"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/races/t/teams"))
                .andExpect(jsonPath("$.teams[0].totalTimeMs").value(650))
                .andExpect(jsonPath("$.teams[0].members[0].bib").value("d"))
                .andExpect(jsonPath("$.teams[0].members[0].totalTimeMs").value(150))
                .andExpect(jsonPath("$.teams[0].members[0].personalRank").value(1))
                .andExpect(jsonPath("$.teams[0].members[0].scored").value(true));

        // 封榜，版本 16 -> 17；团队与个人同版本固化。
        mockMvc.perform(post("/api/races/t/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":16,"requestId":"ts"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/races/t/teams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.version").value(17))
                .andExpect(jsonPath("$.sealedAt").exists())
                .andExpect(jsonPath("$.teams[0].teamCode").value("T2"))
                .andExpect(jsonPath("$.teams[0].totalTimeMs").value(650))
                .andExpect(jsonPath("$.teams[1].totalTimeMs").value(3300));

        // 封榜后建队 409。
        mockMvc.perform(post("/api/races/t/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamCode":"T3","memberBibs":["a","b","c"],
                                 "expectedVersion":17,"requestId":"tt3"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void 建队失败分支返回400_404_409() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"u","requestId":"u0"}
                                """))
                .andExpect(status().isCreated());
        for (int i = 0; i < 5; i++) {
            String bib = String.valueOf((char) ('a' + i));
            mockMvc.perform(post("/api/races/u/runners")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"bib":"%s","expectedVersion":%d,"requestId":"ur-%s"}
                                    """.formatted(bib, i + 1, bib)))
                    .andExpect(status().isCreated());
        }

        // 成员数不足3 -> 400。
        mockMvc.perform(post("/api/races/u/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamCode":"T1","memberBibs":["a","b"],
                                 "expectedVersion":6,"requestId":"ut-bad-size"}
                                """))
                .andExpect(status().isBadRequest());
        // 成员重复 -> 400。
        mockMvc.perform(post("/api/races/u/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamCode":"T1","memberBibs":["a","b","b"],
                                 "expectedVersion":6,"requestId":"ut-bad-dup"}
                                """))
                .andExpect(status().isBadRequest());
        // 成员未登记 -> 404。
        mockMvc.perform(post("/api/races/u/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamCode":"T1","memberBibs":["a","b","ghost"],
                                 "expectedVersion":6,"requestId":"ut-404"}
                                """))
                .andExpect(status().isNotFound());

        // 合法建队 a,b,c -> 版本7。
        mockMvc.perform(post("/api/races/u/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamCode":"T1","memberBibs":["a","b","c"],
                                 "expectedVersion":6,"requestId":"ut-t1"}
                                """))
                .andExpect(status().isCreated());

        // 成员跨队冲突（a 已在 T1）-> 409。
        mockMvc.perform(post("/api/races/u/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamCode":"T2","memberBibs":["a","d","e"],
                                 "expectedVersion":7,"requestId":"ut-t2"}
                                """))
                .andExpect(status().isConflict());
        // 同键异参 -> 409。
        mockMvc.perform(post("/api/races/u/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamCode":"OTHER","memberBibs":["a","b","c"],
                                 "expectedVersion":7,"requestId":"ut-t1"}
                                """))
                .andExpect(status().isConflict());
        // 版本不符（服务端在校验成员前先比对版本）-> 409。
        mockMvc.perform(post("/api/races/u/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamCode":"T2","memberBibs":["a","b","c"],
                                 "expectedVersion":6,"requestId":"ut-stale"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void 赛事出现完赛计时后建队冻结返回409且撤销处罚不开放() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"v","requestId":"v0"}
                                """))
                .andExpect(status().isCreated());
        // a 带完赛耗时登记，赛事即刻冻结团队配置。
        mockMvc.perform(post("/api/races/v/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":1000,"expectedVersion":1,"requestId":"vr-a"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/v/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b","expectedVersion":2,"requestId":"vr-b"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/v/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"c","expectedVersion":3,"requestId":"vr-c"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/races/v/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamCode":"T1","memberBibs":["a","b","c"],
                                 "expectedVersion":4,"requestId":"vt-frozen"}
                                """))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/races/v/teams"))
                .andExpect(jsonPath("$.teams").isEmpty());
    }
}
