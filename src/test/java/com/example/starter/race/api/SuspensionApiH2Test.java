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
 * 覆盖中止/恢复状态机、净值重算、422整体回滚、事件历史、补偿明细与封榜冻结。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class SuspensionApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 中止恢复净值重算到封榜全链路() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"s","requestId":"q0"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/s/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":4000,"expectedVersion":1,"requestId":"q1"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/s/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkpoints":[
                                  {"checkpointCode":"c1","position":1},
                                  {"checkpointCode":"c2","position":2},
                                  {"checkpointCode":"c3","position":3}],
                                 "expectedVersion":2,"requestId":"q2"}
                                """))
                .andExpect(status().isCreated());

        // 中止前 a 仅过 c1@500
        mockMvc.perform(post("/api/races/s/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"a1","checkpointCode":"c1","elapsedMillis":500,
                                 "expectedVersion":3,"requestId":"q3"}
                                """))
                .andExpect(status().isCreated());

        // 中止：起始检查点 c2，start=1000（v5）
        mockMvc.perform(post("/api/races/s/suspensions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventKey":"ev","checkpointKey":"c2","startElapsedMs":1000,
                                 "expectedVersion":4,"requestId":"q4"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.versionAfterSuspend").value(5));

        // 中止期间提交分段 -> 409
        mockMvc.perform(post("/api/races/s/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"a2","checkpointCode":"c2","elapsedMillis":2500,
                                 "expectedVersion":5,"requestId":"q5"}
                                """))
                .andExpect(status().isConflict());

        // resume<=start -> 400
        mockMvc.perform(post("/api/races/s/resumptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventKey":"ev","resumeElapsedMs":1000,
                                 "expectedVersion":5,"requestId":"q6bad"}
                                """))
                .andExpect(status().isBadRequest());

        // 恢复 resume=2000（v6）
        mockMvc.perform(post("/api/races/s/resumptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventKey":"ev","resumeElapsedMs":2000,
                                 "expectedVersion":5,"requestId":"q6"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESUMED"))
                .andExpect(jsonPath("$.durationMs").value(1000))
                .andExpect(jsonPath("$.versionAfterResume").value(6));

        // 恢复后 c2@2500 -> 净1500（v7）
        mockMvc.perform(post("/api/races/s/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"a2","checkpointCode":"c2","elapsedMillis":2500,
                                 "expectedVersion":6,"requestId":"q7"}
                                """))
                .andExpect(status().isCreated());
        // c3@3500 -> 净2500（v8）
        mockMvc.perform(post("/api/races/s/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"a3","checkpointCode":"c3","elapsedMillis":3500,
                                 "expectedVersion":7,"requestId":"q8"}
                                """))
                .andExpect(status().isCreated());

        // 榜单：净完赛3000
        mockMvc.perform(get("/api/races/s/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(8))
                .andExpect(jsonPath("$.entries[0].bib").value("a"))
                .andExpect(jsonPath("$.entries[0].netFinishTimeMs").value(3000))
                .andExpect(jsonPath("$.entries[0].finishCompensationMs").value(1000))
                .andExpect(jsonPath("$.entries[0].totalTimeMs").value(3000));

        // 单选手净分段
        mockMvc.perform(get("/api/races/s/runners/a/timings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finishTimeMs").value(4000))
                .andExpect(jsonPath("$.netFinishTimeMs").value(3000))
                .andExpect(jsonPath("$.checkpoints[1].elapsedMillis").value(2500))
                .andExpect(jsonPath("$.checkpoints[1].netElapsedMs").value(1500))
                .andExpect(jsonPath("$.checkpoints[1].compensationMs").value(1000));

        // 补偿明细
        mockMvc.perform(get("/api/races/s/compensations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runners[0].bib").value("a"))
                .andExpect(jsonPath("$.runners[0].affected").value(true))
                .andExpect(jsonPath("$.runners[0].netFinishTimeMs").value(3000))
                .andExpect(jsonPath("$.runners[0].checkpoints[2].netElapsedMs").value(2500));

        // 事件历史
        mockMvc.perform(get("/api/races/s/suspensions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].eventKey").value("ev"))
                .andExpect(jsonPath("$.events[0].status").value("RESUMED"));

        // 封榜冻结（v9）
        mockMvc.perform(post("/api/races/s/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":8,"requestId":"q9"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.version").value(9));

        // 封榜后补偿查询仍返回冻结净值
        mockMvc.perform(get("/api/races/s/compensations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runners[0].netFinishTimeMs").value(3000));
    }

    @Test
    void 恢复422整体回滚后赛事仍中止且事件不落库() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"s2","requestId":"p0"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/s2/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":4000,"expectedVersion":1,"requestId":"p1"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/s2/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkpoints":[
                                  {"checkpointCode":"c1","position":1},
                                  {"checkpointCode":"c2","position":2}],
                                 "expectedVersion":2,"requestId":"p2"}
                                """))
                .andExpect(status().isCreated());
        // 中止登记前已存在落在未来窗口 [1000,2000) 的 c1@1500
        mockMvc.perform(post("/api/races/s2/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"a1","checkpointCode":"c1","elapsedMillis":1500,
                                 "expectedVersion":3,"requestId":"p3"}
                                """))
                .andExpect(status().isCreated());
        // 中止（v5）
        mockMvc.perform(post("/api/races/s2/suspensions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventKey":"ev","checkpointKey":"c2","startElapsedMs":1000,
                                 "expectedVersion":4,"requestId":"p4"}
                                """))
                .andExpect(status().isCreated());

        // 恢复 -> 422
        mockMvc.perform(post("/api/races/s2/resumptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventKey":"ev","resumeElapsedMs":2000,
                                 "expectedVersion":5,"requestId":"p5"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));

        // 整体回滚：赛事仍 SUSPENDED、版本仍为5、事件仍 SUSPENDED
        mockMvc.perform(get("/api/races/s2/suspensions"))
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.events[0].status").value("SUSPENDED"))
                .andExpect(jsonPath("$.events[0].resumeElapsedMs").doesNotExist());

        // 原始分段未被改写
        mockMvc.perform(get("/api/races/s2/runners/a/timings"))
                .andExpect(jsonPath("$.checkpoints[0].elapsedMillis").value(1500));
    }
}
