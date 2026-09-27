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
 * 校验状态码（201/200/404/409/422）、JSON 字段与幂等重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class ReclassApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 组别重分全链路_HTTP状态码与响应体() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-reclass-api","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());

        // 建组别 A/B
        mockMvc.perform(post("/api/races/race-reclass-api/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categoryCode":"A","capacity":10,"expectedVersion":1,"requestId":"req-cat-a"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoryCode").value("A"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.activeCount").value(0));
        mockMvc.perform(post("/api/races/race-reclass-api/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categoryCode":"B","capacity":10,"expectedVersion":2,"requestId":"req-cat-b"}
                                """))
                .andExpect(status().isCreated());

        // 登记选手到组别
        mockMvc.perform(post("/api/races/race-reclass-api/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":1000,"category":"A","expectedVersion":3,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoryCode").value("A"))
                .andExpect(jsonPath("$.withdrawn").value(false));
        mockMvc.perform(post("/api/races/race-reclass-api/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"c","finishTimeMs":1500,"category":"B","expectedVersion":4,"requestId":"req-c"}
                                """))
                .andExpect(status().isCreated());

        // 提交重分申请
        mockMvc.perform(post("/api/races/race-reclass-api/reclass-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","targetCategory":"B","reason":"年龄组调整",
                                 "expectedVersion":5,"requestId":"app-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestId").value("app-1"))
                .andExpect(jsonPath("$.fromCategory").value("A"))
                .andExpect(jsonPath("$.toCategory").value("B"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.raceVersion").value(5));

        // 批量批准：原子迁移并返回两组重算排名
        mockMvc.perform(post("/api/races/race-reclass-api/reclass-approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reclassKey":"key-1","operator":"referee","expectedVersion":6,
                                 "requestIds":["app-1"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value("key-1"))
                .andExpect(jsonPath("$.version").value(7))
                .andExpect(jsonPath("$.approved[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.standings[0].categoryCode").value("A"))
                .andExpect(jsonPath("$.standings[0].entries").isEmpty())
                .andExpect(jsonPath("$.standings[1].categoryCode").value("B"))
                .andExpect(jsonPath("$.standings[1].entries[0].bib").value("a"))
                .andExpect(jsonPath("$.standings[1].entries[0].rank").value(1))
                .andExpect(jsonPath("$.standings[1].entries[1].bib").value("c"))
                .andExpect(jsonPath("$.standings[1].entries[1].rank").value(2));

        // 同键重放完整排名结果
        mockMvc.perform(post("/api/races/race-reclass-api/reclass-approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reclassKey":"key-1","operator":"referee","expectedVersion":6,
                                 "requestIds":["app-1"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value("key-1"))
                .andExpect(jsonPath("$.version").value(7));

        // 查询申请、重分记录与两组排名
        mockMvc.perform(get("/api/races/race-reclass-api/reclass-requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requestId").value("app-1"))
                .andExpect(jsonPath("$[0].status").value("APPROVED"))
                .andExpect(jsonPath("$[0].batchId").value("key-1"));
        mockMvc.perform(get("/api/races/race-reclass-api/reclass-records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bib").value("a"))
                .andExpect(jsonPath("$[0].fromCategory").value("A"))
                .andExpect(jsonPath("$[0].toCategory").value("B"))
                .andExpect(jsonPath("$[0].preRank").value(1))
                .andExpect(jsonPath("$[0].preStatus").value("RANKED"))
                .andExpect(jsonPath("$[0].preFinishTimeMs").value(1000))
                .andExpect(jsonPath("$[0].operator").value("referee"));
        mockMvc.perform(get("/api/races/race-reclass-api/categories/B/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].bib").value("a"));

        // 组别封榜并查询历史快照
        mockMvc.perform(post("/api/races/race-reclass-api/categories/B/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":7,"requestId":"req-seal-b"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.entries[0].bib").value("a"));
        mockMvc.perform(get("/api/races/race-reclass-api/categories/B/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.entries[0].bib").value("a"))
                .andExpect(jsonPath("$.entries[1].bib").value("c"));
    }

    @Test
    void 错误分支状态码_404_409_422() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-reclass-err","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-reclass-err/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categoryCode":"A","capacity":10,"expectedVersion":1,"requestId":"req-cat-a"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-reclass-err/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":1000,"category":"A","expectedVersion":2,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated());

        // 目标组别不存在 -> 404
        mockMvc.perform(post("/api/races/race-reclass-err/reclass-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","targetCategory":"NOPE","reason":"理由",
                                 "expectedVersion":3,"requestId":"app-missing"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));

        // 目标组别与当前相同 -> 422
        mockMvc.perform(post("/api/races/race-reclass-err/reclass-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","targetCategory":"A","reason":"理由",
                                 "expectedVersion":3,"requestId":"app-same"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));

        // 版本冲突 -> 409
        mockMvc.perform(post("/api/races/race-reclass-err/reclass-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","targetCategory":"A","reason":"理由",
                                 "expectedVersion":99,"requestId":"app-stale"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));

        // 批准不存在的申请 -> 422
        mockMvc.perform(post("/api/races/race-reclass-err/reclass-approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reclassKey":"key-x","operator":"referee","expectedVersion":3,
                                 "requestIds":["app-not-exist"]}
                                """))
                .andExpect(status().isUnprocessableEntity());

        // 正常申请后退赛，批准 -> 409
        mockMvc.perform(post("/api/races/race-reclass-err/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categoryCode":"B","capacity":10,"expectedVersion":3,"requestId":"req-cat-b"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-reclass-err/reclass-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","targetCategory":"B","reason":"理由",
                                 "expectedVersion":4,"requestId":"app-1"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-reclass-err/runners/a/withdrawal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":5,"requestId":"req-wd-a"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.withdrawn").value(true));
        mockMvc.perform(post("/api/races/race-reclass-err/reclass-approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reclassKey":"key-wd","operator":"referee","expectedVersion":6,
                                 "requestIds":["app-1"]}
                                """))
                .andExpect(status().isConflict());

        // 重复退赛 -> 409
        mockMvc.perform(post("/api/races/race-reclass-err/runners/a/withdrawal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":6,"requestId":"req-wd-a2"}
                                """))
                .andExpect(status().isConflict());

        // 未封榜组别查询快照 -> 404
        mockMvc.perform(get("/api/races/race-reclass-err/categories/A/snapshot"))
                .andExpect(status().isNotFound());
        // 不存在的组别 -> 404
        mockMvc.perform(get("/api/races/race-reclass-err/categories/NOPE/results"))
                .andExpect(status().isNotFound());
    }
}
