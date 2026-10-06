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
 * 追逐赛 API 的端到端 HTTP 测试：题干合成用例全链路（真实 Spring MVC + Service + H2）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class PursuitApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 合成用例全链路_交会10秒并封榜冻结() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-pursuit-api","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("STANDARD"));

        mockMvc.perform(post("/api/races/race-pursuit-api/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","expectedVersion":1,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-pursuit-api/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b","expectedVersion":2,"requestId":"req-b"}
                                """))
                .andExpect(status().isCreated());

        // 启用 PURSUIT：A 起始0、B 起始1000毫米。
        mockMvc.perform(post("/api/races/race-pursuit-api/pursuit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pairs":[{"pairNo":1,"chaserBib":"a","chasedBib":"b",
                                           "chaserStartMm":0,"chasedStartMm":1000}],
                                 "expectedVersion":3,"requestId":"req-pursuit","operator":"op-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.pairs[0].chaserBib").value("a"));

        // 10秒时 A 前进3000、B 前进2000。
        mockMvc.perform(post("/api/races/race-pursuit-api/pursuit/trajectories/a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"samples":[{"timeMs":10000,"distanceMm":3000}],
                                 "trajectoryVersion":0,"expectedVersion":4,
                                 "requestId":"req-ta","operator":"op-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.trajectoryVersion").value(1));
        mockMvc.perform(post("/api/races/race-pursuit-api/pursuit/trajectories/b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"samples":[{"timeMs":10000,"distanceMm":2000}],
                                 "trajectoryVersion":0,"expectedVersion":5,
                                 "requestId":"req-tb","operator":"op-1"}
                                """))
                .andExpect(status().isCreated());

        // 裁定：最早交会 10 秒，精确有理数 10000/1。
        mockMvc.perform(post("/api/races/race-pursuit-api/pursuit/adjudications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pairNo":1,"chaserTrajectoryVersion":1,"chasedTrajectoryVersion":1,
                                 "expectedVersion":6,"requestId":"req-adj","operator":"op-judge"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.catchTimeNum").value(10000))
                .andExpect(jsonPath("$.catchTimeDen").value(1));

        // 同参重放：原样返回首次响应。
        mockMvc.perform(post("/api/races/race-pursuit-api/pursuit/adjudications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pairNo":1,"chaserTrajectoryVersion":1,"chasedTrajectoryVersion":1,
                                 "expectedVersion":6,"requestId":"req-adj","operator":"op-judge"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.catchTimeNum").value(10000));

        mockMvc.perform(post("/api/races/race-pursuit-api/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":7,"requestId":"req-seal"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"));

        // 封榜冻结：完整轨迹点与交会分数。
        mockMvc.perform(get("/api/races/race-pursuit-api/pursuit/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pairs[0].catchTimeNum").value(10000))
                .andExpect(jsonPath("$.pairs[0].catchTimeDen").value(1))
                .andExpect(jsonPath("$.samples.length()").value(2));
    }

    @Test
    void 未交会422且缺少操作者400() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-pursuit-422","requestId":"req-create-2"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-pursuit-422/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","expectedVersion":1,"requestId":"req-a2"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-pursuit-422/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b","expectedVersion":2,"requestId":"req-b2"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-pursuit-422/pursuit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pairs":[{"pairNo":1,"chaserBib":"a","chasedBib":"b",
                                           "chaserStartMm":0,"chasedStartMm":1000}],
                                 "expectedVersion":3,"requestId":"req-pursuit-2","operator":"op-1"}
                                """))
                .andExpect(status().isCreated());
        // A 仅前进2999：未交会。
        mockMvc.perform(post("/api/races/race-pursuit-422/pursuit/trajectories/a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"samples":[{"timeMs":10000,"distanceMm":2999}],
                                 "trajectoryVersion":0,"expectedVersion":4,
                                 "requestId":"req-ta2","operator":"op-1"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-pursuit-422/pursuit/trajectories/b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"samples":[{"timeMs":10000,"distanceMm":2000}],
                                 "trajectoryVersion":0,"expectedVersion":5,
                                 "requestId":"req-tb2","operator":"op-1"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/races/race-pursuit-422/pursuit/adjudications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pairNo":1,"chaserTrajectoryVersion":1,"chasedTrajectoryVersion":1,
                                 "expectedVersion":6,"requestId":"req-adj-2","operator":"op-judge"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));

        // 缺少操作者：400。
        mockMvc.perform(post("/api/races/race-pursuit-422/pursuit/adjudications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pairNo":1,"chaserTrajectoryVersion":1,"chasedTrajectoryVersion":1,
                                 "expectedVersion":6,"requestId":"req-adj-3"}
                                """))
                .andExpect(status().isBadRequest());
    }
}
