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
 * 中止恢复能力的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 覆盖中止登记、中止期写入拒绝、恢复重算、净值查询、补偿明细、
 * 事件历史、窗口拒绝与封榜冻结。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class RaceSuspensionApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 中止恢复到封榜全链路() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r","requestId":"c0"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":10000,"expectedVersion":1,"requestId":"c1"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b","finishTimeMs":20000,"expectedVersion":2,"requestId":"c2"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkpoints":[
                                  {"checkpointCode":"p1","position":1},
                                  {"checkpointCode":"p2","position":2}],
                                 "expectedVersion":3,"requestId":"c3"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"ta1","checkpointCode":"p1","elapsedMillis":1000,
                                 "expectedVersion":4,"requestId":"c4"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"ta2","checkpointCode":"p2","elapsedMillis":2000,
                                 "expectedVersion":5,"requestId":"c5"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r/runners/b/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"tb1","checkpointCode":"p1","elapsedMillis":1000,
                                 "expectedVersion":6,"requestId":"c6"}
                                """))
                .andExpect(status().isCreated());

        // 登记中止：201，赛事转 SUSPENDED
        mockMvc.perform(post("/api/races/r/suspensions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventKey":"e1","checkpointKey":"p2","startElapsedMs":4000,
                                 "expectedVersion":7,"requestId":"c7"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventKey").value("e1"))
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.startElapsedMs").value(4000));

        // 中止期间其它写操作 409
        mockMvc.perform(post("/api/races/r/runners/b/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"tbX","checkpointCode":"p2","elapsedMillis":6500,
                                 "expectedVersion":8,"requestId":"c8blocked"}
                                """))
                .andExpect(status().isConflict());

        // 恢复：200，事件转 RESUMED
        mockMvc.perform(post("/api/races/r/suspensions/e1/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resumeElapsedMs":6000,"expectedVersion":8,"requestId":"c8"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESUMED"))
                .andExpect(jsonPath("$.resumeElapsedMs").value(6000));

        // 恢复后提交 b 的 p2=6500（恢复点之后）
        mockMvc.perform(post("/api/races/r/runners/b/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"tb2","checkpointCode":"p2","elapsedMillis":6500,
                                 "expectedVersion":9,"requestId":"c9"}
                                """))
                .andExpect(status().isCreated());

        // 成绩榜：排名以净总耗时为准，原始值保留
        mockMvc.perform(get("/api/races/r/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].bib").value("a"))
                .andExpect(jsonPath("$.entries[0].netTotalTimeMs").value(10000))
                .andExpect(jsonPath("$.entries[1].bib").value("b"))
                .andExpect(jsonPath("$.entries[1].finishTimeMs").value(20000))
                .andExpect(jsonPath("$.entries[1].netFinishTimeMs").value(18000))
                .andExpect(jsonPath("$.entries[1].netTotalTimeMs").value(18000));

        // 单选手分段：原始与净值同时返回
        mockMvc.perform(get("/api/races/r/runners/b/timings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finishTimeMs").value(20000))
                .andExpect(jsonPath("$.netFinishTimeMs").value(18000))
                .andExpect(jsonPath("$.checkpoints[1].elapsedMillis").value(6500))
                .andExpect(jsonPath("$.checkpoints[1].netElapsedMillis").value(4500));

        // 补偿明细：a 已通过指定检查点补偿0，b 受影响补偿2000
        mockMvc.perform(get("/api/races/r/suspensions/e1/compensations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compensations[0].bib").value("a"))
                .andExpect(jsonPath("$.compensations[0].compensationMs").value(0))
                .andExpect(jsonPath("$.compensations[0].basis").value("PASSED_CHECKPOINT"))
                .andExpect(jsonPath("$.compensations[1].bib").value("b"))
                .andExpect(jsonPath("$.compensations[1].compensationMs").value(2000))
                .andExpect(jsonPath("$.compensations[1].basis").value("AFFECTED"));

        // 事件历史
        mockMvc.perform(get("/api/races/r/suspensions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].eventKey").value("e1"))
                .andExpect(jsonPath("$.events[0].status").value("RESUMED"))
                .andExpect(jsonPath("$.events[0].resumeElapsedMs").value(6000));

        // 落在窗口 [4000,6000) 内的完赛修订 422
        mockMvc.perform(post("/api/races/r/timing-revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b","finishTimeMs":5000,"expectedVersion":10,"requestId":"c10bad"}
                                """))
                .andExpect(status().isUnprocessableEntity());

        // 封榜：快照冻结原始值与净值
        mockMvc.perform(post("/api/races/r/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":10,"requestId":"c10"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/races/r/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[1].bib").value("b"))
                .andExpect(jsonPath("$.entries[1].finishTimeMs").value(20000))
                .andExpect(jsonPath("$.entries[1].netFinishTimeMs").value(18000))
                .andExpect(jsonPath("$.entries[1].netTotalTimeMs").value(18000));
        mockMvc.perform(get("/api/races/r/runners/b/timings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpoints[1].elapsedMillis").value(6500))
                .andExpect(jsonPath("$.checkpoints[1].netElapsedMillis").value(4500));
    }

    @Test
    void 恢复不变量违反返回422且不落库() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r2","requestId":"d0"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r2/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"d","finishTimeMs":10000,"expectedVersion":1,"requestId":"d1"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r2/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkpoints":[
                                  {"checkpointCode":"p1","position":1},
                                  {"checkpointCode":"p2","position":2}],
                                 "expectedVersion":2,"requestId":"d2"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r2/runners/d/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"td1","checkpointCode":"p1","elapsedMillis":1000,
                                 "expectedVersion":3,"requestId":"d3"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r2/runners/d/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"td2","checkpointCode":"p2","elapsedMillis":5000,
                                 "expectedVersion":4,"requestId":"d4"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r2/suspensions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventKey":"e1","checkpointKey":"p2","startElapsedMs":4000,
                                 "expectedVersion":5,"requestId":"d5"}
                                """))
                .andExpect(status().isCreated());

        // p2=5000 落在窗口 [4000,6000) 内：恢复 422，赛事保持 SUSPENDED
        mockMvc.perform(post("/api/races/r2/suspensions/e1/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resumeElapsedMs":6000,"expectedVersion":6,"requestId":"d6"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));
        mockMvc.perform(get("/api/races/r2/suspensions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(6))
                .andExpect(jsonPath("$.events[0].status").value("SUSPENDED"));
    }
}
