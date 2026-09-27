package com.example.starter.race.api;

import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.MutableClockTestConfig;
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
 * 医疗暂停 HTTP 接口端到端测试：真实 Spring MVC + Service + H2，
 * 覆盖暂停开始、MEDICAL_HOLD 分段、恢复422/400/409 分支、历史/诊断只读查询与封榜固化。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfig.class)
class MedicalHoldApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 医疗暂停全链路与失败分支HTTP状态() throws Exception {
        // 建赛 v1、登记选手 v2、配置检查点 v3
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-med-api","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-med-api/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":10000,"expectedVersion":1,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-med-api/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkpoints":[
                                  {"checkpointCode":"cp1","position":1},
                                  {"checkpointCode":"cp2","position":2}],
                                 "expectedVersion":2,"requestId":"req-cfg"}
                                """))
                .andExpect(status().isCreated());

        long startAt = 1_800_000_000_000L;
        long endAt = startAt + 60_000;

        // 开始暂停 v4
        mockMvc.perform(post("/api/races/race-med-api/runners/a/medical-holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"holdId":"hold-1","startAt":%d,"reason":"cramp",
                                 "medicalOfficer":"medic-a","expectedVersion":3,"requestId":"req-hold"}
                                """.formatted(startAt)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.holdId").value("hold-1"))
                .andExpect(jsonPath("$.startedBy").value("medic-a"));

        // 重复开始：409
        mockMvc.perform(post("/api/races/race-med-api/runners/a/medical-holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"holdId":"hold-2","startAt":%d,"reason":"again",
                                 "medicalOfficer":"medic-b","expectedVersion":4,"requestId":"req-hold-2"}
                                """.formatted(startAt)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));

        // 暂停期间提交分段：201 且 medicalHold=true
        mockMvc.perform(post("/api/races/race-med-api/runners/a/timings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timingId":"t-cp1","checkpointCode":"cp1","elapsedMillis":1000,
                                 "expectedVersion":4,"requestId":"req-t1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.medicalHold").value(true))
                .andExpect(jsonPath("$.excludedHoldId").value("hold-1"));

        // 临时排名：漏点不排名、暂停中、排除计数1
        mockMvc.perform(get("/api/races/race-med-api/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].status").value("MISSING_CHECKPOINT"))
                .andExpect(jsonPath("$.entries[0].rank").doesNotExist())
                .andExpect(jsonPath("$.entries[0].medicalHoldActive").value(true))
                .andExpect(jsonPath("$.entries[0].excludedTimingCount").value(1));

        // 同一医疗角色恢复：422（分段提交后版本已推进到5）
        mockMvc.perform(post("/api/races/race-med-api/medical-holds/hold-1/resumption")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"endAt":%d,"medicalOfficer":"medic-a","fitConclusion":"fit",
                                 "expectedVersion":5,"requestId":"req-resume-same"}
                                """.formatted(endAt)))
                .andExpect(status().isUnprocessableEntity());

        // endAt 不晚于 startAt：400
        mockMvc.perform(post("/api/races/race-med-api/medical-holds/hold-1/resumption")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"endAt":%d,"medicalOfficer":"medic-b","fitConclusion":"fit",
                                 "expectedVersion":5,"requestId":"req-resume-bad"}
                                """.formatted(startAt)))
                .andExpect(status().isBadRequest());

        // 不同角色恢复成功 v6
        mockMvc.perform(post("/api/races/race-med-api/medical-holds/hold-1/resumption")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"endAt":%d,"medicalOfficer":"medic-b","fitConclusion":"fit-to-race",
                                 "expectedVersion":5,"requestId":"req-resume"}
                                """.formatted(endAt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.durationMs").value(60_000))
                .andExpect(jsonPath("$.resumedBy").value("medic-b"));

        // 重复恢复：409
        mockMvc.perform(post("/api/races/race-med-api/medical-holds/hold-1/resumption")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"endAt":%d,"medicalOfficer":"medic-b","fitConclusion":"fit-2",
                                 "expectedVersion":6,"requestId":"req-resume-2"}
                                """.formatted(endAt + 1)))
                .andExpect(status().isConflict());

        // 历史与诊断只读
        mockMvc.perform(get("/api/races/race-med-api/medical-holds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holds[0].holdId").value("hold-1"))
                .andExpect(jsonPath("$.holds[0].active").value(false))
                .andExpect(jsonPath("$.holds[0].excludedTimingCount").value(1))
                .andExpect(jsonPath("$.holds[0].fitConclusion").value("fit-to-race"));
        mockMvc.perform(get("/api/races/race-med-api/runners/a/medical-diagnosis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entryStatus").value("MISSING_CHECKPOINT"))
                .andExpect(jsonPath("$.activeHoldId").doesNotExist())
                .andExpect(jsonPath("$.excludedTimings[0].timingId").value("t-cp1"));

        // 封榜 v7，快照固化资格状态与排除原因
        mockMvc.perform(post("/api/races/race-med-api/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":6,"requestId":"req-seal"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.entries[0].status").value("MISSING_CHECKPOINT"))
                .andExpect(jsonPath("$.entries[0].excludedTimingCount").value(1));

        // 封榜后分段明细仍带排除原因，原始证据保留
        mockMvc.perform(get("/api/races/race-med-api/runners/a/timings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpoints[0].checkpointCode").value("cp1"))
                .andExpect(jsonPath("$.checkpoints[0].medicalHold").value(true))
                .andExpect(jsonPath("$.checkpoints[0].excludedHoldId").value("hold-1"))
                .andExpect(jsonPath("$.checkpoints[0].excludedReason").value("cramp"));

        // 封榜后再开始/恢复暂停均409
        mockMvc.perform(post("/api/races/race-med-api/medical-holds/hold-1/resumption")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"endAt":%d,"medicalOfficer":"medic-b","fitConclusion":"x",
                                 "expectedVersion":7,"requestId":"req-resume-sealed"}
                                """.formatted(endAt + 2)))
                .andExpect(status().isConflict());
    }
}
