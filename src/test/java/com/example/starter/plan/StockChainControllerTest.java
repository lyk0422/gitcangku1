package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 车底交路新接口的 HTTP 边界测试（MockMvc + 真实 H2）：
 * 周转参数登记、整批发布、422 断点明细、参数校验 400 与交路链查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StockChainControllerTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 25);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void turnaroundBatchPublishAndChainQueryOverHttp() throws Exception {
        String stock = key("STK");
        // 首次登记：expectedVersion=0
        MvcResult registered = mvc.perform(put("/api/v1/rolling-stocks/{stockNo}/turnaround", stock)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(turnaroundBody(key("REQ"), 0, 30)))
                .andExpect(status().isOk()).andReturn();
        JsonNode stockView = read(registered);
        assertThat(stockView.get("stockNo").asText()).isEqualTo(stock);
        assertThat(stockView.get("minTurnaroundMinutes").asInt()).isEqualTo(30);
        assertThat(stockView.get("version").asInt()).isEqualTo(1);

        // 分钟数越界 400
        mvc.perform(put("/api/v1/rolling-stocks/{stockNo}/turnaround", stock)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(turnaroundBody(key("REQ"), 1, 241)))
                .andExpect(status().isBadRequest());
        // 版本冲突 409
        mvc.perform(put("/api/v1/rolling-stocks/{stockNo}/turnaround", stock)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(turnaroundBody(key("REQ"), 9, 30)))
                .andExpect(status().isConflict());

        String a = key("SCH");
        String b = key("SCH");
        createStockPlan(a, stock, "BJ", "TJ", 8, 9);
        createStockPlan(b, stock, "TJ", "LF", 9.25, 10);

        // 整批发布：B 与 A 间隔仅 15 分钟 < 30 → 422，断点/实际间隔/要求值齐备
        MvcResult rejected = mvc.perform(post("/api/v1/plans/publish-batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), a, b)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("CHAIN_LINK_CONFLICT");
        JsonNode violation = error.get("details").get(0);
        assertThat(violation.get("type").asText()).isEqualTo("TURNAROUND_INSUFFICIENT");
        assertThat(violation.get("breakpoint").asText()).isEqualTo("TJ->TJ");
        assertThat(violation.get("actualGapMinutes").asLong()).isEqualTo(15L);
        assertThat(violation.get("requiredMinutes").asInt()).isEqualTo(30);

        // 修正 B 为 09:30 始发（间隔恰好 30 分钟）后整批成功
        mvc.perform(put("/api/v1/plans/{key}/occupancies", b)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1, b + "-SEC", 9.5, 10)))
                .andExpect(status().isOk());
        MvcResult published = mvc.perform(post("/api/v1/plans/publish-batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"), a, b)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(published).get("plans")).hasSize(2);

        // 交路链查询
        MvcResult chainResult = mvc.perform(get("/api/v1/rolling-stocks/{stockNo}/chain", stock))
                .andExpect(status().isOk()).andReturn();
        JsonNode chain = read(chainResult);
        assertThat(chain.get("stockNo").asText()).isEqualTo(stock);
        assertThat(chain.get("days").get(0).get("segments")).hasSize(2);
        JsonNode second = chain.get("days").get(0).get("segments").get(1);
        assertThat(second.get("gapMinutes").asLong()).isEqualTo(30L);
        assertThat(second.get("linked").asBoolean()).isTrue();

        // 未登记车底查询 404
        mvc.perform(get("/api/v1/rolling-stocks/{stockNo}/chain", key("MISSING")))
                .andExpect(status().isNotFound());
    }

    // ---------- 辅助 ----------

    private void createStockPlan(String scheduleKey, String stock, String origin, String dest,
                                 double startHour, double endHour) throws Exception {
        String body = "{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\"" + scheduleKey
                + "\",\"opDate\":\"" + DAY + "\",\"stockNo\":\"" + stock
                + "\",\"originStation\":\"" + origin + "\",\"destinationStation\":\"" + dest
                + "\",\"occupancies\":[" + occ(scheduleKey + "-SEC", startHour, endHour) + "]}";
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String occ(String section, double startHour, double endHour) {
        return "{\"trainNo\":\"G-" + UUID.randomUUID() + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + iso(startHour) + "\",\"endUtc\":\"" + iso(endHour) + "\"}";
    }

    private static String updateBody(String requestKey, int expectedVersion, String section,
                                     double startHour, double endHour) {
        return "{\"requestKey\":\"" + requestKey + "\",\"expectedVersion\":" + expectedVersion
                + ",\"occupancies\":[" + occ(section, startHour, endHour) + "]}";
    }

    private static String batchBody(String requestKey, String... keys) {
        String quoted = java.util.Arrays.stream(keys)
                .map(k -> "\"" + k + "\"")
                .reduce((a, b) -> a + "," + b).orElse("");
        return "{\"requestKey\":\"" + requestKey + "\",\"scheduleKeys\":[" + quoted + "]}";
    }

    private static String turnaroundBody(String requestKey, int expectedVersion, int minutes) {
        return "{\"requestKey\":\"" + requestKey + "\",\"expectedVersion\":" + expectedVersion
                + ",\"minTurnaroundMinutes\":" + minutes + "}";
    }

    private static String iso(double hour) {
        long seconds = Math.round(hour * 3600);
        Instant instant = DAY.atStartOfDay(SH).toInstant().plusSeconds(seconds);
        return instant.toString();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
