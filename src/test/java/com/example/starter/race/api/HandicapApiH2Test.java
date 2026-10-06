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
 * 让分赛 API 端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 覆盖合成场景（展示相同不并列）、幂等重放与封榜后只读。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class HandicapApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 让分赛全链路_展示相同不并列_封榜后只读() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-hc","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scoringMode").value("STANDARD"));

        mockMvc.perform(post("/api/races/race-hc/scoring-mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"HANDICAP","expectedVersion":1,"requestId":"req-mode"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scoringMode").value("HANDICAP"))
                .andExpect(jsonPath("$.version").value(2));

        // 合成数据：A 原时1000、B 原时999
        mockMvc.perform(post("/api/races/race-hc/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"A","finishTimeMs":1000,"expectedVersion":2,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-hc/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"B","finishTimeMs":999,"expectedVersion":3,"requestId":"req-b"}
                                """))
                .andExpect(status().isCreated());

        // 系数均1/3（B 以等比例 2/6 登记，响应为约分后的 1/3）
        mockMvc.perform(post("/api/races/race-hc/runners/A/handicap-factor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"p":1,"q":3,"expectedVersion":4,"requestId":"req-fa"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.p").value(1))
                .andExpect(jsonPath("$.q").value(3))
                .andExpect(jsonPath("$.version").value(5));
        mockMvc.perform(post("/api/races/race-hc/runners/B/handicap-factor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"p":2,"q":6,"expectedVersion":5,"requestId":"req-fb"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.p").value(1))
                .andExpect(jsonPath("$.q").value(3));

        // 展示均333毫秒但 B 先 A 后；精确分子分母可见
        mockMvc.perform(get("/api/races/race-hc/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scoringMode").value("HANDICAP"))
                .andExpect(jsonPath("$.entries[0].bib").value("B"))
                .andExpect(jsonPath("$.entries[0].rank").value(1))
                .andExpect(jsonPath("$.entries[0].totalTimeMs").value(333))
                .andExpect(jsonPath("$.entries[0].scoreNumerator").value(333))
                .andExpect(jsonPath("$.entries[0].scoreDenominator").value(1))
                .andExpect(jsonPath("$.entries[1].bib").value("A"))
                .andExpect(jsonPath("$.entries[1].rank").value(2))
                .andExpect(jsonPath("$.entries[1].totalTimeMs").value(333))
                .andExpect(jsonPath("$.entries[1].scoreNumerator").value(1000))
                .andExpect(jsonPath("$.entries[1].scoreDenominator").value(3));

        // 同键同参重放首次完整响应
        mockMvc.perform(post("/api/races/race-hc/runners/A/handicap-factor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"p":1,"q":3,"expectedVersion":4,"requestId":"req-fa"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(5));
        // 同键异参 -> 409
        mockMvc.perform(post("/api/races/race-hc/runners/A/handicap-factor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"p":1,"q":2,"expectedVersion":4,"requestId":"req-fa"}
                                """))
                .andExpect(status().isConflict());

        // 封榜后修改系数 -> 409，快照只读
        mockMvc.perform(post("/api/races/race-hc/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":6,"requestId":"req-seal"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.scoringMode").value("HANDICAP"))
                .andExpect(jsonPath("$.entries[0].bib").value("B"));

        mockMvc.perform(post("/api/races/race-hc/runners/A/handicap-factor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"p":1,"q":2,"expectedVersion":7,"requestId":"req-fa2"}
                                """))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/races/race-hc/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].bib").value("B"))
                .andExpect(jsonPath("$.entries[0].factorP").value(1))
                .andExpect(jsonPath("$.entries[0].factorQ").value(3))
                .andExpect(jsonPath("$.entries[1].scoreNumerator").value(1000));
    }

    @Test
    void 非法系数与缺系数状态() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-hc2","requestId":"req-create2"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-hc2/scoring-mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"HANDICAP","expectedVersion":1,"requestId":"req-mode2"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/races/race-hc2/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":500,"expectedVersion":2,"requestId":"req-a2"}
                                """))
                .andExpect(status().isCreated());

        // 系数越界 -> 400
        mockMvc.perform(post("/api/races/race-hc2/runners/a/handicap-factor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"p":0,"q":3,"expectedVersion":3,"requestId":"req-f0"}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/races/race-hc2/runners/a/handicap-factor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"p":1,"q":1001,"expectedVersion":3,"requestId":"req-f1001"}
                                """))
                .andExpect(status().isBadRequest());

        // 缺系数不排名，状态 MISSING_FACTOR
        mockMvc.perform(get("/api/races/race-hc2/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].status").value("MISSING_FACTOR"))
                .andExpect(jsonPath("$.entries[0].rank").doesNotExist());
    }
}
