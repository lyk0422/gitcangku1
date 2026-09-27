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
 * 医疗暂停能力的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 覆盖登记/恢复主流程、暂停期间计时排除、409/422 失败分支、历史与诊断查询、封榜固化。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class MedicalHoldApiH2Test extends AbstractRaceH2Test {

    private static final long BASE = FixedClockTestConfig.FIXED_INSTANT.toEpochMilli();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 医疗暂停到恢复到封榜全链路() throws Exception {
        long startedAt = BASE + 1_000L;
        long endedAt = BASE + 2_000L;

        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"rm","requestId":"m0"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/rm/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":10000,"expectedVersion":1,"requestId":"m1"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/rm/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkpoints":[
                                  {"checkpointCode":"p1","position":1},
                                  {"checkpointCode":"p2","position":2}],
                                 "expectedVersion":2,"requestId":"m2"}
                                """))
                .andExpect(status().isCreated());

        // 暂停前分段：正常参与
        mockMvc.perform(post("/api/races/rm/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"ta1","checkpointCode":"p1","elapsedMillis":100,
                                 "expectedVersion":3,"requestId":"m3"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.exclusionReason").doesNotExist());

        // 登记医疗暂停（v4 -> v5）
        mockMvc.perform(post("/api/races/rm/runners/a/medical-holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"holdId":"h1","medicRole":"medic-1","startedAt":%d,
                                 "reason":"赛中扭伤","expectedVersion":4,"requestId":"m4"}
                                """.formatted(startedAt)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.holdId").value("h1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.medicRole").value("medic-1"))
                .andExpect(jsonPath("$.startedAt").value(startedAt))
                .andExpect(jsonPath("$.endedAt").doesNotExist());

        // 重复开始 -> 409
        mockMvc.perform(post("/api/races/rm/runners/a/medical-holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"holdId":"h2","medicRole":"medic-9","startedAt":%d,
                                 "reason":"重复","expectedVersion":5,"requestId":"m5"}
                                """.formatted(startedAt)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));

        // 暂停期间分段：保存但标记 MEDICAL_HOLD（v5 -> v6）
        mockMvc.perform(post("/api/races/rm/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"ta2","checkpointCode":"p2","elapsedMillis":200,
                                 "expectedVersion":5,"requestId":"m6"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.exclusionReason").value("MEDICAL_HOLD"));

        // 临时排名：p2 被排除 -> 仍缺 p2
        mockMvc.perform(get("/api/races/rm/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].bib").value("a"))
                .andExpect(jsonPath("$.entries[0].status").value("MISSING_CHECKPOINT"))
                .andExpect(jsonPath("$.entries[0].coveredCheckpointCount").value(1))
                .andExpect(jsonPath("$.entries[0].missingCheckpoints[0]").value("p2"));

        // 同一医疗角色恢复 -> 422
        mockMvc.perform(post("/api/races/rm/runners/a/medical-holds/h1/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"confirmRole":"medic-1","endedAt":%d,"fitnessConclusion":"FIT",
                                 "expectedVersion":6,"requestId":"m7bad"}
                                """.formatted(endedAt)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));

        // 结束不晚于开始 -> 422
        mockMvc.perform(post("/api/races/rm/runners/a/medical-holds/h1/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"confirmRole":"medic-2","endedAt":%d,"fitnessConclusion":"FIT",
                                 "expectedVersion":6,"requestId":"m7bad2"}
                                """.formatted(startedAt)))
                .andExpect(status().isUnprocessableEntity());

        // 不同医疗角色确认适赛 -> 恢复（v6 -> v7）
        mockMvc.perform(post("/api/races/rm/runners/a/medical-holds/h1/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"confirmRole":"medic-2","endedAt":%d,"fitnessConclusion":"FIT",
                                 "expectedVersion":6,"requestId":"m7"}
                                """.formatted(endedAt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESUMED"))
                .andExpect(jsonPath("$.confirmRole").value("medic-2"))
                .andExpect(jsonPath("$.endedAt").value(endedAt))
                .andExpect(jsonPath("$.fitnessConclusion").value("FIT"));

        // 选手暂停历史与赛事诊断查询
        mockMvc.perform(get("/api/races/rm/runners/a/medical-holds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].holdId").value("h1"))
                .andExpect(jsonPath("$[0].status").value("RESUMED"));
        mockMvc.perform(get("/api/races/rm/medical-holds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].bib").value("a"));

        // 封榜（v7 -> v8）：快照显示资格状态与排除计时原因
        mockMvc.perform(post("/api/races/rm/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":7,"requestId":"m8"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.entries[0].status").value("MISSING_CHECKPOINT"))
                .andExpect(jsonPath("$.entries[0].missingCheckpoints[0]").value("p2"));

        // 封榜后分段明细走快照：p2 保留原始耗时与排除原因
        mockMvc.perform(get("/api/races/rm/runners/a/timings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpoints[1].elapsedMillis").value(200))
                .andExpect(jsonPath("$.checkpoints[1].exclusionReason").value("MEDICAL_HOLD"));

        // 封榜后不可再登记暂停
        mockMvc.perform(post("/api/races/rm/runners/a/medical-holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"holdId":"h3","medicRole":"medic-1","startedAt":%d,
                                 "reason":"封榜后","expectedVersion":8,"requestId":"m9"}
                                """.formatted(startedAt)))
                .andExpect(status().isConflict());
    }

    @Test
    void 暂停期间终点计时恢复返回422且参数校验失败返回400() throws Exception {
        long startedAt = BASE - 1_000L;

        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"rf","requestId":"f0"}
                                """))
                .andExpect(status().isCreated());
        // 登记即带完赛耗时（记录时刻为固定时钟 BASE，落在声明区间内）
        mockMvc.perform(post("/api/races/rf/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":10000,"expectedVersion":1,"requestId":"f1"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/rf/runners/a/medical-holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"holdId":"h1","medicRole":"medic-1","startedAt":%d,
                                 "reason":"受伤","expectedVersion":2,"requestId":"f2"}
                                """.formatted(startedAt)))
                .andExpect(status().isCreated());

        // 暂停声明区间覆盖终点计时记录时刻 -> 422 要求先裁定完赛状态
        mockMvc.perform(post("/api/races/rf/runners/a/medical-holds/h1/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"confirmRole":"medic-2","endedAt":%d,"fitnessConclusion":"FIT",
                                 "expectedVersion":3,"requestId":"f3"}
                                """.formatted(BASE + 1_000L)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));

        // 缺少必填字段 -> 400
        mockMvc.perform(post("/api/races/rf/runners/a/medical-holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"holdId":"h9","startedAt":1,"reason":"缺角色",
                                 "expectedVersion":3,"requestId":"f4"}
                                """))
                .andExpect(status().isBadRequest());

        // 恢复不存在的暂停 -> 404
        mockMvc.perform(post("/api/races/rf/runners/a/medical-holds/ghost/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"confirmRole":"medic-2","endedAt":%d,"fitnessConclusion":"FIT",
                                 "expectedVersion":3,"requestId":"f5"}
                                """.formatted(BASE + 1_000L)))
                .andExpect(status().isNotFound());
    }
}
