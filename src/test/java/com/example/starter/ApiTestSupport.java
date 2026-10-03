package com.example.starter;

import java.util.HashMap;
import java.util.Map;

import com.example.starter.support.TestClockConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * API 测试基座：真实 H2 库，逐用例清理场景数据，保留站点种子数据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestClockConfig.class)
public abstract class ApiTestSupport {

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected ObjectMapper objectMapper;

    @BeforeEach
    void cleanScenarioData() {
        jdbc.sql("DELETE FROM rejection_event").update();
        jdbc.sql("DELETE FROM idempotency_record").update();
        jdbc.sql("DELETE FROM tag_transfer").update();
        jdbc.sql("DELETE FROM attachment").update();
        jdbc.sql("DELETE FROM tag").update();
        jdbc.sql("DELETE FROM animal").update();
    }

    protected ResponseEntity<String> post(String url, Map<String, Object> body) {
        return rest.postForEntity(url, body, String.class);
    }

    protected JsonNode json(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("响应不是合法 JSON: " + response.getBody(), e);
        }
    }

    protected Map<String, Object> body(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    protected ResponseEntity<String> registerTag(String tagCode, String stationCode) {
        return post("/api/tags", body("tagCode", tagCode, "stationCode", stationCode));
    }

    protected ResponseEntity<String> attach(String requestId, String tagCode, String animalCode,
            String stationCode, String startAt) {
        return post("/api/attachments", body("requestId", requestId, "tagCode", tagCode,
                "animalCode", animalCode, "stationCode", stationCode, "startAt", startAt));
    }

    protected ResponseEntity<String> detach(String tagCode, String stationCode, String endAt) {
        return post("/api/attachments/detach", body("tagCode", tagCode,
                "stationCode", stationCode, "endAt", endAt));
    }

    protected ResponseEntity<String> initTransfer(String requestId, String tagCode,
            String from, String to, String initiatedAt) {
        return post("/api/transfers", body("requestId", requestId, "tagCode", tagCode,
                "fromStationCode", from, "toStationCode", to, "initiatedAt", initiatedAt));
    }

    protected ResponseEntity<String> confirmTransfer(long id, String stationCode, String confirmedAt) {
        return post("/api/transfers/" + id + "/confirm",
                body("stationCode", stationCode, "confirmedAt", confirmedAt));
    }

    protected long countRows(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}