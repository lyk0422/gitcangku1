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
 * 组别重分 API 端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 覆盖建组别、申请、批准、两组排名、重分记录、组别封榜快照与错误状态码。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class ReclassApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 组别重分全链路_申请批准两组排名与历史快照() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-rc","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/races/race-rc/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categoryCode":"CAT_A","capacity":5,"expectedVersion":1,"requestId":"req-ca"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoryCode").value("CAT_A"))
                .andExpect(jsonPath("$.status").value("OPEN"));
        mockMvc.perform(post("/api/races/race-rc/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categoryCode":"CAT_B","expectedVersion":2,"requestId":"req-cb"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/races/race-rc/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a1","categoryCode":"CAT_A","finishTimeMs":1000,
                                 "expectedVersion":3,"requestId":"req-a1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoryCode").value("CAT_A"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(post("/api/races/race-rc/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b1","categoryCode":"CAT_B","finishTimeMs":3000,
                                 "expectedVersion":4,"requestId":"req-b1"}
                                """))
                .andExpect(status().isCreated());

        // 提交重分申请
        mockMvc.perform(post("/api/races/race-rc/reclass-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"applicationId":"app-1","bib":"a1","targetCategory":"CAT_B",
                                 "reason":"年龄组报错","expectedVersion":5,"requestId":"req-app-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.targetCategory").value("CAT_B"));

        // 批量批准：返回两组重算排名与不可变记录
        mockMvc.perform(post("/api/races/race-rc/reclass-approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reclassKey":"batch-1","operator":"referee","expectedVersion":6,
                                 "applicationIds":["app-1"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value("batch-1"))
                .andExpect(jsonPath("$.version").value(7))
                .andExpect(jsonPath("$.records[0].bib").value("a1"))
                .andExpect(jsonPath("$.records[0].fromCategory").value("CAT_A"))
                .andExpect(jsonPath("$.records[0].toCategory").value("CAT_B"))
                .andExpect(jsonPath("$.records[0].prevRank").value(1))
                .andExpect(jsonPath("$.standings.CAT_B[0].bib").value("a1"))
                .andExpect(jsonPath("$.standings.CAT_B[1].bib").value("b1"));

        // 查询申请 / 重分记录 / 两组排名
        mockMvc.perform(get("/api/races/race-rc/reclass-requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("APPROVED"))
                .andExpect(jsonPath("$[0].batchId").value("batch-1"));
        mockMvc.perform(get("/api/races/race-rc/reclass-records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bib").value("a1"))
                .andExpect(jsonPath("$[0].prevFinishTimeMs").value(1000));
        mockMvc.perform(get("/api/races/race-rc/categories/CAT_A/standings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isEmpty());
        mockMvc.perform(get("/api/races/race-rc/categories/CAT_B/standings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].bib").value("a1"))
                .andExpect(jsonPath("$.entries[0].rank").value(1));

        // 组别封榜后排名来自快照，历史快照可查
        mockMvc.perform(post("/api/races/race-rc/categories/CAT_B/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":7,"requestId":"req-seal-b"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.entries[0].bib").value("a1"));
        mockMvc.perform(get("/api/races/race-rc/category-snapshots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].categoryCode").value("CAT_B"))
                .andExpect(jsonPath("$[0].entries[0].bib").value("a1"));
        mockMvc.perform(get("/api/races/race-rc/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].status").value("SEALED"));
    }

    @Test
    void 错误分支状态码_422_409_404() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-rc-err","requestId":"e-create"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-rc-err/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categoryCode":"CAT_A","capacity":1,"expectedVersion":1,"requestId":"e-ca"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-rc-err/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categoryCode":"CAT_B","capacity":1,"expectedVersion":2,"requestId":"e-cb"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-rc-err/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a1","categoryCode":"CAT_A","finishTimeMs":1000,
                                 "expectedVersion":3,"requestId":"e-a1"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-rc-err/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b1","categoryCode":"CAT_B","finishTimeMs":2000,
                                 "expectedVersion":4,"requestId":"e-b1"}
                                """))
                .andExpect(status().isCreated());

        // 目标组别不存在 → 422
        mockMvc.perform(post("/api/races/race-rc-err/reclass-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"applicationId":"app-x","bib":"a1","targetCategory":"NO_SUCH",
                                 "reason":"r","expectedVersion":5,"requestId":"e-app-x"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE_ENTITY"));

        // 正常申请（目标 CAT_B 容量1已满）→ 批准整批 422
        mockMvc.perform(post("/api/races/race-rc-err/reclass-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"applicationId":"app-1","bib":"a1","targetCategory":"CAT_B",
                                 "reason":"r","expectedVersion":5,"requestId":"e-app-1"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/race-rc-err/reclass-approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reclassKey":"bk-1","operator":"referee","expectedVersion":6,
                                 "applicationIds":["app-1"]}
                                """))
                .andExpect(status().isUnprocessableEntity());

        // 版本冲突 → 409
        mockMvc.perform(post("/api/races/race-rc-err/reclass-approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reclassKey":"bk-2","operator":"referee","expectedVersion":99,
                                 "applicationIds":["app-1"]}
                                """))
                .andExpect(status().isConflict());

        // 申请不存在 → 404
        mockMvc.perform(post("/api/races/race-rc-err/reclass-requests/no-such/rejection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":6,"requestId":"e-rej-x"}
                                """))
                .andExpect(status().isNotFound());

        // 拒绝申请：状态迁移且成绩不变
        mockMvc.perform(post("/api/races/race-rc-err/reclass-requests/app-1/rejection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":6,"requestId":"e-rej-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        mockMvc.perform(get("/api/races/race-rc-err/categories/CAT_A/standings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].bib").value("a1"))
                .andExpect(jsonPath("$.entries[0].rank").value(1));

        // 参数校验失败 400：缺少 reclassKey
        mockMvc.perform(post("/api/races/race-rc-err/reclass-approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operator":"referee","expectedVersion":7,"applicationIds":["app-1"]}
                                """))
                .andExpect(status().isBadRequest());
    }
}
