package com.example.starter.calibration;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 迟滞会话测试共享辅助：证书/会话创建、追加、关闭、会话放行的 HTTP 调用封装。
 */
final class SessionTestSupport {

    private SessionTestSupport() {
    }

    static long createHysteresisCert(MockMvc mvc, String instrument,
                                     String slope, String up, String down) throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","type":"HYSTERESIS",
                                 "slope":"%s","upOffset":"%s","downOffset":"%s"}
                                """.formatted(instrument, slope, up, down)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    static long createLinearCert(MockMvc mvc, String instrument, String a, String b) throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"%s","b":"%s"}
                                """.formatted(instrument, a, b)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    static String sessionJson(String requestId, String key, String instrument, long certId,
                              String lower, String upper, String by) {
        return """
                {"requestId":"%s","sessionKey":"%s","instrumentId":"%s","certificateId":%d,
                 "lowerLimit":"%s","upperLimit":"%s","createdBy":"%s"}
                """.formatted(requestId, key, instrument, certId, lower, upper, by);
    }

    static ResultActions createSession(MockMvc mvc, String requestId, String key, String instrument,
                                       long certId, String lower, String upper, String by) throws Exception {
        return mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                .content(sessionJson(requestId, key, instrument, certId, lower, upper, by)));
    }

    static String appendJson(String requestId, long expected, String raw, String direction, String by) {
        String dir = direction == null ? "" : "\"direction\":\"%s\",".formatted(direction);
        return """
                {"requestId":"%s","expectedSessionVersion":%d,"rawValue":"%s",%s"submittedBy":"%s"}
                """.formatted(requestId, expected, raw, dir, by);
    }

    static ResultActions append(MockMvc mvc, String requestId, String key, long expected,
                                String raw, String direction, String by) throws Exception {
        return mvc.perform(post("/api/sessions/{key}/entries", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(appendJson(requestId, expected, raw, direction, by)));
    }

    static ResultActions close(MockMvc mvc, String requestId, String key,
                               long expected, String by) throws Exception {
        return mvc.perform(post("/api/sessions/{key}/close", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","expectedSessionVersion":%d,"closedBy":"%s"}
                        """.formatted(requestId, expected, by)));
    }

    static ResultActions releaseSessions(MockMvc mvc, String requestId, String actor,
                                         String... keys) throws Exception {
        String keyList = java.util.Arrays.stream(keys)
                .map(k -> "\"" + k + "\"")
                .reduce((x, y) -> x + "," + y).orElse("");
        return mvc.perform(post("/api/sessions/release")
                .header("X-Actor-Id", actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"%s\",\"keys\":[%s]}".formatted(requestId, keyList)));
    }
}
