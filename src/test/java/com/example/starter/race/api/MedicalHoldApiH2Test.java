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
 * 覆盖登记/恢复主流程、排除分段、查询端点与错误语义（400/404/409/422）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class MedicalHoldApiH2Test extends AbstractRaceH2Test {

    private static final long START_AT =
            FixedClockTestConfig.FIXED_INSTANT.toEpochMilli() + 60_000L;
    private static final long END_AT =
            FixedClockTestConfig.FIXED_INSTANT.toEpochMilli() + 120_000L;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 医疗暂停登记恢复到查询全链路() throws Exception {
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
        mockMvc.perform(post("/api/races/r/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkpoints":[
                                  {"checkpointCode":"c1","position":1},
                                  {"checkpointCode":"c2","position":2}],
                                 "expectedVersion":2,"requestId":"c2"}
                                """))
                .andExpect(status().isCreated());

        // 参数校验：缺 reason -> 400
        mockMvc.perform(post("/api/races/r/runners/a/medical-holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"holdId":"h0","startAt":%d,
                                 "medicalRole":"medic-1","expectedVersion":3,"requestId":"h0"}
                                """.formatted(START_AT)))
                .andExpect(status().isBadRequest());

        // 登记医疗暂停 -> 201 ACTIVE
        mockMvc.perform(post("/api/races/r/runners/a/medical-holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"holdId":"h1","startAt":%d,"reason":"赛道医疗处置",
                                 "medicalRole":"medic-1","expectedVersion":3,"requestId":"h1"}
                                """.formatted(START_AT)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.holdId").value("h1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.startedBy").value("medic-1"))
                .andExpect(jsonPath("$.startAt").value(START_AT));

        // 重复开始 -> 409
        mockMvc.perform(post("/api/races/r/runners/a/medical-holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"holdId":"h2","startAt":%d,"reason":"再次处置",
                                 "medicalRole":"medic-2","expectedVersion":4,"requestId":"h2"}
                                """.formatted(START_AT)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));

        // 暂停期间提交分段 -> 201 但标记 MEDICAL_HOLD 排除
        mockMvc.perform(post("/api/races/r/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"t1","checkpointCode":"c1","elapsedMillis":100,
                                 "expectedVersion":4,"requestId":"t1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.excluded").value(true))
                .andExpect(jsonPath("$.exclusionReason").value("MEDICAL_HOLD"))
                .andExpect(jsonPath("$.holdId").value("h1"));

        // 即时成绩：资格状态 MEDICAL_HOLD，被排除分段不计入覆盖
        mockMvc.perform(get("/api/races/r/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].bib").value("a"))
                .andExpect(jsonPath("$.entries[0].eligibilityStatus").value("MEDICAL_HOLD"))
                .andExpect(jsonPath("$.entries[0].status").value("MISSING_CHECKPOINT"))
                .andExpect(jsonPath("$.entries[0].coveredCheckpointCount").value(0));

        // 同一医疗角色恢复 -> 422
        mockMvc.perform(post("/api/races/r/runners/a/medical-holds/h1/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"endAt":%d,"fitnessConclusion":"确认适赛",
                                 "medicalRole":"medic-1","expectedVersion":5,"requestId":"r1"}
                                """.formatted(END_AT)))
                .andExpect(status().isUnprocessableEntity());

        // 结束不晚于开始 -> 400
        mockMvc.perform(post("/api/races/r/runners/a/medical-holds/h1/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"endAt":%d,"fitnessConclusion":"确认适赛",
                                 "medicalRole":"medic-2","expectedVersion":5,"requestId":"r2"}
                                """.formatted(START_AT)))
                .andExpect(status().isBadRequest());

        // 不同医疗角色恢复 -> 200 RESUMED
        mockMvc.perform(post("/api/races/r/runners/a/medical-holds/h1/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"endAt":%d,"fitnessConclusion":"确认适赛",
                                 "medicalRole":"medic-2","expectedVersion":5,"requestId":"r3"}
                                """.formatted(END_AT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESUMED"))
                .andExpect(jsonPath("$.endedBy").value("medic-2"))
                .andExpect(jsonPath("$.endAt").value(END_AT));

        // 历史查询
        mockMvc.perform(get("/api/races/r/runners/a/medical-holds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holds.length()").value(1))
                .andExpect(jsonPath("$.holds[0].holdId").value("h1"))
                .andExpect(jsonPath("$.holds[0].status").value("RESUMED"));

        // 明细查询
        mockMvc.perform(get("/api/races/r/runners/a/medical-holds/h1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("赛道医疗处置"))
                .andExpect(jsonPath("$.fitnessConclusion").value("确认适赛"));

        // 诊断查询：被排除分段
        mockMvc.perform(get("/api/races/r/runners/a/excluded-timings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.excludedTimings.length()").value(1))
                .andExpect(jsonPath("$.excludedTimings[0].timingId").value("t1"))
                .andExpect(jsonPath("$.excludedTimings[0].exclusionReason").value("MEDICAL_HOLD"));

        // 未找到 -> 404
        mockMvc.perform(get("/api/races/r/runners/a/medical-holds/ghost"))
                .andExpect(status().isNotFound());
    }
}
