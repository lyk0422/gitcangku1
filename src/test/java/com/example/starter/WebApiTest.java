package com.example.starter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP 层端到端：状态码映射（201/200/409/422）、时间线包含交接确认时刻与拒绝原因。
 */
@AutoConfigureMockMvc
class WebApiTest extends AbstractDatabaseTest {

    @Autowired
    private MockMvc mvc;

    private String postJson(String url, String body, int expectedStatus) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse()
                .getContentAsString();
    }

    @Test
    void fullBusinessChainOverHttp() throws Exception {
        postJson("/api/tags", "{\"tagCode\":\"L\",\"stationCode\":\"ST-A\"}", 201);
        postJson("/api/attachments", "{\"requestId\":\"r1\",\"tagCode\":\"L\","
                + "\"animalCode\":\"AN-1\",\"stationCode\":\"ST-A\","
                + "\"attachedAt\":\"2026-10-04T08:00:00Z\"}", 201);

        // 未摘除时重复附着 → 409，拒绝原因进入时间线
        postJson("/api/attachments", "{\"requestId\":\"r2\",\"tagCode\":\"L\","
                + "\"animalCode\":\"AN-2\",\"stationCode\":\"ST-A\","
                + "\"attachedAt\":\"2026-10-04T09:00:00Z\"}", 409);

        // 10:00 摘除并发起交接
        postJson("/api/attachments/detach", "{\"tagCode\":\"L\",\"stationCode\":\"ST-A\","
                + "\"detachedAt\":\"2026-10-04T10:00:00Z\"}", 200);
        String transfer = postJson("/api/transfers", "{\"requestId\":\"r3\",\"tagCode\":\"L\","
                + "\"fromStation\":\"ST-A\",\"toStation\":\"ST-B\","
                + "\"initiatedAt\":\"2026-10-04T10:00:00Z\"}", 201);
        long transferId = Long.parseLong(
                transfer.replaceAll(".*\"id\":(\\d+).*", "$1"));

        // 非目标站点确认 → 409
        postJson("/api/transfers/" + transferId + "/confirm",
                "{\"stationCode\":\"ST-C\",\"confirmedAt\":\"2026-10-04T10:05:00Z\"}", 409);
        // 目标站点确认 → 200，持有权转移
        postJson("/api/transfers/" + transferId + "/confirm",
                "{\"stationCode\":\"ST-B\",\"confirmedAt\":\"2026-10-04T10:05:00Z\"}", 200);

        // 09:59 交叉附着 → 422；10:00 附着 → 201
        postJson("/api/attachments", "{\"requestId\":\"r4\",\"tagCode\":\"L\","
                + "\"animalCode\":\"AN-2\",\"stationCode\":\"ST-B\","
                + "\"attachedAt\":\"2026-10-04T09:59:00Z\"}", 422);
        postJson("/api/attachments", "{\"requestId\":\"r5\",\"tagCode\":\"L\","
                + "\"animalCode\":\"AN-2\",\"stationCode\":\"ST-B\","
                + "\"attachedAt\":\"2026-10-04T10:00:00Z\"}", 201);

        // 同 requestId 同参重放 → 201 且返回同一记录
        postJson("/api/attachments", "{\"requestId\":\"r5\",\"tagCode\":\"L\","
                + "\"animalCode\":\"AN-2\",\"stationCode\":\"ST-B\","
                + "\"attachedAt\":\"2026-10-04T10:00:00Z\"}", 201);
        // 同 requestId 异参 → 409
        postJson("/api/attachments", "{\"requestId\":\"r5\",\"tagCode\":\"L\","
                + "\"animalCode\":\"AN-9\",\"stationCode\":\"ST-B\","
                + "\"attachedAt\":\"2026-10-04T10:00:00Z\"}", 409);

        mvc.perform(get("/api/tags/L/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'TRANSFER_CONFIRMED')].confirmedAt")
                        .value(org.hamcrest.Matchers.hasItem("2026-10-04T10:05:00Z")))
                .andExpect(jsonPath("$[?(@.type == 'REJECTED')].reason").isArray())
                .andExpect(jsonPath("$[?(@.type == 'ATTACH')].stationCode")
                        .value(org.hamcrest.Matchers.hasItems("ST-A", "ST-B")));

        mvc.perform(get("/api/animals/AN-2/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'ATTACH')].tagCode").value(org.hamcrest.Matchers.hasItem("L")))
                .andExpect(jsonPath("$[?(@.type == 'REJECTED')].reason").isArray());
    }

    @Test
    void validationFailureReturns400() throws Exception {
        postJson("/api/tags", "{\"tagCode\":\"\",\"stationCode\":\"ST-A\"}", 400);
    }
}