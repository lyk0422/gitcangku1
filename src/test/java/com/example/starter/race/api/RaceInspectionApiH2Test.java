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
 * 器材检录能力端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 覆盖配置、PASS/FAIL 提交、起跑门禁422、复检放行、同器材409与绑定/状态/历史查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class RaceInspectionApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 检录配置提交门禁复检与绑定查询全链路() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r","requestId":"c0"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":null,"expectedVersion":1,"requestId":"c1"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b","finishTimeMs":null,"expectedVersion":2,"requestId":"c2"}
                                """))
                .andExpect(status().isCreated());

        // 开启强制检录，PASS 有效30分钟（v4）
        mockMvc.perform(post("/api/races/r/inspection-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mandatory":true,"validMinutes":30,"expectedVersion":3,"requestId":"c3"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.mandatory").value(true))
                .andExpect(jsonPath("$.validMinutes").value(30));

        // a 未检录起跑 → 422
        mockMvc.perform(post("/api/races/r/runners/a/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":4,"requestId":"c4"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不存在")));

        // a 检录 FAIL → 起跑仍422
        mockMvc.perform(post("/api/races/r/runners/a/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inspectionKey":"ka-fail","equipmentSerial":"BIKE-1","result":"FAIL",
                                 "expectedVersion":4,"requestId":"c5"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("FAIL"));
        mockMvc.perform(post("/api/races/r/runners/a/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":5,"requestId":"c6"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("FAIL")));

        // b 先以 PASS 占用 BIKE-1（v6）
        mockMvc.perform(post("/api/races/r/runners/b/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inspectionKey":"kb-pass","equipmentSerial":"BIKE-1","result":"PASS",
                                 "expectedVersion":5,"requestId":"c7"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.validUntil").exists());

        // a 复检 PASS 但器材已绑 b → 409
        mockMvc.perform(post("/api/races/r/runners/a/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inspectionKey":"ka-pass","equipmentSerial":"BIKE-1","result":"PASS",
                                 "expectedVersion":6,"requestId":"c8"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("已绑定另一名未完赛选手")));

        // a 改用 BIKE-2 复检 PASS（v7）并起跑成功（v8）
        mockMvc.perform(post("/api/races/r/runners/a/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inspectionKey":"ka-pass2","equipmentSerial":"BIKE-2","result":"PASS",
                                 "expectedVersion":6,"requestId":"c9"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r/runners/a/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":7,"requestId":"c10"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("STARTED"))
                .andExpect(jsonPath("$.startedAt").exists());

        // 查询：历史两条（FAIL、PASS）、状态 PASS_VALID、绑定两个器材
        mockMvc.perform(get("/api/races/r/runners/a/inspections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records.length()").value(2))
                .andExpect(jsonPath("$.records[0].result").value("FAIL"))
                .andExpect(jsonPath("$.records[1].result").value("PASS"))
                .andExpect(jsonPath("$.records[1].equipmentSerial").value("BIKE-2"));
        mockMvc.perform(get("/api/races/r/runners/a/inspection-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mandatory").value(true))
                .andExpect(jsonPath("$.effective").value("PASS_VALID"))
                .andExpect(jsonPath("$.latest.inspectionId").value("ka-pass2"));
        mockMvc.perform(get("/api/races/r/equipment-bindings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bindings.length()").value(2))
                .andExpect(jsonPath("$.bindings[0].equipmentSerial").value("BIKE-1"))
                .andExpect(jsonPath("$.bindings[0].bib").value("b"))
                .andExpect(jsonPath("$.bindings[1].equipmentSerial").value("BIKE-2"))
                .andExpect(jsonPath("$.bindings[1].bib").value("a"));

        // b 退赛后 BIKE-1 释放，绑定清单只剩 a 的 BIKE-2
        mockMvc.perform(post("/api/races/r/runners/b/withdrawal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"抽筋","expectedVersion":8,"requestId":"c11"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("WITHDRAWN"))
                .andExpect(jsonPath("$.reason").value("抽筋"));
        mockMvc.perform(get("/api/races/r/equipment-bindings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bindings.length()").value(1))
                .andExpect(jsonPath("$.bindings[0].equipmentSerial").value("BIKE-2"));
    }

    @Test
    void 参数校验失败返回400() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r2","requestId":"d0"}
                                """))
                .andExpect(status().isCreated());
        // validMinutes 超出 1~1440
        mockMvc.perform(post("/api/races/r2/inspection-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mandatory":true,"validMinutes":0,"expectedVersion":1,"requestId":"d1"}
                                """))
                .andExpect(status().isBadRequest());
        // result 非 PASS/FAIL（Bean Validation 在业务校验前返回400）
        mockMvc.perform(post("/api/races/r2/runners/x/inspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"inspectionKey":"k","equipmentSerial":"s","result":"MAYBE",
                                 "expectedVersion":1,"requestId":"d2"}
                                """))
                .andExpect(status().isBadRequest());
    }
}
