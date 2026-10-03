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
 * 覆盖建组、登记、申请、批量批准、排名重算、查询与错误状态码。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class ReclassApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 组别重分全链路与查询端点() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-rc","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());

        // 建组 A、B
        mockMvc.perform(post("/api/races/race-rc/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categoryCode":"A","capacity":2,"expectedVersion":1,"requestId":"req-cat-a"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoryCode").value("A"))
                .andExpect(jsonPath("$.capacity").value(2))
                .andExpect(jsonPath("$.status").value("OPEN"));
        mockMvc.perform(post("/api/races/race-rc/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categoryCode":"B","capacity":2,"expectedVersion":2,"requestId":"req-cat-b"}
                                """))
                .andExpect(status().isCreated());

        // 登记选手到组别
        mockMvc.perform(post("/api/races/race-rc/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":1000,"categoryCode":"A",
                                 "expectedVersion":3,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoryCode").value("A"));
        mockMvc.perform(post("/api/races/race-rc/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b","finishTimeMs":2000,"categoryCode":"A",
                                 "expectedVersion":4,"requestId":"req-b"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-rc/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"c","finishTimeMs":1500,"categoryCode":"B",
                                 "expectedVersion":5,"requestId":"req-c"}
                                """))
                .andExpect(status().isCreated());

        // 组别成绩查询
        mockMvc.perform(get("/api/races/race-rc/categories/A/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].bib").value("a"))
                .andExpect(jsonPath("$.entries[0].rank").value(1))
                .andExpect(jsonPath("$.entries[1].bib").value("b"))
                .andExpect(jsonPath("$.entries[1].rank").value(2));

        // 提交重分申请
        mockMvc.perform(post("/api/races/race-rc/reclass-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"applicationId":"app-1","bib":"b","targetCategoryCode":"B",
                                 "reason":"年龄组报错","expectedVersion":6,"requestId":"req-sub-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.sourceCategoryCode").value("A"))
                .andExpect(jsonPath("$.targetCategoryCode").value("B"));

        // 批量批准：返回重分记录与两组重算排名
        mockMvc.perform(post("/api/races/race-rc/reclass-approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reclassKey":"k1","operator":"referee-1","expectedVersion":7,
                                 "applicationIds":["app-1"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(8))
                .andExpect(jsonPath("$.records[0].bib").value("b"))
                .andExpect(jsonPath("$.records[0].fromCategoryCode").value("A"))
                .andExpect(jsonPath("$.records[0].toCategoryCode").value("B"))
                .andExpect(jsonPath("$.records[0].rankBefore").value(2))
                .andExpect(jsonPath("$.records[0].finishTimeMs").value(2000))
                .andExpect(jsonPath("$.standings[0].categoryCode").value("A"))
                .andExpect(jsonPath("$.standings[0].entries[0].bib").value("a"))
                .andExpect(jsonPath("$.standings[1].categoryCode").value("B"))
                .andExpect(jsonPath("$.standings[1].entries[0].bib").value("c"))
                .andExpect(jsonPath("$.standings[1].entries[1].bib").value("b"))
                .andExpect(jsonPath("$.standings[1].entries[1].rank").value(2));

        // 查询申请与重分记录
        mockMvc.perform(get("/api/races/race-rc/reclass-requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].applicationId").value("app-1"))
                .andExpect(jsonPath("$[0].status").value("APPROVED"));
        mockMvc.perform(get("/api/races/race-rc/reclass-records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].recordId").value("k1:app-1"))
                .andExpect(jsonPath("$[0].operator").value("referee-1"));

        // 迁移后组别成绩
        mockMvc.perform(get("/api/races/race-rc/categories/B/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].bib").value("c"))
                .andExpect(jsonPath("$.entries[1].bib").value("b"))
                .andExpect(jsonPath("$.entries[1].categoryCode").value("B"));

        // 拒绝端点：新申请拒绝后成绩不变
        mockMvc.perform(post("/api/races/race-rc/reclass-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"applicationId":"app-2","bib":"a","targetCategoryCode":"B",
                                 "reason":"再试一次","expectedVersion":8,"requestId":"req-sub-2"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-rc/reclass-requests/app-2/rejection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":9,"requestId":"req-rej-2"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        mockMvc.perform(get("/api/races/race-rc/categories/A/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].bib").value("a"))
                .andExpect(jsonPath("$.entries[0].rank").value(1));
    }

    @Test
    void 错误状态码_容量422与封榜后409() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-rc2","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-rc2/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categoryCode":"A","capacity":1,"expectedVersion":1,"requestId":"req-cat-a"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-rc2/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categoryCode":"B","capacity":1,"expectedVersion":2,"requestId":"req-cat-b"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-rc2/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":1000,"categoryCode":"A",
                                 "expectedVersion":3,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-rc2/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"c","finishTimeMs":1500,"categoryCode":"B",
                                 "expectedVersion":4,"requestId":"req-c"}
                                """))
                .andExpect(status().isCreated());

        // B 容量1已满：a 迁入 -> 422
        mockMvc.perform(post("/api/races/race-rc2/reclass-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"applicationId":"app-1","bib":"a","targetCategoryCode":"B",
                                 "reason":"容量测试","expectedVersion":5,"requestId":"req-sub-1"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-rc2/reclass-approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reclassKey":"k1","operator":"referee-1","expectedVersion":6,
                                 "applicationIds":["app-1"]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));

        // 赛事封榜后批准 -> 409
        mockMvc.perform(post("/api/races/race-rc2/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":6,"requestId":"req-seal"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/races/race-rc2/reclass-approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reclassKey":"k2","operator":"referee-1","expectedVersion":7,
                                 "applicationIds":["app-1"]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));

        // 赛事快照固化提交时刻所属组别
        mockMvc.perform(get("/api/races/race-rc2/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].categoryCode").value("A"))
                .andExpect(jsonPath("$.entries[1].categoryCode").value("B"));
    }
}