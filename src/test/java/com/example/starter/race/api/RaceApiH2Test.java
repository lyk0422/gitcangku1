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
        mockMvc.perform(post("/api/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"courseKey":"course-api","requestId":"req-course"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"race-api","courseKey":"course-api","requestId":"req-create"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.courseKey").value("course-api"))
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
                                {"raceId":"r","courseKey":"missing-course","requestId":"c0"}
                                """))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"courseKey":"c","requestId":"c0course"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r","courseKey":"c","requestId":"c1"}
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
        mockMvc.perform(post("/api/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"courseKey":"c2","requestId":"c7course"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r2","courseKey":"c2","requestId":"c7"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/races/r2/snapshot"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 同键同参HTTP重放返回首次状态码与响应() throws Exception {
        mockMvc.perform(post("/api/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"courseKey":"c3","requestId":"c3course"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r3","courseKey":"c3","requestId":"dup-create"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r3","courseKey":"c3","requestId":"dup-create"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));

        // 同键异参 409
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r3-other","courseKey":"c3","requestId":"dup-create"}
                                """))
                .andExpect(status().isConflict());
    }
}
