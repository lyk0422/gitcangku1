package com.example.starter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 主流程测试：合成 12:00-12:10 过境分给站点 A，验证重叠拒绝、
 * 取消释放、完成回报与四类查询，均通过真实 HTTP 与 H2 执行。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:flowtest;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class AllocationFlowTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void fullPassLifecycleAndQueries() throws Exception {
        String prediction = """
                {"satelliteId":"SAT-1","windowStartUtc":"2026-01-01T12:00:00Z",
                 "windowEndUtc":"2026-01-01T12:10:00Z","minElevationDeg":10.0,"plannedDataMb":500}
                """;
        mvc.perform(post("/api/predictions/PASS-1").contentType(MediaType.APPLICATION_JSON)
                        .content(prediction))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("CURRENT"));

        String allocation = """
                {"requestId":"req-1","predictionKey":"PASS-1","expectedVersion":1,
                 "stationCode":"A","slotStartUtc":"2026-01-01T12:00:00Z",
                 "slotEndUtc":"2026-01-01T12:10:00Z"}
                """;
        MvcResult created = mvc.perform(post("/api/allocations").contentType(MediaType.APPLICATION_JSON)
                        .content(allocation))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.predictionVersion").value(1))
                .andExpect(jsonPath("$.capacitySnapshotMbPerMin").value(100))
                .andExpect(jsonPath("$.stationCode").value("A"))
                .andReturn();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();

        // 同 requestId 同参重放：返回 200 与同一记录
        mvc.perform(post("/api/allocations").contentType(MediaType.APPLICATION_JSON).content(allocation))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));

        // 同 requestId 异参：409 REQUEST_ID_CONFLICT
        String mutated = allocation.replace("12:10:00Z\"}", "12:09:00Z\"}");
        mvc.perform(post("/api/allocations").contentType(MediaType.APPLICATION_JSON).content(mutated))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("REQUEST_ID_CONFLICT"));

        // 同天线重叠请求：409 ANTENNA_OVERLAP
        String overlap = """
                {"requestId":"req-2","predictionKey":"PASS-1","expectedVersion":1,
                 "stationCode":"A","slotStartUtc":"2026-01-01T12:05:00Z",
                 "slotEndUtc":"2026-01-01T12:10:00Z"}
                """;
        mvc.perform(post("/api/allocations").contentType(MediaType.APPLICATION_JSON).content(overlap))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("ANTENNA_OVERLAP"));

        // 取消后释放天线，重叠请求转为可分配
        mvc.perform(post("/api/allocations/{id}/cancel", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"计划调整\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("计划调整"));
        MvcResult reallocated = mvc.perform(post("/api/allocations")
                        .contentType(MediaType.APPLICATION_JSON).content(overlap))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andReturn();
        long id2 = ((Number) com.jayway.jsonpath.JsonPath.read(reallocated.getResponse().getContentAsString(), "$.id")).longValue();

        // 完成回报：记录实际字节数，同参重放返回原结果
        String completion = "{\"requestId\":\"cmp-1\",\"actualBytes\":524000000}";
        mvc.perform(post("/api/allocations/{id}/complete", id2)
                        .contentType(MediaType.APPLICATION_JSON).content(completion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.actualBytes").value(524000000));
        mvc.perform(post("/api/allocations/{id}/complete", id2)
                        .contentType(MediaType.APPLICATION_JSON).content(completion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actualBytes").value(524000000));

        // 查询：时隙时间线、预测版本、待重排、接收实绩
        mvc.perform(get("/api/stations/A/timeline")
                        .param("from", "2026-01-01T11:00:00Z").param("to", "2026-01-01T13:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/predictions/PASS-1/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/allocations/pending-reassignment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/stations/A/receptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].actualBytes").value(524000000))
                .andExpect(jsonPath("$[0].predictionVersion").value(1));
    }
}