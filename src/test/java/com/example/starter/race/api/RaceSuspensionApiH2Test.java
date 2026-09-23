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
 * 分组中止恢复能力的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 覆盖中止登记、中止中写拒绝、恢复重算、净计时查询、事件历史、补偿明细与422回滚。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class RaceSuspensionApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 中止恢复到净计时排名全链路() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r","requestId":"c0"}
                                """))
                .andExpect(status().isCreated());
        // ahead/behind 完赛均为 20000（版本到3）
        mockMvc.perform(post("/api/races/r/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"ahead","finishTimeMs":20000,"expectedVersion":1,"requestId":"c1"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"behind","finishTimeMs":20000,"expectedVersion":2,"requestId":"c2"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkpoints":[
                                  {"checkpointCode":"k1","position":1},
                                  {"checkpointCode":"k2","position":2}],
                                 "expectedVersion":3,"requestId":"c3"}
                                """))
                .andExpect(status().isCreated());
        // ahead 中止前已过 k1；behind 的 k1 在恢复点之后（版本到6）
        mockMvc.perform(post("/api/races/r/runners/ahead/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"ta1","checkpointCode":"k1","elapsedMillis":3000,
                                 "expectedVersion":4,"requestId":"c4"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r/runners/behind/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"tb1","checkpointCode":"k1","elapsedMillis":11000,
                                 "expectedVersion":5,"requestId":"c5"}
                                """))
                .andExpect(status().isCreated());

        // v7: 登记中止 -> 201，赛事 SUSPENDED
        mockMvc.perform(post("/api/races/r/suspensions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventKey":"e1","checkpointKey":"k1","startElapsedMs":6000,
                                 "expectedVersion":6,"requestId":"c6"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventKey").value("e1"))
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.startElapsedMs").value(6000));

        // 中止中写操作 409
        mockMvc.perform(post("/api/races/r/runners/behind/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"tb2x","checkpointCode":"k2","elapsedMillis":16000,
                                 "expectedVersion":7,"requestId":"c7x"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));

        // v8: 恢复 -> 200，中止时长 4000
        mockMvc.perform(post("/api/races/r/suspensions/e1/resumption")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resumeElapsedMs":10000,"expectedVersion":7,"requestId":"c7"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESUMED"))
                .andExpect(jsonPath("$.durationMs").value(4000))
                .andExpect(jsonPath("$.resumeElapsedMs").value(10000));

        // behind 补 k2=16000（恢复点之后，合法）
        mockMvc.perform(post("/api/races/r/runners/behind/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"tb2","checkpointCode":"k2","elapsedMillis":16000,
                                 "expectedVersion":8,"requestId":"c8"}
                                """))
                .andExpect(status().isCreated());

        // 落窗记录 422
        mockMvc.perform(post("/api/races/r/runners/ahead/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"ta2bad","checkpointCode":"k2","elapsedMillis":7000,
                                 "expectedVersion":9,"requestId":"c9bad"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));

        // 净计时排名：behind 净 16000 反超 ahead 净 20000
        mockMvc.perform(get("/api/races/r/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].bib").value("behind"))
                .andExpect(jsonPath("$.entries[0].rank").value(1))
                .andExpect(jsonPath("$.entries[0].finishTimeMs").value(20000))
                .andExpect(jsonPath("$.entries[0].netFinishTimeMs").value(16000))
                .andExpect(jsonPath("$.entries[0].totalTimeMs").value(16000))
                .andExpect(jsonPath("$.entries[1].bib").value("ahead"))
                .andExpect(jsonPath("$.entries[1].netFinishTimeMs").value(20000));

        // 单选手分段含原始/净值
        mockMvc.perform(get("/api/races/r/runners/behind/timings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finishTimeMs").value(20000))
                .andExpect(jsonPath("$.netFinishTimeMs").value(16000))
                .andExpect(jsonPath("$.checkpoints[0].elapsedMillis").value(11000))
                .andExpect(jsonPath("$.checkpoints[0].netElapsedMillis").value(7000))
                .andExpect(jsonPath("$.checkpoints[1].elapsedMillis").value(16000))
                .andExpect(jsonPath("$.checkpoints[1].netElapsedMillis").value(12000));

        // 事件历史只读
        mockMvc.perform(get("/api/races/r/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].eventKey").value("e1"))
                .andExpect(jsonPath("$.events[0].status").value("RESUMED"))
                .andExpect(jsonPath("$.events[0].durationMs").value(4000));

        // 补偿明细：behind 受影响 4000，ahead 补偿0
        mockMvc.perform(get("/api/races/r/compensations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runners[0].bib").value("ahead"))
                .andExpect(jsonPath("$.runners[0].totalCompensationMs").value(0))
                .andExpect(jsonPath("$.runners[1].bib").value("behind"))
                .andExpect(jsonPath("$.runners[1].totalCompensationMs").value(4000))
                .andExpect(jsonPath("$.runners[1].entries[0].eventKey").value("e1"))
                .andExpect(jsonPath("$.runners[1].entries[0].affected").value(true));

        // v10: 封榜，快照冻结净值
        mockMvc.perform(post("/api/races/r/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":9,"requestId":"c9"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.entries[0].bib").value("behind"))
                .andExpect(jsonPath("$.entries[0].netFinishTimeMs").value(16000));

        // 封榜后事件历史与快照仍只读可查
        mockMvc.perform(get("/api/races/r/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].eventKey").value("e1"));
        mockMvc.perform(get("/api/races/r/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].netFinishTimeMs").value(16000));
    }

    @Test
    void 恢复落窗返回422且事件不落库() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r2","requestId":"d0"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r2/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":20000,"expectedVersion":1,"requestId":"d1"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r2/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkpoints":[{"checkpointCode":"k1","position":1}],
                                 "expectedVersion":2,"requestId":"d2"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r2/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"t1","checkpointCode":"k1","elapsedMillis":7000,
                                 "expectedVersion":3,"requestId":"d3"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r2/suspensions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventKey":"e1","checkpointKey":"k1","startElapsedMs":6000,
                                 "expectedVersion":4,"requestId":"d4"}
                                """))
                .andExpect(status().isCreated());

        // 分段 7000 落在 [6000,10000) -> 422，事件保持 SUSPENDED
        mockMvc.perform(post("/api/races/r2/suspensions/e1/resumption")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resumeElapsedMs":10000,"expectedVersion":5,"requestId":"d5"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));
        mockMvc.perform(get("/api/races/r2/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].status").value("SUSPENDED"));

        // 修正恢复点使记录不落窗 -> 200
        mockMvc.perform(post("/api/races/r2/suspensions/e1/resumption")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resumeElapsedMs":6500,"expectedVersion":5,"requestId":"d5"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.durationMs").value(500));
        mockMvc.perform(get("/api/races/r2/runners/a/timings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpoints[0].netElapsedMillis").value(6500));
    }
}
