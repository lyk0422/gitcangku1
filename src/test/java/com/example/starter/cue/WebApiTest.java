package com.example.starter.cue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层冒烟：跳号 409、幂等重放 200、查询冻结序列与结果。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(CueTestConfig.class)
class WebApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM cue_execution");
        jdbc.update("DELETE FROM session_cue");
        jdbc.update("DELETE FROM rehearsal_session");
        jdbc.update("DELETE FROM cue_item");
        jdbc.update("DELETE FROM cue_version");
    }

    private JsonNode postJson(String url, String body, int expected) throws Exception {
        MvcResult result = mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expected)).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void fullChainOverHttp() throws Exception {
        JsonNode version = postJson("/api/versions", """
                {"production":"茶馆","cues":[
                  {"seq":1,"cueType":"LIGHT","triggerPhrase":"幕启","durationSeconds":30},
                  {"seq":2,"cueType":"SOUND","triggerPhrase":"雷声","durationSeconds":10},
                  {"seq":3,"cueType":"LIGHT","triggerPhrase":"幕落","durationSeconds":20}]}
                """, 201);
        long versionId = version.get("versionId").asLong();
        mvc.perform(post("/api/versions/" + versionId + "/freeze"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));
        JsonNode session = postJson("/api/sessions", "{\"production\":\"茶馆\"}", 201);
        long sessionId = session.get("sessionId").asLong();
        String base = "/api/sessions/" + sessionId;
        mvc.perform(post(base + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));

        // 跳号回报 2 被拒（409）
        postJson(base + "/reports",
                "{\"requestId\":\"r1\",\"seq\":2,\"operator\":\"op-a\",\"result\":\"SUCCESS\"}", 409);
        // 先回报 1，同参重放返回原回报，异参 409
        JsonNode first = postJson(base + "/reports",
                "{\"requestId\":\"r1\",\"seq\":1,\"operator\":\"op-a\",\"result\":\"SUCCESS\"}", 200);
        JsonNode replay = postJson(base + "/reports",
                "{\"requestId\":\"r1\",\"seq\":1,\"operator\":\"op-a\",\"result\":\"SUCCESS\"}", 200);
        org.junit.jupiter.api.Assertions.assertEquals(first.get("id").asLong(),
                replay.get("id").asLong());
        postJson(base + "/reports",
                "{\"requestId\":\"r1\",\"seq\":2,\"operator\":\"op-a\",\"result\":\"SUCCESS\"}", 409);
        // 失败回报记录原因但不推进序号，也不消耗幂等键
        postJson(base + "/reports",
                "{\"requestId\":\"r2\",\"seq\":2,\"operator\":\"op-a\",\"result\":\"FAILED\",\"failReason\":\"音响断电\"}", 200);
        postJson(base + "/reports",
                "{\"requestId\":\"r2\",\"seq\":2,\"operator\":\"op-a\",\"result\":\"SUCCESS\"}", 200);
        postJson(base + "/reports",
                "{\"requestId\":\"r3\",\"seq\":3,\"operator\":\"op-b\",\"result\":\"SUCCESS\"}", 200);

        mvc.perform(post(base + "/end"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.completed").value(true));
        mvc.perform(get(base))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cues.length()").value(3))
                .andExpect(jsonPath("$.cues[1].executions.length()").value(2))
                .andExpect(jsonPath("$.cues[1].executions[0].failReason").value("音响断电"));
    }
}