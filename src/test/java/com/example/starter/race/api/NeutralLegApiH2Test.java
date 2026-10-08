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
 * 中立赛段模式的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 覆盖启用模式、合成样例净值、分段更正与校验失败状态码。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class NeutralLegApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 中立模式启用计时更正全链路() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"rn","requestId":"n0"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/rn/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":100,"expectedVersion":1,"requestId":"n1"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/rn/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkpoints":[
                                  {"checkpointCode":"cp1","position":1},
                                  {"checkpointCode":"cp2","position":2}],
                                 "expectedVersion":2,"requestId":"n2"}
                                """))
                .andExpect(status().isCreated());

        // 启用中立赛段模式：第一至第二点中立（版本到4）
        mockMvc.perform(post("/api/races/rn/neutral-legs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"legs":[{"startPosition":1,"endPosition":2}],
                                 "expectedVersion":3,"requestId":"n3","operator":"referee"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.legs[0].startPosition").value(1))
                .andExpect(jsonPath("$.legs[0].endPosition").value(2));

        // 重复启用 -> 409
        mockMvc.perform(post("/api/races/rn/neutral-legs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"legs":[{"startPosition":0,"endPosition":1}],
                                 "expectedVersion":4,"requestId":"n3b","operator":"referee"}
                                """))
                .andExpect(status().isConflict());

        // 累计点 20、70（版本到6）
        mockMvc.perform(post("/api/races/rn/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"t1","checkpointCode":"cp1","elapsedMillis":20,
                                 "expectedVersion":4,"requestId":"n4"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/rn/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"t2","checkpointCode":"cp2","elapsedMillis":70,
                                 "expectedVersion":5,"requestId":"n5"}
                                """))
                .andExpect(status().isCreated());

        // 净竞技用时 = 100 - (70-20) = 50
        mockMvc.perform(get("/api/races/rn/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].neutralDeductionMs").value(50))
                .andExpect(jsonPath("$.entries[0].netTimeMs").value(50))
                .andExpect(jsonPath("$.entries[0].totalTimeMs").value(100));

        // 第二点更正为 60 -> 净用时 60
        mockMvc.perform(post("/api/races/rn/timing-corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","checkpointCode":"cp2","elapsedMillis":60,
                                 "expectedVersion":6,"requestId":"n6","operator":"referee"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.elapsedMillis").value(60));
        mockMvc.perform(get("/api/races/rn/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].neutralDeductionMs").value(40))
                .andExpect(jsonPath("$.entries[0].netTimeMs").value(60));

        // 中立区间查询
        mockMvc.perform(get("/api/races/rn/neutral-legs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legs[0].legIndex").value(1));
    }

    @Test
    void 中立配置与更正的校验失败状态码() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"rb","requestId":"b0"}
                                """))
                .andExpect(status().isCreated());
        // 未配置检查点即启用 -> 400
        mockMvc.perform(post("/api/races/rb/neutral-legs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"legs":[{"startPosition":0,"endPosition":1}],
                                 "expectedVersion":1,"requestId":"b1","operator":"referee"}
                                """))
                .andExpect(status().isBadRequest());
        // 缺少操作者 -> 400
        mockMvc.perform(post("/api/races/rb/neutral-legs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"legs":[{"startPosition":0,"endPosition":1}],
                                 "expectedVersion":1,"requestId":"b1x"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/races/rb/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkpoints":[{"checkpointCode":"cp1","position":1}],
                                 "expectedVersion":1,"requestId":"b2"}
                                """))
                .andExpect(status().isCreated());
        // 交叠区间 -> 400
        mockMvc.perform(post("/api/races/rb/neutral-legs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"legs":[{"startPosition":0,"endPosition":1},
                                         {"startPosition":0,"endPosition":1}],
                                 "expectedVersion":2,"requestId":"b3","operator":"referee"}
                                """))
                .andExpect(status().isBadRequest());
        // 更正不存在的分段 -> 404
        mockMvc.perform(post("/api/races/rb/timing-corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"ghost","checkpointCode":"cp1","elapsedMillis":10,
                                 "expectedVersion":2,"requestId":"b4","operator":"referee"}
                                """))
                .andExpect(status().isNotFound());
    }
}
