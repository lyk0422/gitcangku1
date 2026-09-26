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
 * 医疗暂停与退赛能力的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 覆盖登记暂停、排除计时、恢复适赛、退赛、历史查询与封榜固化。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class MedicalHoldApiH2Test extends AbstractRaceH2Test {

    private static final long NOW = FixedClockTestConfig.FIXED_INSTANT.toEpochMilli();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 医疗暂停到封榜全链路() throws Exception {
        int version = 1;
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"mh","requestId":"m0"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/mh/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":10000,"expectedVersion":1,"requestId":"m1"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/mh/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b","finishTimeMs":9000,"expectedVersion":2,"requestId":"m2"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/mh/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkpoints":[
                                  {"checkpointCode":"c1","position":1},
                                  {"checkpointCode":"c2","position":2}],
                                 "expectedVersion":3,"requestId":"m3"}
                                """))
                .andExpect(status().isCreated());
        version = 4;

        // a 赛前通过 c1
        mockMvc.perform(post("/api/races/mh/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"t-a1","checkpointCode":"c1","elapsedMillis":100,
                                 "expectedVersion":4,"requestId":"m4"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.medicalHold").value(false));
        version = 5;

        // 登记医疗暂停
        mockMvc.perform(post("/api/races/mh/runners/a/medical-holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"holdId":"h1","startAt":%d,"reason":"赛中扭伤",
                                 "medicalRole":"medic-1","expectedVersion":5,"requestId":"m5"}
                                """.formatted(NOW + 1_000L)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.startAt").value(NOW + 1_000L))
                .andExpect(jsonPath("$.reason").value("赛中扭伤"))
                .andExpect(jsonPath("$.startedBy").value("medic-1"))
                .andExpect(jsonPath("$.endAt").doesNotExist());
        version = 6;

        // 暂停生效：a 资格暂停不排名（a、b 均未排名，按参赛号排列）
        mockMvc.perform(get("/api/races/mh/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].bib").value("a"))
                .andExpect(jsonPath("$.entries[0].status").value("MEDICAL_HOLD"));

        // 暂停期间分段保存但被排除
        mockMvc.perform(post("/api/races/mh/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"t-a2","checkpointCode":"c2","elapsedMillis":200,
                                 "expectedVersion":6,"requestId":"m6"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.medicalHold").value(true))
                .andExpect(jsonPath("$.holdId").value("h1"));
        version = 7;

        // 明细查询显示排除原因
        mockMvc.perform(get("/api/races/mh/runners/a/timings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpoints[1].exclusionReason").value("MEDICAL_HOLD"))
                .andExpect(jsonPath("$.checkpoints[0].exclusionReason").doesNotExist());

        // 同一医疗角色恢复 → 409
        mockMvc.perform(post("/api/races/mh/runners/a/medical-holds/h1/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"endAt":%d,"fitnessConclusion":"可以完赛",
                                 "medicalRole":"medic-1","expectedVersion":7,"requestId":"m7bad"}
                                """.formatted(NOW + 2_000L)))
                .andExpect(status().isConflict());

        // 结束不晚于开始 → 400
        mockMvc.perform(post("/api/races/mh/runners/a/medical-holds/h1/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"endAt":%d,"fitnessConclusion":"可以完赛",
                                 "medicalRole":"medic-2","expectedVersion":7,"requestId":"m7bad2"}
                                """.formatted(NOW + 1_000L)))
                .andExpect(status().isBadRequest());

        // 不同医疗角色确认适赛 → 200
        mockMvc.perform(post("/api/races/mh/runners/a/medical-holds/h1/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"endAt":%d,"fitnessConclusion":"可以完赛",
                                 "medicalRole":"medic-2","expectedVersion":7,"requestId":"m7"}
                                """.formatted(NOW + 2_000L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESUMED"))
                .andExpect(jsonPath("$.endAt").value(NOW + 2_000L))
                .andExpect(jsonPath("$.durationMs").value(1_000L))
                .andExpect(jsonPath("$.resumedBy").value("medic-2"))
                .andExpect(jsonPath("$.fitnessConclusion").value("可以完赛"));
        version = 8;

        // 历史与诊断查询
        mockMvc.perform(get("/api/races/mh/medical-holds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(version))
                .andExpect(jsonPath("$.holds.length()").value(1))
                .andExpect(jsonPath("$.holds[0].holdId").value("h1"))
                .andExpect(jsonPath("$.holds[0].status").value("RESUMED"));
        mockMvc.perform(get("/api/races/mh/runners/a/medical-holds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bib").value("a"))
                .andExpect(jsonPath("$.holds.length()").value(1));

        // 退赛
        mockMvc.perform(post("/api/races/mh/runners/b/withdrawal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"个人原因","expectedVersion":8,"requestId":"m8"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.withdrawn").value(true));
        version = 9;
        mockMvc.perform(get("/api/races/mh/results"))
                .andExpect(jsonPath("$.entries[1].bib").value("b"))
                .andExpect(jsonPath("$.entries[1].status").value("WITHDRAWN"));

        // 封榜：a 的 c2 被排除 → 漏点不排名；b 退赛
        mockMvc.perform(post("/api/races/mh/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":9,"requestId":"m9"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.entries[0].bib").value("a"))
                .andExpect(jsonPath("$.entries[0].status").value("MISSING_CHECKPOINT"))
                .andExpect(jsonPath("$.entries[1].bib").value("b"))
                .andExpect(jsonPath("$.entries[1].status").value("WITHDRAWN"));

        // 封榜快照保留排除原因
        mockMvc.perform(get("/api/races/mh/runners/a/timings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpoints[1].exclusionReason").value("MEDICAL_HOLD"));

        // 封榜后禁止登记暂停
        mockMvc.perform(post("/api/races/mh/runners/a/medical-holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"holdId":"h2","startAt":%d,"reason":"赛后检查",
                                 "medicalRole":"medic-1","expectedVersion":10,"requestId":"m10"}
                                """.formatted(NOW + 3_000L)))
                .andExpect(status().isConflict());
    }

    @Test
    void 请求校验与资源缺失分支() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"mh2","requestId":"v0"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/mh2/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":10000,"expectedVersion":1,"requestId":"v1"}
                                """))
                .andExpect(status().isCreated());

        // 缺少必填字段 → 400
        mockMvc.perform(post("/api/races/mh2/runners/a/medical-holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"holdId":"h1","startAt":%d,
                                 "medicalRole":"medic-1","expectedVersion":2,"requestId":"v2"}
                                """.formatted(NOW + 1_000L)))
                .andExpect(status().isBadRequest());

        // 赛事不存在 → 404
        mockMvc.perform(get("/api/races/nope/medical-holds"))
                .andExpect(status().isNotFound());
        // 选手不存在 → 404
        mockMvc.perform(post("/api/races/mh2/runners/zz/medical-holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"holdId":"h9","startAt":%d,"reason":"x",
                                 "medicalRole":"medic-1","expectedVersion":2,"requestId":"v3"}
                                """.formatted(NOW + 1_000L)))
                .andExpect(status().isNotFound());
        // 恢复不存在的暂停 → 404
        mockMvc.perform(post("/api/races/mh2/runners/a/medical-holds/h-x/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"endAt":%d,"fitnessConclusion":"y",
                                 "medicalRole":"medic-2","expectedVersion":2,"requestId":"v4"}
                                """.formatted(NOW + 2_000L)))
                .andExpect(status().isNotFound());
    }
}
