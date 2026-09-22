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
 * 封榜 API 的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 校验状态码（201/200/404/409/400）、JSON 字段与幂等重放状态。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class RaceApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 建赛登记查询成绩到封榜快照全链路() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-api","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("OPEN"));

        mockMvc.perform(post("/api/races/race-api/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":1500,"expectedVersion":1,"requestId":"req-a"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bib").value("a"))
                .andExpect(jsonPath("$.finishTimeMs").value(1500));

        mockMvc.perform(post("/api/races/race-api/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b","expectedVersion":2,"requestId":"req-b"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.finishTimeMs").doesNotExist());

        mockMvc.perform(get("/api/races/race-api/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.entries[0].bib").value("a"))
                .andExpect(jsonPath("$.entries[0].rank").value(1))
                .andExpect(jsonPath("$.entries[1].bib").value("b"))
                .andExpect(jsonPath("$.entries[1].status").value("UNTIMED"))
                .andExpect(jsonPath("$.entries[1].rank").doesNotExist());

        mockMvc.perform(post("/api/races/race-api/timing-revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"b","finishTimeMs":1500,"expectedVersion":3,"requestId":"req-tb"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/races/race-api/penalties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"penaltyId":"pen-1","bib":"b","type":"ADD_TIME","amountMs":100,
                                 "expectedVersion":4,"requestId":"req-pen"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/races/race-api/results"))
                .andExpect(jsonPath("$.entries[0].bib").value("a"))
                .andExpect(jsonPath("$.entries[0].rank").value(1))
                .andExpect(jsonPath("$.entries[1].bib").value("b"))
                .andExpect(jsonPath("$.entries[1].rank").value(2))
                .andExpect(jsonPath("$.entries[1].penaltyMs").value(100))
                .andExpect(jsonPath("$.entries[1].totalTimeMs").value(1600));

        mockMvc.perform(post("/api/races/race-api/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":5,"requestId":"req-seal"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.version").value(6))
                .andExpect(jsonPath("$.sealedAt").exists());

        mockMvc.perform(get("/api/races/race-api/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.entries[1].totalTimeMs").value(1600));
    }

    @Test
    void 错误分支状态码_404_409_400() throws Exception {
        mockMvc.perform(get("/api/races/missing/results"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));

        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r","requestId":"c1"}
                                """))
                .andExpect(status().isCreated());

        // 版本冲突 409
        mockMvc.perform(post("/api/races/r/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":1,"expectedVersion":9,"requestId":"c2"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));

        // 参数校验失败 400：缺少 requestId
        mockMvc.perform(post("/api/races/r/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","expectedVersion":1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        // 业务参数错误 400：加时越界
        mockMvc.perform(post("/api/races/r/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":1,"expectedVersion":1,"requestId":"c3"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/r/penalties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"penaltyId":"p","bib":"a","type":"ADD_TIME","amountMs":99999999,
                                 "expectedVersion":2,"requestId":"c4"}
                                """))
                .andExpect(status().isBadRequest());

        // 封榜后写入 409
        mockMvc.perform(post("/api/races/r/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":2,"requestId":"c5"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/races/r/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"z","finishTimeMs":1,"expectedVersion":3,"requestId":"c6"}
                                """))
                .andExpect(status().isConflict());
        // 未封榜赛事查快照 404
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r2","requestId":"c7"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/races/r2/snapshot"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 同键同参HTTP重放返回首次状态码与响应() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r3","requestId":"dup-create"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r3","requestId":"dup-create"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));

        // 同键异参 409
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r3-other","requestId":"dup-create"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void 分段计时全链路_配置_乱序提交_漏点_422_封榜() throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-split-api","requestId":"sp-create"}
                                """))
                .andExpect(status().isCreated());

        // 配置检查点（v1 -> v2）
        mockMvc.perform(post("/api/races/race-split-api/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkpointCodes":["cp1","cp2"],"expectedVersion":1,
                                 "requestId":"sp-cp"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.checkpoints[0].checkpointCode").value("cp1"))
                .andExpect(jsonPath("$.checkpoints[0].seq").value(1))
                .andExpect(jsonPath("$.checkpoints[1].seq").value(2));

        // 重复配置 409
        mockMvc.perform(post("/api/races/race-split-api/checkpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkpointCodes":["cpA"],"expectedVersion":2,
                                 "requestId":"sp-cp-again"}
                                """))
                .andExpect(status().isConflict());

        // 登记选手（v2 -> v3）
        mockMvc.perform(post("/api/races/race-split-api/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"a","finishTimeMs":10000,"expectedVersion":2,
                                 "requestId":"sp-reg-a"}
                                """))
                .andExpect(status().isCreated());

        // 已完赛但未覆盖检查点 -> MISSING_CHECKPOINT 不排名
        mockMvc.perform(get("/api/races/race-split-api/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].status").value("MISSING_CHECKPOINT"))
                .andExpect(jsonPath("$.entries[0].rank").doesNotExist())
                .andExpect(jsonPath("$.entries[0].splits[0].checkpointCode").value("cp1"))
                .andExpect(jsonPath("$.entries[0].splits[0].elapsedMillis").doesNotExist());

        // 乱序提交：先 cp2 后 cp1（v3 -> v5）
        mockMvc.perform(post("/api/races/race-split-api/runners/a/splits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkpointCode":"cp2","elapsedMillis":5000,
                                 "expectedVersion":3,"timingId":"t-2","requestId":"sp-s2"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.timingId").value("t-2"))
                .andExpect(jsonPath("$.seq").value(2));

        // 相邻约束违反 -> 422，且不写入
        mockMvc.perform(post("/api/races/race-split-api/runners/a/splits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkpointCode":"cp1","elapsedMillis":9000,
                                 "expectedVersion":4,"timingId":"t-1","requestId":"sp-s1"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE"));

        // 修正后成功（v4 -> v5），恢复排名
        mockMvc.perform(post("/api/races/race-split-api/runners/a/splits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkpointCode":"cp1","elapsedMillis":1000,
                                 "expectedVersion":4,"timingId":"t-1","requestId":"sp-s1"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/races/race-split-api/results"))
                .andExpect(jsonPath("$.entries[0].status").value("RANKED"))
                .andExpect(jsonPath("$.entries[0].rank").value(1))
                .andExpect(jsonPath("$.entries[0].splits[0].elapsedMillis").value(1000))
                .andExpect(jsonPath("$.entries[0].splits[1].elapsedMillis").value(5000));

        // timingId 同参重放（旧版本号）仍返回原结果
        mockMvc.perform(post("/api/races/race-split-api/runners/a/splits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkpointCode":"cp1","elapsedMillis":1000,
                                 "expectedVersion":3,"timingId":"t-1","requestId":"sp-s1-retry"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.timingId").value("t-1"));
        // timingId 异参 409
        mockMvc.perform(post("/api/races/race-split-api/runners/a/splits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkpointCode":"cp1","elapsedMillis":2000,
                                 "expectedVersion":5,"timingId":"t-1","requestId":"sp-s1-diff"}
                                """))
                .andExpect(status().isConflict());

        // 单选手分段查询与缺失汇总
        mockMvc.perform(get("/api/races/race-split-api/runners/a/splits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.splits[0].checkpointCode").value("cp1"))
                .andExpect(jsonPath("$.splits[0].elapsedMillis").value(1000))
                .andExpect(jsonPath("$.splits[1].elapsedMillis").value(5000));
        mockMvc.perform(get("/api/races/race-split-api/missing-checkpoints"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runners").isEmpty());

        // 封榜（v5 -> v6）后禁止新增分段
        mockMvc.perform(post("/api/races/race-split-api/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":5,"requestId":"sp-seal"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.entries[0].splits[0].elapsedMillis").value(1000));
        mockMvc.perform(post("/api/races/race-split-api/runners/a/splits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkpointCode":"cp1","elapsedMillis":500,
                                 "expectedVersion":6,"timingId":"t-9","requestId":"sp-s9"}
                                """))
                .andExpect(status().isConflict());
    }
}
