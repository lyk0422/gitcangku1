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
 * 器材检录与起跑拦截 HTTP 接口的端到端测试：真实 Spring MVC + Service + H2，
 * 覆盖建赛配置、检录、起跑门禁（201/422/409）及查询接口。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class InspectionApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 强制赛事检录起跑查询全链路() throws Exception {
        // 建强制检录赛事，PASS 有效 30 分钟
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-http","inspectionRequired":true,"validMinutes":30,
                                 "requestId":"req-create"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"));

        mockMvc.perform(post("/api/races/race-http/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","expectedVersion":1,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated());

        // 未检录起跑 → 422
        mockMvc.perform(post("/api/races/race-http/runners/a/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startId":"start-1","expectedVersion":2,"requestId":"req-start-1"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("PASS")));

        // FAIL → 422
        mockMvc.perform(post("/api/races/race-http/runners/a/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inspectionKey":"insp-f","equipmentSerial":"bike-1","result":"FAIL",
                                 "expectedVersion":2,"requestId":"req-f"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("FAIL"))
                .andExpect(jsonPath("$.validUntil").doesNotExist());

        mockMvc.perform(post("/api/races/race-http/runners/a/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startId":"start-1","expectedVersion":3,"requestId":"req-start-1b"}
                                """))
                .andExpect(status().isUnprocessableEntity());

        // 复检 PASS（v4），随后起跑（v5）
        mockMvc.perform(post("/api/races/race-http/runners/a/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inspectionKey":"insp-p","equipmentSerial":"bike-1","result":"PASS",
                                 "expectedVersion":3,"requestId":"req-p"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("PASS"))
                .andExpect(jsonPath("$.validMinutes").value(30));

        mockMvc.perform(post("/api/races/race-http/runners/a/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startId":"start-1","expectedVersion":4,"requestId":"req-start-ok"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bib").value("a"));

        // 当前状态：valid=true
        mockMvc.perform(get("/api/races/race-http/runners/a/inspection-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("PASS"))
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.inspectionRequired").value(true));

        // 历史保留 FAIL + PASS 两条，顺序为 FAIL 在前
        mockMvc.perform(get("/api/races/race-http/runners/a/inspections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].result").value("FAIL"))
                .andExpect(jsonPath("$.entries[1].result").value("PASS"));

        // 绑定清单：bike-1 → a
        mockMvc.perform(get("/api/races/race-http/equipment-bindings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].equipmentSerial").value("bike-1"))
                .andExpect(jsonPath("$.entries[0].bib").value("a"));
    }

    @Test
    void 同器材绑定第二个未完赛选手返回409() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-409","inspectionRequired":true,"validMinutes":30,
                                 "requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-409/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","expectedVersion":1,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-409/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b","expectedVersion":2,"requestId":"req-b"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/races/race-409/runners/a/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inspectionKey":"ia","equipmentSerial":"bike-X","result":"PASS",
                                 "expectedVersion":3,"requestId":"req-ia"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/races/race-409/runners/b/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inspectionKey":"ib","equipmentSerial":"bike-X","result":"PASS",
                                 "expectedVersion":4,"requestId":"req-ib"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("绑定")));
    }

    @Test
    void 非强制赛事无需检录即可起跑() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-free","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-free/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","expectedVersion":1,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-free/runners/a/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startId":"start-1","expectedVersion":2,"requestId":"req-start"}
                                """))
                .andExpect(status().isCreated());
    }
}
