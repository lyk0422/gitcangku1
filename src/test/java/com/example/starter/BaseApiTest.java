package com.example.starter;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * API 测试基类：真实 Spring 上下文 + H2 内存库（MODE=MySQL），每个用例前清空场景数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class BaseApiTest {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper mapper;

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("DELETE FROM package_item").update();
        jdbc.sql("DELETE FROM seal_event").update();
        jdbc.sql("DELETE FROM request_log").update();
        jdbc.sql("DELETE FROM sample").update();
        jdbc.sql("DELETE FROM seal_package").update();
        jdbc.sql("DELETE FROM stratum").update();
    }

    protected ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }

    protected void registerStratum(String siteCode, String stratumNo) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("siteCode", siteCode);
        body.put("stratumNo", stratumNo);
        body.put("description", "合成测试层位");
        postJson("/api/strata", body).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
    }

    protected Map<String, Object> collectBody(String requestId, String sampleNo, String stratumNo, String gridRef) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("sampleNo", sampleNo);
        body.put("siteCode", "SITE-T");
        body.put("stratumNo", stratumNo);
        body.put("gridRef", gridRef);
        return body;
    }

    protected void collect(String requestId, String sampleNo, String stratumNo, String gridRef) throws Exception {
        postJson("/api/samples/collect", collectBody(requestId, sampleNo, stratumNo, gridRef))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
    }

    protected long countRows(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}