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
 * COUNTBACK 排名模式的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 覆盖模式变更接口、合成 A/B/C 破同分场景、封榜固化与计时后改模式409。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class RaceCountbackApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 合成场景全链路_B与C并列第一A第三() throws Exception {
        // 创建 COUNTBACK 赛事（v1）
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r","rankingMode":"COUNTBACK","requestId":"k0"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rankingMode").value("COUNTBACK"));

        // 单检查点 cp1（v2）
        mockMvc.perform(post("/api/races/r/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkpoints":[{"checkpointCode":"cp1","position":1}],
                                 "expectedVersion":1,"requestId":"k1"}
                                """))
                .andExpect(status().isCreated());

        // A/B/C 完赛均 100（v3~v5）
        registerRunner("A", "k2", 2);
        registerRunner("B", "k3", 3);
        registerRunner("C", "k4", 4);

        // A 累计40，B/C 累计60（v6~v8）
        submitTiming("A", "ta", 40, "k5", 5);
        submitTiming("B", "tb", 60, "k6", 6);
        submitTiming("C", "tc", 60, "k7", 7);

        // 总成绩均100：B/C 末段40并列第1，A 末段60为第3
        mockMvc.perform(get("/api/races/r/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rankingMode").value("COUNTBACK"))
                .andExpect(jsonPath("$.entries[0].bib").value("B"))
                .andExpect(jsonPath("$.entries[0].rank").value(1))
                .andExpect(jsonPath("$.entries[0].segmentMillisDesc[0]").value(40))
                .andExpect(jsonPath("$.entries[0].segmentMillisDesc[1]").value(60))
                .andExpect(jsonPath("$.entries[1].bib").value("C"))
                .andExpect(jsonPath("$.entries[1].rank").value(1))
                .andExpect(jsonPath("$.entries[2].bib").value("A"))
                .andExpect(jsonPath("$.entries[2].rank").value(3))
                .andExpect(jsonPath("$.entries[2].segmentMillisDesc[0]").value(60));

        // 计时后改模式 409
        mockMvc.perform(post("/api/races/r/ranking-mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rankingMode":"TOTAL_TIME","expectedVersion":8,"requestId":"k8"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));

        // 封榜（v9）：快照固化名次与段向量
        mockMvc.perform(post("/api/races/r/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":8,"requestId":"k9"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.rankingMode").value("COUNTBACK"))
                .andExpect(jsonPath("$.entries[0].bib").value("B"))
                .andExpect(jsonPath("$.entries[2].rank").value(3));

        mockMvc.perform(get("/api/races/r/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].segmentMillisDesc[0]").value(40))
                .andExpect(jsonPath("$.entries[2].segmentMillisDesc[0]").value(60));
    }

    @Test
    void 尚无计时可改模式_同键重放异参409() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"m","requestId":"m0"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rankingMode").value("TOTAL_TIME"));

        // 改为 COUNTBACK，版本 1->2
        mockMvc.perform(post("/api/races/m/ranking-mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rankingMode":"COUNTBACK","expectedVersion":1,"requestId":"m1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.rankingMode").value("COUNTBACK"));

        // 同键同参重放：仍返回首次结果，版本不再推进
        mockMvc.perform(post("/api/races/m/ranking-mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rankingMode":"COUNTBACK","expectedVersion":1,"requestId":"m1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        // 同键异参 409
        mockMvc.perform(post("/api/races/m/ranking-mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rankingMode":"TOTAL_TIME","expectedVersion":2,"requestId":"m1"}
                                """))
                .andExpect(status().isConflict());

        // 非法模式 400
        mockMvc.perform(post("/api/races/m/ranking-mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rankingMode":"FASTEST","expectedVersion":2,"requestId":"m2"}
                                """))
                .andExpect(status().isBadRequest());
    }

    private void registerRunner(String bib, String reqId, int expectedVersion) throws Exception {
        mockMvc.perform(post("/api/races/r/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"%s","finishTimeMs":100,"expectedVersion":%d,"requestId":"%s"}
                                """.formatted(bib, expectedVersion, reqId)))
                .andExpect(status().isCreated());
    }

    private void submitTiming(String bib, String timingId, long elapsed,
                              String reqId, int expectedVersion) throws Exception {
        mockMvc.perform(post("/api/races/r/runners/" + bib + "/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"%s","checkpointCode":"cp1","elapsedMillis":%d,
                                 "expectedVersion":%d,"requestId":"%s"}
                                """.formatted(timingId, elapsed, expectedVersion, reqId)))
                .andExpect(status().isCreated());
    }
}
