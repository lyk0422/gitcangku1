package com.example.starter.observation.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通过 MockMvc 验证 HTTP 契约：状态码、冲突/墓碑 JSON 结构、幂等请求头与参数校验。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ObservationApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM dedup_record");
    }

    @Test
    void createSubmitQueryAndDeleteHappyPathOverHttp() throws Exception {
        mockMvc.perform(post("/api/observations")
                        .header("X-Request-Id", "http-req-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"observationId":"obs-a","location":"1号井","reading":"12.345","remark":"晴"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.observationId").value("obs-a"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.reading").value("12.345"));

        mockMvc.perform(post("/api/observations/obs-a/submit")
                        .header("X-Request-Id", "http-req-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseVersion":1,"location":"1号井","reading":"13.000","remark":"晴"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.reading").value("13.000"));

        mockMvc.perform(get("/api/observations/obs-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(get("/api/observations/obs-a/versions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reading").value("12.345"));

        mockMvc.perform(post("/api/observations/obs-a/delete")
                        .header("X-Request-Id", "http-req-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.location").doesNotExist())
                .andExpect(jsonPath("$.reading").doesNotExist())
                .andExpect(jsonPath("$.remark").doesNotExist());

        mockMvc.perform(get("/api/observations/obs-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.location").doesNotExist());
    }

    @Test
    void conflictReturns409WithFieldsAndCurrentVersion() throws Exception {
        mockMvc.perform(post("/api/observations")
                .header("X-Request-Id", "http-req-4")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"observationId":"obs-b","location":"A","reading":"1.000","remark":"r"}
                        """)).andExpect(status().isCreated());
        // 当前 v2：地点与读数被修改。
        mockMvc.perform(post("/api/observations/obs-b/submit")
                .header("X-Request-Id", "http-req-5")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"baseVersion":1,"location":"B","reading":"2.000","remark":"r"}
                        """)).andExpect(status().isOk());
        // 候选基于 v1：地点改成 C（与当前 B 冲突），读数未改，备注未改。
        mockMvc.perform(post("/api/observations/obs-b/submit")
                        .header("X-Request-Id", "http-req-6")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseVersion":1,"location":"C","reading":"1.000","remark":"r"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.conflictFields[0]").value("location"))
                .andExpect(jsonPath("$.conflictFields.length()").value(1));
    }

    @Test
    void missingBaselineAndUnknownObservationReturn404() throws Exception {
        mockMvc.perform(post("/api/observations")
                .header("X-Request-Id", "http-req-7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"observationId":"obs-c","location":"A","reading":"1.000","remark":"r"}
                        """)).andExpect(status().isCreated());
        mockMvc.perform(post("/api/observations/obs-c/submit")
                        .header("X-Request-Id", "http-req-8")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseVersion":9,"location":"A","reading":"1.000","remark":"r"}
                                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/observations/obs-c/versions/9"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/observations/no-such"))
                .andExpect(status().isNotFound());
    }

    @Test
    void writesOnTombstoneReturn410() throws Exception {
        mockMvc.perform(post("/api/observations")
                .header("X-Request-Id", "http-req-9")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"observationId":"obs-d","location":"A","reading":"1.000","remark":"r"}
                        """)).andExpect(status().isCreated());
        mockMvc.perform(post("/api/observations/obs-d/delete")
                        .header("X-Request-Id", "http-req-10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":1}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/observations/obs-d/submit")
                        .header("X-Request-Id", "http-req-11")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseVersion":2,"location":"A","reading":"1.000","remark":"r"}
                                """))
                .andExpect(status().isGone());
        mockMvc.perform(post("/api/observations/obs-d/delete")
                        .header("X-Request-Id", "http-req-12")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":2}
                                """))
                .andExpect(status().isGone());
    }

    @Test
    void deleteVersionMismatchReturns409() throws Exception {
        mockMvc.perform(post("/api/observations")
                .header("X-Request-Id", "http-req-13")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"observationId":"obs-e","location":"A","reading":"1.000","remark":"r"}
                        """)).andExpect(status().isCreated());
        mockMvc.perform(post("/api/observations/obs-e/delete")
                        .header("X-Request-Id", "http-req-14")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":5}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(1));
    }

    @Test
    void sameRequestIdReplaysAndDifferentParamsConflict() throws Exception {
        String body = """
                {"observationId":"obs-f","location":"A","reading":"1.000","remark":"r"}
                """;
        mockMvc.perform(post("/api/observations").header("X-Request-Id", "http-req-15")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/observations").header("X-Request-Id", "http-req-15")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));

        String submit1 = """
                {"baseVersion":1,"location":"A","reading":"2.000","remark":"r"}
                """;
        mockMvc.perform(post("/api/observations/obs-f/submit").header("X-Request-Id", "http-req-16")
                        .contentType(MediaType.APPLICATION_JSON).content(submit1))
                .andExpect(status().isOk());
        String submit2 = """
                {"baseVersion":1,"location":"A","reading":"3.000","remark":"r"}
                """;
        mockMvc.perform(post("/api/observations/obs-f/submit").header("X-Request-Id", "http-req-16")
                        .contentType(MediaType.APPLICATION_JSON).content(submit2))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidReadingFormatReturns400() throws Exception {
        mockMvc.perform(post("/api/observations")
                        .header("X-Request-Id", "http-req-17")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"observationId":"obs-g","location":"A","reading":"1.2345","remark":"r"}
                                """))
                .andExpect(status().isBadRequest());
    }
}
