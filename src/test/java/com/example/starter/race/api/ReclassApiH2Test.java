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
 * 组别重分 API 的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 校验状态码（201/200/404/409/422）与关键 JSON 字段。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class ReclassApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    /** 建赛、配置组别并登记两名选手，推进到版本4。 */
    private void initRaceWithRunners() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-reclass-api","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-reclass-api/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categories":[{"categoryCode":"AMATEUR","capacity":10},
                                {"categoryCode":"ELITE","capacity":2}],
                                "expectedVersion":1,"requestId":"req-cats"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.categories[0].categoryCode").value("AMATEUR"));
        mockMvc.perform(post("/api/races/race-reclass-api/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","categoryCode":"ELITE","finishTimeMs":1000,
                                "expectedVersion":2,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoryCode").value("ELITE"))
                .andExpect(jsonPath("$.withdrawn").value(false));
        mockMvc.perform(post("/api/races/race-reclass-api/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"c","categoryCode":"AMATEUR","finishTimeMs":1500,
                                "expectedVersion":3,"requestId":"req-c"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void 重分全链路_申请批准查询两组排名与记录() throws Exception {
        initRaceWithRunners(); // 当前版本4
        mockMvc.perform(post("/api/races/race-reclass-api/reclass-applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","targetCategory":"AMATEUR","reason":"年龄组调整",
                                "expectedVersion":4,"requestId":"req-app-a"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.applicationId").value("rca-req-app-a"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(post("/api/races/race-reclass-api/reclass-approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"referee","applicationIds":["rca-req-app-a"],
                                "expectedVersion":5,"reclassKey":"rk-api-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(6))
                .andExpect(jsonPath("$.records[0].bib").value("a"))
                .andExpect(jsonPath("$.records[0].fromCategory").value("ELITE"))
                .andExpect(jsonPath("$.records[0].toCategory").value("AMATEUR"))
                .andExpect(jsonPath("$.records[0].preRank").value(1))
                .andExpect(jsonPath("$.standings[0].categoryCode").value("AMATEUR"))
                .andExpect(jsonPath("$.standings[1].categoryCode").value("ELITE"));

        mockMvc.perform(get("/api/races/race-reclass-api/categories/AMATEUR/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sealed").value(false))
                .andExpect(jsonPath("$.entries[0].bib").value("a"))
                .andExpect(jsonPath("$.entries[0].rank").value(1))
                .andExpect(jsonPath("$.entries[1].bib").value("c"));
        mockMvc.perform(get("/api/races/race-reclass-api/reclass-records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].recordId").value("rk-api-1:rca-req-app-a"));
        mockMvc.perform(get("/api/races/race-reclass-api/reclass-applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applications[0].status").value("APPROVED"));
    }

    @Test
    void 错误码_目标组别不存在404_容量超限422_版本冲突409() throws Exception {
        initRaceWithRunners(); // 当前版本4
        // 目标组别不存在 -> 404
        mockMvc.perform(post("/api/races/race-reclass-api/reclass-applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","targetCategory":"NOPE","reason":"x",
                                "expectedVersion":4,"requestId":"req-bad-cat"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        // 填满 ELITE（容量2，已有 a），再登记一人占满
        mockMvc.perform(post("/api/races/race-reclass-api/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b","categoryCode":"ELITE","finishTimeMs":2000,
                                "expectedVersion":4,"requestId":"req-b"}
                                """))
                .andExpect(status().isCreated()); // v5
        mockMvc.perform(post("/api/races/race-reclass-api/reclass-applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"c","targetCategory":"ELITE","reason":"升级",
                                "expectedVersion":5,"requestId":"req-app-c"}
                                """))
                .andExpect(status().isCreated()); // v6
        // ELITE 已满 -> 批准整批422
        mockMvc.perform(post("/api/races/race-reclass-api/reclass-approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"referee","applicationIds":["rca-req-app-c"],
                                "expectedVersion":6,"reclassKey":"rk-cap"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));
        // 版本冲突 -> 409
        mockMvc.perform(post("/api/races/race-reclass-api/reclass-approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"referee","applicationIds":["rca-req-app-c"],
                                "expectedVersion":1,"reclassKey":"rk-ver"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
        // 组别快照未封榜 -> 404
        mockMvc.perform(get("/api/races/race-reclass-api/categories/ELITE/snapshot"))
                .andExpect(status().isNotFound());
    }
}