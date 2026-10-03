package com.example.starter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 主流程与拒绝分支：12:00-12:10 过境分给站点 A，同天线重叠 409，取消后释放。
 */
class SatellitePassApiTest extends AbstractIntegrationTest {

    private static final String PASS_BODY =
            predictionBody("SAT-1", "2026-10-04T12:00:00Z", "2026-10-04T12:10:00Z", "8.0", 5_000_000);

    @Autowired
    private ObjectMapper objectMapper;

    private void publishDefaultPass() throws Exception {
        mockMvc.perform(post("/api/predictions").contentType(MediaType.APPLICATION_JSON)
                        .content(PASS_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.superseded").value(false));
    }

    private long allocate(String body, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/allocations")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.has("id") ? json.get("id").asLong() : -1L;
    }

    @Test
    void overlapReturns409AndCancelReleasesSlot() throws Exception {
        publishDefaultPass();
        long allocationId = allocate(allocateBody("req-1", "SAT-1", STATION_A,
                "2026-10-04T12:00:00Z", "2026-10-04T12:10:00Z"), 201);

        mockMvc.perform(get("/api/allocations/{id}", allocationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.predictionVersion").value(1))
                .andExpect(jsonPath("$.capacityBytesPerMin").value(1_000_000))
                .andExpect(jsonPath("$.plannedBytes").value(5_000_000));

        // 同天线重叠请求：409，原因可区分。
        mockMvc.perform(post("/api/allocations").contentType(MediaType.APPLICATION_JSON)
                        .content(allocateBody("req-2", "SAT-1", STATION_A,
                                "2026-10-04T12:05:00Z", "2026-10-04T12:10:00Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("ANTENNA_OVERLAP"));

        // 取消后时隙释放，同段可再次分配。
        mockMvc.perform(post("/api/allocations/{id}/cancel", allocationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"计划调整\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("计划调整"));

        mockMvc.perform(post("/api/allocations").contentType(MediaType.APPLICATION_JSON)
                        .content(allocateBody("req-3", "SAT-1", STATION_A,
                                "2026-10-04T12:00:00Z", "2026-10-04T12:10:00Z")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // 时间线查询：一条已取消、一条已确认。
        mockMvc.perform(get("/api/stations/ST-A/timeline")
                        .param("from", "2026-10-04T11:00:00Z")
                        .param("to", "2026-10-04T13:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$[1].status").value("CONFIRMED"));
    }

    @Test
    void rejectionReasonsAreDistinct() throws Exception {
        publishDefaultPass();
        // 窗口缺口：时隙超出预测窗口。
        mockMvc.perform(post("/api/allocations").contentType(MediaType.APPLICATION_JSON)
                        .content(allocateBody("req-gap", "SAT-1", STATION_A,
                                "2026-10-04T11:50:00Z", "2026-10-04T12:10:00Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("WINDOW_GAP"));

        // 超容量：计划量超过 时隙时长 x 容量。
        mockMvc.perform(post("/api/predictions").contentType(MediaType.APPLICATION_JSON)
                        .content(predictionBody("SAT-2", "2026-10-04T12:00:00Z",
                                "2026-10-04T12:10:00Z", "8.0", 20_000_000)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/allocations").contentType(MediaType.APPLICATION_JSON)
                        .content(allocateBody("req-cap", "SAT-2", STATION_A,
                                "2026-10-04T12:00:00Z", "2026-10-04T12:10:00Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("CAPACITY_EXCEEDED"));

        // 仰角不足：预测最低仰角 3 度低于站点 A 门槛 5 度。
        mockMvc.perform(post("/api/predictions").contentType(MediaType.APPLICATION_JSON)
                        .content(predictionBody("SAT-3", "2026-10-04T12:00:00Z",
                                "2026-10-04T12:10:00Z", "3.0", 1_000_000)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/allocations").contentType(MediaType.APPLICATION_JSON)
                        .content(allocateBody("req-elev", "SAT-3", STATION_A,
                                "2026-10-04T12:00:00Z", "2026-10-04T12:10:00Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("ELEVATION_BELOW_THRESHOLD"));

        // 拒绝不占用时隙：时间线为空。
        mockMvc.perform(get("/api/stations/ST-A/timeline")
                        .param("from", "2026-10-04T11:00:00Z")
                        .param("to", "2026-10-04T13:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}