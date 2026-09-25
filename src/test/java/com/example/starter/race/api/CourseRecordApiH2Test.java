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
 * 赛道登记与赛道纪录 API 的端到端 HTTP 测试：真实 Spring MVC + Service + H2，
 * 校验认定主流程、前置条件状态码（404/409/422）、422 携带实际当前纪录与只读查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class CourseRecordApiH2Test extends AbstractRaceH2Test {

    @Autowired
    private MockMvc mockMvc;

    private void registerCourse(String courseKey, String requestId) throws Exception {
        mockMvc.perform(post("/api/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"courseKey":"%s","requestId":"%s"}
                                """.formatted(courseKey, requestId)))
                .andExpect(status().isCreated());
    }

    private void seedSealedRace(String courseKey, String raceId, String bib, long timeMs)
            throws Exception {
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"%s","courseKey":"%s","requestId":"req-create-%s"}
                                """.formatted(raceId, courseKey, raceId)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/" + raceId + "/runners")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bib":"%s","finishTimeMs":%d,"expectedVersion":1,"requestId":"req-reg-%s"}
                                """.formatted(bib, timeMs, raceId)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/races/" + raceId + "/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":2,"requestId":"req-seal-%s"}
                                """.formatted(raceId)))
                .andExpect(status().isOk());
    }

    @Test
    void 认定主流程与当前纪录及历史链查询() throws Exception {
        registerCourse("c1", "req-course-1");
        seedSealedRace("c1", "r1", "a", 1000L);
        seedSealedRace("c1", "r2", "b", 900L);

        // 尚无纪录：当前纪录 404，历史链为空
        mockMvc.perform(get("/api/courses/c1/record"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/courses/c1/record-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseKey").value("c1"))
                .andExpect(jsonPath("$.records").isEmpty());

        // 首次认定：无纪录时合法完赛计时即可
        mockMvc.perform(post("/api/courses/c1/record-claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recordClaimKey":"ck-1","raceId":"r1","bib":"a","requestId":"req-ck-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.courseKey").value("c1"))
                .andExpect(jsonPath("$.raceId").value("r1"))
                .andExpect(jsonPath("$.bib").value("a"))
                .andExpect(jsonPath("$.timeMs").value(1000))
                .andExpect(jsonPath("$.recordClaimKey").value("ck-1"))
                .andExpect(jsonPath("$.createdAt").exists());

        mockMvc.perform(get("/api/courses/c1/record"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeMs").value(1000))
                .andExpect(jsonPath("$.raceId").value("r1"));

        // 更优认定：原子替换，旧纪录进入历史链
        mockMvc.perform(post("/api/courses/c1/record-claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recordClaimKey":"ck-2","raceId":"r2","bib":"b","requestId":"req-ck-2"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.timeMs").value(900));

        mockMvc.perform(get("/api/courses/c1/record"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeMs").value(900))
                .andExpect(jsonPath("$.raceId").value("r2"));

        mockMvc.perform(get("/api/courses/c1/record-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records.length()").value(2))
                .andExpect(jsonPath("$.records[0].timeMs").value(1000))
                .andExpect(jsonPath("$.records[0].raceId").value("r1"))
                .andExpect(jsonPath("$.records[1].timeMs").value(900))
                .andExpect(jsonPath("$.records[1].raceId").value("r2"));
    }

    @Test
    void 认定错误分支状态码_404_409_422() throws Exception {
        registerCourse("c2", "req-course-2");
        seedSealedRace("c2", "r1", "a", 900L);
        seedSealedRace("c2", "r2", "b", 950L);

        // 未登记赛道 404
        mockMvc.perform(get("/api/courses/ghost/record"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        mockMvc.perform(post("/api/courses/ghost/record-claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recordClaimKey":"x","raceId":"r1","bib":"a","requestId":"req-x"}
                                """))
                .andExpect(status().isNotFound());

        // 未封榜赛事 409
        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r-open","courseKey":"c2","requestId":"req-create-open"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/courses/c2/record-claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recordClaimKey":"ck-open","raceId":"r-open","bib":"a","requestId":"req-ck-open"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));

        // 首次认定成功
        mockMvc.perform(post("/api/courses/c2/record-claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recordClaimKey":"ck-1","raceId":"r1","bib":"a","requestId":"req-ck-1"}
                                """))
                .andExpect(status().isCreated());

        // 不严格更优 422，响应携带实际当前纪录
        mockMvc.perform(post("/api/courses/c2/record-claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recordClaimKey":"ck-2","raceId":"r2","bib":"b","requestId":"req-ck-2"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNPROCESSABLE"))
                .andExpect(jsonPath("$.currentRecord.timeMs").value(900))
                .andExpect(jsonPath("$.currentRecord.raceId").value("r1"));

        // 快照中不存在的选手 404
        mockMvc.perform(post("/api/courses/c2/record-claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recordClaimKey":"ck-3","raceId":"r1","bib":"ghost","requestId":"req-ck-3"}
                                """))
                .andExpect(status().isNotFound());

        // 参数校验失败 400：缺少 recordClaimKey
        mockMvc.perform(post("/api/courses/c2/record-claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r1","bib":"a","requestId":"req-ck-4"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 重复赛道登记409且未登记赛道不能建赛() throws Exception {
        registerCourse("c3", "req-course-3");
        mockMvc.perform(post("/api/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"courseKey":"c3","requestId":"req-course-3-dup"}
                                """))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/races")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"raceId":"r-ghost","courseKey":"ghost-course","requestId":"req-create-ghost"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void 同一recordClaimKey重复申请HTTP幂等返回首次结果() throws Exception {
        registerCourse("c4", "req-course-4");
        seedSealedRace("c4", "r1", "a", 1000L);

        String firstBody = mockMvc.perform(post("/api/courses/c4/record-claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recordClaimKey":"ck-dup","raceId":"r1","bib":"a","requestId":"req-ck-a"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recordId").exists())
                .andExpect(jsonPath("$.timeMs").value(1000))
                .andReturn().getResponse().getContentAsString();

        // 相同 recordClaimKey、不同 requestId：幂等返回首次认定结果
        mockMvc.perform(post("/api/courses/c4/record-claims")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recordClaimKey":"ck-dup","raceId":"r1","bib":"a","requestId":"req-ck-b"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().json(firstBody));

        mockMvc.perform(get("/api/courses/c4/record-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records.length()").value(1));
    }
}
