package com.example.starter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 测试基座：真实 H2 库，逐用例清理场景数据并重置可控时钟。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
abstract class BaseWebTest {

    static final Instant T0 = TestClockConfig.T0;
    static final Instant TARE_TIME = T0;
    static final Instant GROSS_TIME = T0.plus(Duration.ofMinutes(20));

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper om;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MutableClock clock;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM errata_request");
        jdbc.update("DELETE FROM settlement_freeze");
        jdbc.update("DELETE FROM dispute");
        jdbc.update("DELETE FROM reweigh_request");
        jdbc.update("DELETE FROM weigh_ticket");
        clock.set(T0);
    }

    MvcResult postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body))).andReturn();
    }

    MvcResult getJson(String url) throws Exception {
        return mvc.perform(get(url)).andReturn();
    }

    JsonNode body(MvcResult r) throws Exception {
        return om.readTree(r.getResponse().getContentAsString());
    }

    /** 建单并登记皮重8000、毛重12000，返回称量单ID。 */
    long grossedTicket(String requestPrefix) throws Exception {
        long id = createTicket(requestPrefix + "-create");
        clock.set(TARE_TIME);
        postJson("/api/weigh-tickets/" + id + "/tare",
                Map.of("requestId", requestPrefix + "-tare", "tareKg", 8000, "tareTimeUtc", TARE_TIME.toString()));
        clock.set(GROSS_TIME);
        MvcResult gross = postJson("/api/weigh-tickets/" + id + "/gross",
                Map.of("requestId", requestPrefix + "-gross", "grossKg", 12000, "grossTimeUtc", GROSS_TIME.toString()));
        if (gross.getResponse().getStatus() != 201) {
            throw new IllegalStateException("gross failed: " + gross.getResponse().getContentAsString());
        }
        return id;
    }

    long createTicket(String requestId) throws Exception {
        MvcResult r = postJson("/api/weigh-tickets",
                Map.of("requestId", requestId, "transportOrderNo", "TO-SYN-0001", "vehicleNo", "SYN-TRUCK-01"));
        if (r.getResponse().getStatus() != 201) {
            throw new IllegalStateException("create failed: " + r.getResponse().getContentAsString());
        }
        return body(r).get("id").asLong();
    }
}