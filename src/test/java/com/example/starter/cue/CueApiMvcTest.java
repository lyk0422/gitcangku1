package com.example.starter.cue;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** HTTP层链路测试：编排、冻结、场次、按序回报与409冲突。 */
@AutoConfigureMockMvc
class CueApiMvcTest extends CueTestSupport {

    @Autowired
    private MockMvc mvc;

    @Test
    void fullChainOverHttp() throws Exception {
        long versionId = createVersion("["
                + "{\"seq\":1,\"cueType\":\"LIGHT\",\"triggerPhrase\":\"开幕\",\"durationSeconds\":30},"
                + "{\"seq\":2,\"cueType\":\"SOUND\",\"triggerPhrase\":\"雷声\",\"durationSeconds\":5},"
                + "{\"seq\":3,\"cueType\":\"LIGHT\",\"triggerPhrase\":\"谢幕\",\"durationSeconds\":20}]");

        mvc.perform(post("/api/cue-versions/{id}/freeze", versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));

        MvcResult sessionResult = mvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionId\":" + versionId + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        long sessionId = Long.parseLong(
                com.jayway.jsonpath.JsonPath.read(sessionResult.getResponse().getContentAsString(), "$.sessionId")
                        .toString());

        mvc.perform(post("/api/sessions/{id}/start", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));

        // 先回报2应被拒（409）
        mvc.perform(post("/api/sessions/{id}/reports", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-2\",\"seq\":2,\"success\":true}"))
                .andExpect(status().isConflict());

        // 回报1后才可执行2
        mvc.perform(post("/api/sessions/{id}/reports", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-1\",\"seq\":1,\"success\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("recorded"));
        mvc.perform(post("/api/sessions/{id}/reports", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-2\",\"seq\":2,\"success\":true}"))
                .andExpect(status().isOk());

        // 同requestId同参重放原回报，异参409
        mvc.perform(post("/api/sessions/{id}/reports", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-2\",\"seq\":2,\"success\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("replayed"));
        mvc.perform(post("/api/sessions/{id}/reports", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-2\",\"seq\":3,\"success\":true}"))
                .andExpect(status().isConflict());

        // 未执行完不能结束
        mvc.perform(post("/api/sessions/{id}/finish", sessionId))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/sessions/{id}/reports", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-3\",\"seq\":3,\"success\":true}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/sessions/{id}/finish", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINISHED"));

        // 查询冻结序列与每个提示点执行结果
        mvc.perform(get("/api/sessions/{id}", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cues.length()").value(3))
                .andExpect(jsonPath("$.cues[0].cue.triggerPhrase").value("开幕"))
                .andExpect(jsonPath("$.cues[0].execution.seq").value(1));
    }

    @Test
    void freezeInvalidVersionReturnsConflict() throws Exception {
        long versionId = createVersion(
                "[{\"seq\":1,\"cueType\":\"LIGHT\",\"triggerPhrase\":\"超时\",\"durationSeconds\":601}]");

        mvc.perform(post("/api/cue-versions/{id}/freeze", versionId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void versionDiffOverHttp() throws Exception {
        long v1 = createVersion(
                "[{\"seq\":1,\"cueType\":\"LIGHT\",\"triggerPhrase\":\"开幕\",\"durationSeconds\":30}]");
        long v2 = createVersion("["
                + "{\"seq\":1,\"cueType\":\"LIGHT\",\"triggerPhrase\":\"开幕\",\"durationSeconds\":30},"
                + "{\"seq\":2,\"cueType\":\"SOUND\",\"triggerPhrase\":\"雷声\",\"durationSeconds\":5}]");

        mvc.perform(get("/api/cue-versions/diff").param("from", String.valueOf(v1))
                        .param("to", String.valueOf(v2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added.length()").value(1))
                .andExpect(jsonPath("$.added[0].seq").value(2));
    }

    private long createVersion(String cuesJson) throws Exception {
        MvcResult result = mvc.perform(post("/api/cue-versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cues\":" + cuesJson + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        return Long.parseLong(com.jayway.jsonpath.JsonPath
                .read(result.getResponse().getContentAsString(), "$.versionId").toString());
    }
}