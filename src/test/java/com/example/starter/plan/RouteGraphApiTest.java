package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 线路图替换 API 测试：版本递增、expectedVersion 冲突、参数校验与幂等（H2 内存库）。
 * 图版本为全库共享状态，用例一律先读当前版本再替换，断言相对增量。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RouteGraphApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void replaceGraphCreatesVersionAndIncrements() throws Exception {
        int current = currentVersion();
        String stationA = key("STA");
        String stationB = key("STB");

        MvcResult created = mvc.perform(put("/api/v1/route-graph")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphBody(key("REQ"), "tester", current,
                                stations(stationA, stationB),
                                seg(key("SEG"), stationA, stationB, 5))))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(created);
        assertThat(body.get("version").asInt()).isEqualTo(current + 1);
        assertThat(body.get("stations")).hasSize(2);
        assertThat(body.get("segments")).hasSize(1);
        assertThat(body.get("segments").get(0).get("minutes").asInt()).isEqualTo(5);

        // 再替换一次版本继续加一
        MvcResult next = mvc.perform(put("/api/v1/route-graph")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphBody(key("REQ"), "tester", current + 1,
                                stations(stationA, stationB),
                                seg(key("SEG"), stationA, stationB, 7))))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(next).get("version").asInt()).isEqualTo(current + 2);

        // 当前图查询返回最新版本
        MvcResult currentGraph = mvc.perform(get("/api/v1/route-graph"))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(currentGraph).get("version").asInt()).isEqualTo(current + 2);
    }

    @Test
    void replaceGraphRejectsStaleExpectedVersion() throws Exception {
        int current = currentVersion();
        String stationA = key("STA");
        String stationB = key("STB");
        String body = graphBody(key("REQ"), "tester", current + 1,
                stations(stationA, stationB), seg(key("SEG"), stationA, stationB, 5));

        MvcResult result = mvc.perform(put("/api/v1/route-graph")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("VERSION_CONFLICT");
        // 失败不占版本
        assertThat(currentVersion()).isEqualTo(current);
    }

    @Test
    void replaceGraphRejectsInvalidPayloads() throws Exception {
        int current = currentVersion();
        String stationA = key("STA");
        String stationB = key("STB");

        // 区段端点不在站点清单内
        mvc.perform(put("/api/v1/route-graph").contentType(MediaType.APPLICATION_JSON)
                        .content(graphBody(key("REQ"), "tester", current,
                                stations(stationA), seg(key("SEG"), stationA, stationB, 5))))
                .andExpect(status().isBadRequest());
        // 区段编号重复
        String dupSeg = key("SEG");
        mvc.perform(put("/api/v1/route-graph").contentType(MediaType.APPLICATION_JSON)
                        .content(graphBody(key("REQ"), "tester", current,
                                stations(stationA, stationB),
                                seg(dupSeg, stationA, stationB, 5) + ","
                                        + seg(dupSeg, stationB, stationA, 3))))
                .andExpect(status().isBadRequest());
        // 行驶分钟非正整数
        mvc.perform(put("/api/v1/route-graph").contentType(MediaType.APPLICATION_JSON)
                        .content(graphBody(key("REQ"), "tester", current,
                                stations(stationA, stationB),
                                seg(key("SEG"), stationA, stationB, 0))))
                .andExpect(status().isBadRequest());
        // 站点超过 10 个
        mvc.perform(put("/api/v1/route-graph").contentType(MediaType.APPLICATION_JSON)
                        .content(graphBody(key("REQ"), "tester", current, manyStations(11), "")))
                .andExpect(status().isBadRequest());
        // 区段超过 20 条
        mvc.perform(put("/api/v1/route-graph").contentType(MediaType.APPLICATION_JSON)
                        .content(graphBody(key("REQ"), "tester", current,
                                stations(stationA, stationB), manySegments(stationA, stationB, 21))))
                .andExpect(status().isBadRequest());
        // 缺 operator
        mvc.perform(put("/api/v1/route-graph").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"expectedVersion\":"
                                + current + ",\"stations\":" + stations(stationA, stationB)
                                + ",\"segments\":[]}"))
                .andExpect(status().isBadRequest());

        assertThat(currentVersion()).isEqualTo(current);
    }

    @Test
    void replaceGraphIdempotentReplayAndParamConflict() throws Exception {
        int current = currentVersion();
        String stationA = key("STA");
        String stationB = key("STB");
        String requestKey = key("REQ");
        String body = graphBody(requestKey, "tester", current,
                stations(stationA, stationB), seg(key("SEG"), stationA, stationB, 5));

        MvcResult first = mvc.perform(put("/api/v1/route-graph")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        // 同键同参重放返回首次结果，版本不再前进
        MvcResult replay = mvc.perform(put("/api/v1/route-graph")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        assertThat(currentVersion()).isEqualTo(current + 1);

        // 同键不同参 → 409
        mvc.perform(put("/api/v1/route-graph").contentType(MediaType.APPLICATION_JSON)
                        .content(graphBody(requestKey, "tester", current + 1,
                                stations(stationA, stationB),
                                seg(key("SEG"), stationA, stationB, 9))))
                .andExpect(status().isConflict());
    }

    // ---------- 辅助 ----------

    private int currentVersion() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/route-graph")).andReturn();
        if (result.getResponse().getStatus() == 404) {
            return 0;
        }
        return read(result).get("version").asInt();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String stations(String... stations) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < stations.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(stations[i]).append('"');
        }
        return sb.append(']').toString();
    }

    private static String manyStations(int count) {
        String[] stations = new String[count];
        for (int i = 0; i < count; i++) {
            stations[i] = key("ST") + "-" + i;
        }
        return stations(stations);
    }

    private static String seg(String id, String from, String to, int minutes) {
        return "{\"segmentId\":\"" + id + "\",\"fromStation\":\"" + from
                + "\",\"toStation\":\"" + to + "\",\"minutes\":" + minutes + "}";
    }

    private static String manySegments(String from, String to, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(seg(key("SEG"), from, to, 1));
        }
        return sb.toString();
    }

    private static String graphBody(String requestKey, String operator, int expectedVersion,
                                    String stationsJson, String segmentsJson) {
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"" + operator
                + "\",\"expectedVersion\":" + expectedVersion
                + ",\"stations\":" + stationsJson + ",\"segments\":[" + segmentsJson + "]}";
    }
}
