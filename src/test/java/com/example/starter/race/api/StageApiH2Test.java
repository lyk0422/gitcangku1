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
 * 阶段（STAGES）赛事 API 的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 校验状态码（201/200/400/409/422）、JSON 字段与封榜快照。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class StageApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 阶段赛事全链路_合成关门场景到封榜快照() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"stage-api","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());
        registerRunner("110", 1);
        registerRunner("111", 2);
        registerRunner("112", 3);

        // 配置两阶段（各一个检查点A）
        mockMvc.perform(post("/api/races/stage-api/stages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-stages","operator":"referee","expectedVersion":4,
                                 "stages":[
                                   {"stageNo":1,"checkpoints":[{"checkpointCode":"A","position":1}]},
                                   {"stageNo":2,"checkpoints":[{"checkpointCode":"A","position":1}]}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.stages[0].stageNo").value(1))
                .andExpect(jsonPath("$.stages[1].checkpoints[0].checkpointCode").value("A"));

        // 阶段1：110=101(F)、111=111(允许，关门线111.1)、112=112(OUT)
        submitStageTiming(1, "110", "t-s1-110", 101, 5, "req-s1-110");
        submitStageTiming(1, "111", "t-s1-111", 111, 6, "req-s1-111");
        submitStageTiming(1, "112", "t-s1-112", 112, 7, "req-s1-112");
        // 阶段2：三人同为200，均完整
        submitStageTiming(2, "110", "t-s2-110", 200, 8, "req-s2-110");
        submitStageTiming(2, "111", "t-s2-111", 200, 9, "req-s2-111");
        submitStageTiming(2, "112", "t-s2-112", 200, 10, "req-s2-112");

        mockMvc.perform(get("/api/races/stage-api/stages/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stages[0].fastestMs").value(101))
                .andExpect(jsonPath("$.stages[0].entries[0].status").value("COMPLETE"))
                .andExpect(jsonPath("$.stages[0].entries[1].status").value("COMPLETE"))
                .andExpect(jsonPath("$.stages[0].entries[2].status").value("OUT"));

        mockMvc.perform(get("/api/races/stage-api/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].bib").value("110"))
                .andExpect(jsonPath("$.entries[0].rank").value(1))
                .andExpect(jsonPath("$.entries[0].totalTimeMs").value(301))
                .andExpect(jsonPath("$.entries[1].bib").value("111"))
                .andExpect(jsonPath("$.entries[1].rank").value(2))
                .andExpect(jsonPath("$.entries[2].status").value("OUT"));

        // 最快者阶段1计时更正 101 -> 100：F 重算为100，111 失去资格变为 OUT
        mockMvc.perform(post("/api/races/stage-api/stages/1/timings/t-s1-110/revision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-revise","operator":"timer","elapsedMillis":100,
                                 "expectedVersion":11}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.elapsedMillis").value(100));

        mockMvc.perform(get("/api/races/stage-api/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].bib").value("110"))
                .andExpect(jsonPath("$.entries[0].status").value("RANKED"))
                .andExpect(jsonPath("$.entries[0].totalTimeMs").value(300))
                .andExpect(jsonPath("$.entries[1].status").value("OUT"))
                .andExpect(jsonPath("$.entries[2].status").value("OUT"));

        // 封榜：冻结每阶段F、资格与总成绩
        mockMvc.perform(post("/api/races/stage-api/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":12,"requestId":"req-seal"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.entries[0].bib").value("110"))
                .andExpect(jsonPath("$.entries[0].rank").value(1))
                .andExpect(jsonPath("$.entries[1].status").value("OUT"));

        // 封榜后阶段成绩来自只读快照
        mockMvc.perform(get("/api/races/stage-api/stages/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.stages[0].fastestMs").value(100))
                .andExpect(jsonPath("$.stages[0].entries[1].status").value("OUT"));
    }

    private void registerRunner(String bib, int expectedVersion) throws Exception {
        mockMvc.perform(post("/api/races/stage-api/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"%s","expectedVersion":%d,"requestId":"req-r-%s"}
                                """.formatted(bib, expectedVersion, bib)))
                .andExpect(status().isCreated());
    }

    private void submitStageTiming(
            int stageNo, String bib, String timingId, int elapsed, int version, String requestId)
            throws Exception {
        mockMvc.perform(post("/api/races/stage-api/stages/{stageNo}/runners/{bib}/timings",
                        stageNo, bib)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","operator":"timer","timingId":"%s",
                                 "checkpointCode":"A","elapsedMillis":%d,"expectedVersion":%d}
                                """.formatted(requestId, timingId, elapsed, version)))
                .andExpect(status().isCreated());
    }

    @Test
    void 操作者为空返回400_单日提交被拒_阶段无完整选手封榜422() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"stage-api-2","requestId":"req-create-2"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/stage-api-2/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","expectedVersion":1,"requestId":"req-ra"}
                                """))
                .andExpect(status().isCreated());

        // operator 为空 -> 400
        mockMvc.perform(post("/api/races/stage-api-2/stages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-st","operator":"","expectedVersion":2,
                                 "stages":[
                                   {"stageNo":1,"checkpoints":[{"checkpointCode":"A","position":1}]},
                                   {"stageNo":2,"checkpoints":[{"checkpointCode":"A","position":1}]}]}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/races/stage-api-2/stages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-st","operator":"referee","expectedVersion":2,
                                 "stages":[
                                   {"stageNo":1,"checkpoints":[{"checkpointCode":"A","position":1}]},
                                   {"stageNo":2,"checkpoints":[{"checkpointCode":"A","position":1}]}]}
                                """))
                .andExpect(status().isCreated());

        // 阶段赛事上单日分段提交 -> 409
        mockMvc.perform(post("/api/races/stage-api-2/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"t-x","checkpointCode":"A","elapsedMillis":100,
                                 "expectedVersion":3,"requestId":"req-tx"}
                                """))
                .andExpect(status().isConflict());

        // 阶段2无完整选手时封榜 -> 422
        mockMvc.perform(post("/api/races/stage-api-2/stages/1/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-t1","operator":"timer","timingId":"t-1",
                                 "checkpointCode":"A","elapsedMillis":100,"expectedVersion":3}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/stage-api-2/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":4,"requestId":"req-seal-2"}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }
}
