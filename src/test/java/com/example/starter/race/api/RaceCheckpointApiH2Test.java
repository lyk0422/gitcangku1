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
 * 分段计时能力的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 覆盖配置、乱序提交、422 相邻约束、漏点排名、只读查询与封榜固化。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class RaceCheckpointApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 分段配置提交漏点到封榜全链路() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r","requestId":"c0"}
                                """))
                .andExpect(status().isCreated());

        // 两个选手：a 完赛 5000，b 完赛 9000（版本到3）
        mockMvc.perform(post("/api/races/r/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":5000,"expectedVersion":1,"requestId":"c1"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b","finishTimeMs":9000,"expectedVersion":2,"requestId":"c2"}
                                """))
                .andExpect(status().isCreated());

        // 配置3个检查点（顺序故意打乱，服务端按 position 返回）
        mockMvc.perform(post("/api/races/r/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkpoints":[
                                  {"checkpointCode":"p1","position":1},
                                  {"checkpointCode":"p3","position":3},
                                  {"checkpointCode":"p2","position":2}],
                                 "expectedVersion":3,"requestId":"c3"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.checkpoints[0].checkpointCode").value("p1"))
                .andExpect(jsonPath("$.checkpoints[2].checkpointCode").value("p3"));

        // 乱序提交：b 先 p3 再 p1
        mockMvc.perform(post("/api/races/r/runners/b/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"tb3","checkpointCode":"p3","elapsedMillis":800,
                                 "expectedVersion":4,"requestId":"c4"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.position").value(3))
                .andExpect(jsonPath("$.elapsedMillis").value(800));
        mockMvc.perform(post("/api/races/r/runners/b/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"tb1","checkpointCode":"p1","elapsedMillis":100,
                                 "expectedVersion":5,"requestId":"c5"}
                                """))
                .andExpect(status().isCreated());

        // 相邻约束违反 422：p2=900 不严格小于已有 p3=800
        mockMvc.perform(post("/api/races/r/runners/b/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"tb2bad","checkpointCode":"p2","elapsedMillis":900,
                                 "expectedVersion":6,"requestId":"c6bad"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));

        // 合法 p2=450
        mockMvc.perform(post("/api/races/r/runners/b/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"tb2","checkpointCode":"p2","elapsedMillis":450,
                                 "expectedVersion":6,"requestId":"c6"}
                                """))
                .andExpect(status().isCreated());

        // a 只过 p1，漏 p2/p3：即使完赛更快也 MISSING_CHECKPOINT 不排名
        mockMvc.perform(post("/api/races/r/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"ta1","checkpointCode":"p1","elapsedMillis":100,
                                 "expectedVersion":7,"requestId":"c7"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/races/r/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(8))
                .andExpect(jsonPath("$.entries[0].bib").value("b"))
                .andExpect(jsonPath("$.entries[0].status").value("RANKED"))
                .andExpect(jsonPath("$.entries[0].rank").value(1))
                .andExpect(jsonPath("$.entries[1].bib").value("a"))
                .andExpect(jsonPath("$.entries[1].status").value("MISSING_CHECKPOINT"))
                .andExpect(jsonPath("$.entries[1].rank").doesNotExist())
                .andExpect(jsonPath("$.entries[1].missingCheckpoints[0]").value("p2"))
                .andExpect(jsonPath("$.entries[1].missingCheckpoints[1]").value("p3"));

        // 单选手分段查询（顺序稳定，缺项 elapsedMillis 缺省）
        mockMvc.perform(get("/api/races/r/runners/a/timings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpoints.length()").value(3))
                .andExpect(jsonPath("$.checkpoints[0].checkpointCode").value("p1"))
                .andExpect(jsonPath("$.checkpoints[0].elapsedMillis").value(100))
                .andExpect(jsonPath("$.checkpoints[1].elapsedMillis").doesNotExist());

        // 缺失检查点汇总
        mockMvc.perform(get("/api/races/r/missing-checkpoints"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpointCount").value(3))
                .andExpect(jsonPath("$.runners[0].bib").value("a"))
                .andExpect(jsonPath("$.runners[0].missingCheckpoints.length()").value(2))
                .andExpect(jsonPath("$.runners[1].bib").value("b"))
                .andExpect(jsonPath("$.runners[1].missingCheckpoints.length()").value(0));

        // 封榜（expectedVersion=8 -> 9）
        mockMvc.perform(post("/api/races/r/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":8,"requestId":"c8"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.version").value(9));

        // 封榜后分段查询走快照，仍保留 a 缺失 p2/p3
        mockMvc.perform(get("/api/races/r/runners/a/timings"))
                .andExpect(jsonPath("$.checkpoints[2].elapsedMillis").doesNotExist());
        mockMvc.perform(get("/api/races/r/missing-checkpoints"))
                .andExpect(jsonPath("$.runners[0].missingCheckpoints.length()").value(2));

        // 封榜后新增分段 409
        mockMvc.perform(post("/api/races/r/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"late","checkpointCode":"p2","elapsedMillis":200,
                                 "expectedVersion":9,"requestId":"c9"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void timingId异参返回409且配置校验400() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r2","requestId":"d0"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r2/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":9000,"expectedVersion":1,"requestId":"d1"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r2/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkpoints":[{"checkpointCode":"p1","position":1}],
                                 "expectedVersion":2,"requestId":"d2"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r2/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"dup","checkpointCode":"p1","elapsedMillis":100,
                                 "expectedVersion":3,"requestId":"d3"}
                                """))
                .andExpect(status().isCreated());
        // 同 timingId 不同参数 -> 409
        mockMvc.perform(post("/api/races/r2/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"dup","checkpointCode":"p1","elapsedMillis":200,
                                 "expectedVersion":3,"requestId":"d4"}
                                """))
                .andExpect(status().isConflict());

        // position 非连续 -> 400
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r3","requestId":"e0"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r3/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkpoints":[
                                  {"checkpointCode":"x","position":1},
                                  {"checkpointCode":"y","position":3}],
                                 "expectedVersion":1,"requestId":"e1"}
                                """))
                .andExpect(status().isBadRequest());
    }
}
