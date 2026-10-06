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
 * 排名模式变更与 COUNTBACK 榜单的端到端 HTTP 测试：
 * 真实 Spring MVC + Service + H2，校验状态码与 JSON 字段。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class RaceRankingModeApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 合成场景全链路_COUNTBACK榜单与封榜快照() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"raceId\":\"race-cb\",\"requestId\":\"req-create\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rankingMode").value("NORMAL"));

        mockMvc.perform(post("/api/races/race-cb/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkpoints":[{"checkpointCode":"cp1","position":1},
                                 {"checkpointCode":"cp2","position":2}],
                                 "expectedVersion":1,"requestId":"req-cp"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/races/race-cb/ranking-mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rankingMode":"COUNTBACK","expectedVersion":2,
                                 "operator":"official-1","requestId":"req-mode"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rankingMode").value("COUNTBACK"))
                .andExpect(jsonPath("$.version").value(3));

        registerRunner("a", 3);
        registerRunner("b", 4);
        registerRunner("c", 5);
        submitTiming("a", "cp1", 40, 6);
        submitTiming("a", "cp2", 99, 7);
        submitTiming("b", "cp1", 60, 8);
        submitTiming("b", "cp2", 99, 9);
        submitTiming("c", "cp1", 60, 10);
        submitTiming("c", "cp2", 99, 11);

        // 总成绩均100；最后段 b/c=39 优于 a=59，b 与 c 并列第1，a 为3
        mockMvc.perform(get("/api/races/race-cb/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].bib").value("b"))
                .andExpect(jsonPath("$.entries[0].rank").value(1))
                .andExpect(jsonPath("$.entries[0].segmentMillisDesc[0]").value(39))
                .andExpect(jsonPath("$.entries[0].segmentMillisDesc[1]").value(60))
                .andExpect(jsonPath("$.entries[1].bib").value("c"))
                .andExpect(jsonPath("$.entries[1].rank").value(1))
                .andExpect(jsonPath("$.entries[2].bib").value("a"))
                .andExpect(jsonPath("$.entries[2].rank").value(3))
                .andExpect(jsonPath("$.entries[2].totalTimeMs").value(100));

        mockMvc.perform(post("/api/races/race-cb/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":12,\"requestId\":\"req-seal\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"));

        mockMvc.perform(get("/api/races/race-cb/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].bib").value("b"))
                .andExpect(jsonPath("$.entries[0].segmentMillisDesc[0]").value(39))
                .andExpect(jsonPath("$.entries[2].rank").value(3));
    }

    @Test
    void 模式变更错误分支_400与409() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"raceId\":\"race-mode-err\",\"requestId\":\"req-create\"}"))
                .andExpect(status().isCreated());

        // 空操作者 400
        mockMvc.perform(post("/api/races/race-mode-err/ranking-mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rankingMode":"COUNTBACK","expectedVersion":1,
                                 "operator":" ","requestId":"req-m1"}
                                """))
                .andExpect(status().isBadRequest());
        // 未知模式 400
        mockMvc.perform(post("/api/races/race-mode-err/ranking-mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rankingMode":"FANCY","expectedVersion":1,
                                 "operator":"op","requestId":"req-m2"}
                                """))
                .andExpect(status().isBadRequest());
        // 版本冲突 409
        mockMvc.perform(post("/api/races/race-mode-err/ranking-mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rankingMode":"COUNTBACK","expectedVersion":9,
                                 "operator":"op","requestId":"req-m3"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
        // 赛事不存在 404
        mockMvc.perform(post("/api/races/ghost/ranking-mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rankingMode":"COUNTBACK","expectedVersion":1,
                                 "operator":"op","requestId":"req-m4"}
                                """))
                .andExpect(status().isNotFound());
    }

    private void registerRunner(String bib, int expectedVersion) throws Exception {
        mockMvc.perform(post("/api/races/race-cb/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bib\":\"" + bib + "\",\"finishTimeMs\":100,"
                                + "\"expectedVersion\":" + expectedVersion
                                + ",\"requestId\":\"req-reg-" + bib + "\"}"))
                .andExpect(status().isCreated());
    }

    private void submitTiming(String bib, String code, int elapsed, int expectedVersion)
            throws Exception {
        mockMvc.perform(post("/api/races/race-cb/runners/" + bib + "/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timingId\":\"t-" + bib + "-" + code
                                + "\",\"checkpointCode\":\"" + code + "\",\"elapsedMillis\":"
                                + elapsed + ",\"expectedVersion\":" + expectedVersion
                                + ",\"requestId\":\"req-t-" + bib + "-" + code + "\"}"))
                .andExpect(status().isCreated());
    }
}
