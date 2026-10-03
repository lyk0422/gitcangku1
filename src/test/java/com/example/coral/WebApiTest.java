package com.example.coral;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web层测试：固定UTC时钟验证登记时刻固化，覆盖201/200/409/422与查询响应。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebApiTest {

    /** 可控时钟：所有登记与复核时刻固定为2026-02-01T08:00:00Z。 */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-02-01T08:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM seal_snapshot");
        jdbc.update("DELETE FROM transplant");
        jdbc.update("DELETE FROM quadrat");
    }

    private String json(String body) {
        return body;
    }

    @Test
    void syntheticBusinessCallEndToEnd() throws Exception {
        // 创建样方
        mvc.perform(post("/api/quadrats").contentType(MediaType.APPLICATION_JSON)
                .content(json("{\"quadratCode\":\"Q-WEB\",\"name\":\"合成样方\"}")))
                .andExpect(status().isCreated());
        // 移植登记：固化来源苗圃、样方与UTC时刻
        mvc.perform(post("/api/transplants").contentType(MediaType.APPLICATION_JSON)
                .content(json("{\"requestId\":\"req-w-1\",\"plantCode\":\"P-W-1\","
                        + "\"nursery\":\"东山苗圃\",\"quadratCode\":\"Q-WEB\"}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nursery").value("东山苗圃"))
                .andExpect(jsonPath("$.quadratCode").value("Q-WEB"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.transplantedAt").value("2026-02-01T08:00:00Z"));
        // 同requestId同参重放返回200原记录
        mvc.perform(post("/api/transplants").contentType(MediaType.APPLICATION_JSON)
                .content(json("{\"requestId\":\"req-w-1\",\"plantCode\":\"P-W-1\","
                        + "\"nursery\":\"东山苗圃\",\"quadratCode\":\"Q-WEB\"}")))
                .andExpect(status().isOk());
        // 同requestId异参返回409
        mvc.perform(post("/api/transplants").contentType(MediaType.APPLICATION_JSON)
                .content(json("{\"requestId\":\"req-w-1\",\"plantCode\":\"P-W-OTHER\","
                        + "\"nursery\":\"东山苗圃\",\"quadratCode\":\"Q-WEB\"}")))
                .andExpect(status().isConflict());
        // 缺requestId返回422
        mvc.perform(post("/api/transplants").contentType(MediaType.APPLICATION_JSON)
                .content(json("{\"plantCode\":\"P-W-2\",\"nursery\":\"N\",\"quadratCode\":\"Q-WEB\"}")))
                .andExpect(status().isUnprocessableEntity());
        // 复核为存活
        mvc.perform(post("/api/reviews").contentType(MediaType.APPLICATION_JSON)
                .content(json("{\"requestId\":\"req-w-r1\",\"plantCode\":\"P-W-1\","
                        + "\"result\":\"ALIVE\",\"note\":\"状态良好\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ALIVE"))
                .andExpect(jsonPath("$.reviewedAt").value("2026-02-01T08:00:00Z"));
        // 非法复核结果枚举返回422
        mvc.perform(post("/api/reviews").contentType(MediaType.APPLICATION_JSON)
                .content(json("{\"requestId\":\"req-w-r2\",\"plantCode\":\"P-W-1\","
                        + "\"result\":\"SICK\"}")))
                .andExpect(status().isUnprocessableEntity());
        // 封存并核对快照数量
        mvc.perform(post("/api/quadrats/Q-WEB/seal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aliveCount").value(1))
                .andExpect(jsonPath("$.deadCount").value(0))
                .andExpect(jsonPath("$.plantCodes[0]").value("P-W-1"));
        // 已封存样方新增移植返回409
        mvc.perform(post("/api/transplants").contentType(MediaType.APPLICATION_JSON)
                .content(json("{\"requestId\":\"req-w-3\",\"plantCode\":\"P-W-3\","
                        + "\"nursery\":\"N\",\"quadratCode\":\"Q-WEB\"}")))
                .andExpect(status().isConflict());
        // 查询展示来源、当前样方、复核链和封存数量
        mvc.perform(get("/api/transplants/P-W-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nursery").value("东山苗圃"))
                .andExpect(jsonPath("$.quadratCode").value("Q-WEB"))
                .andExpect(jsonPath("$.reviews[0].result").value("ALIVE"));
        mvc.perform(get("/api/quadrats/Q-WEB"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.aliveCount").value(1))
                .andExpect(jsonPath("$.seal.aliveCount").value(1))
                .andExpect(jsonPath("$.plants[0].reviews[0].note").value("状态良好"));
    }

    @Test
    void duplicatePlantAndCapacityReturn409() throws Exception {
        mvc.perform(post("/api/quadrats").contentType(MediaType.APPLICATION_JSON)
                .content(json("{\"quadratCode\":\"Q-CAP\"}"))).andExpect(status().isCreated());
        for (int i = 1; i <= 12; i++) {
            mvc.perform(post("/api/transplants").contentType(MediaType.APPLICATION_JSON)
                    .content(json("{\"requestId\":\"req-c-" + i + "\",\"plantCode\":\"P-C-" + i
                            + "\",\"nursery\":\"N\",\"quadratCode\":\"Q-CAP\"}")))
                    .andExpect(status().isCreated());
        }
        // 第13株容量已满返回409
        mvc.perform(post("/api/transplants").contentType(MediaType.APPLICATION_JSON)
                .content(json("{\"requestId\":\"req-c-13\",\"plantCode\":\"P-C-13\","
                        + "\"nursery\":\"N\",\"quadratCode\":\"Q-CAP\"}")))
                .andExpect(status().isConflict());
        // 重复植株编号返回409
        mvc.perform(post("/api/transplants").contentType(MediaType.APPLICATION_JSON)
                .content(json("{\"requestId\":\"req-c-dup\",\"plantCode\":\"P-C-1\","
                        + "\"nursery\":\"N\",\"quadratCode\":\"Q-CAP\"}")))
                .andExpect(status().isConflict());
        // 死亡后释放容量可登记新植株，再标存活返回409
        mvc.perform(post("/api/reviews").contentType(MediaType.APPLICATION_JSON)
                .content(json("{\"requestId\":\"req-c-kill\",\"plantCode\":\"P-C-1\","
                        + "\"result\":\"DEAD\"}"))).andExpect(status().isOk());
        mvc.perform(post("/api/transplants").contentType(MediaType.APPLICATION_JSON)
                .content(json("{\"requestId\":\"req-c-new\",\"plantCode\":\"P-C-NEW\","
                        + "\"nursery\":\"N\",\"quadratCode\":\"Q-CAP\"}")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/reviews").contentType(MediaType.APPLICATION_JSON)
                .content(json("{\"requestId\":\"req-c-revive\",\"plantCode\":\"P-C-1\","
                        + "\"result\":\"ALIVE\"}"))).andExpect(status().isConflict());
    }
}