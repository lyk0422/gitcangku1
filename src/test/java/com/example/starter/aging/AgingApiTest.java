package com.example.starter.aging;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * HTTP 层端到端：合成批次从入库到放行的完整调用链，含幂等重放与拒绝原因。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AgingApiTest {

    @Autowired
    private MockMvc mvc;

    private String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private long createBatch(String tag) throws Exception {
        MvcResult result = mvc.perform(post("/api/batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"req-batch-" + tag + "\",\"batchCode\":\"LOT-" + tag
                        + "\",\"storedAt\":\"2026-02-01T06:00:00Z\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AGING"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    private void turn(long batchId, String tag, int day) throws Exception {
        mvc.perform(post("/api/batches/{id}/turns", batchId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"req-turn-" + day + "-" + tag
                        + "\",\"turnDate\":\"2026-02-0" + day + "\",\"operator\":\"op-" + tag
                        + "\"}"))
                .andExpect(status().isCreated());
    }

    private void temperature(long batchId, String tag, int day, String celsius) throws Exception {
        mvc.perform(post("/api/batches/{id}/temperatures", batchId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"req-temp-" + day + "-" + tag
                        + "\",\"recordedDate\":\"2026-02-0" + day + "\",\"celsius\":" + celsius
                        + "}"))
                .andExpect(status().isCreated());
    }

    @Test
    void fullChainWithMissingDay4ThenRelease() throws Exception {
        String tag = tag();
        long batchId = createBatch(tag);
        for (int day = 1; day <= 7; day++) {
            turn(batchId, tag, day);
            if (day != 4) {
                temperature(batchId, tag, day, "10.0");
            }
        }

        // 第4天（2026-02-04）缺温度：拒绝并明确返回日期
        mvc.perform(post("/api/batches/{id}/release", batchId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"req-rel-" + tag + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.missingTemperatureDates[0]").value("2026-02-04"));

        // 同日重复翻面：同参幂等返回原记录，异参 409
        mvc.perform(post("/api/batches/{id}/turns", batchId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"req-turn-1-" + tag
                        + "\",\"turnDate\":\"2026-02-01\",\"operator\":\"op-" + tag + "\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/batches/{id}/turns", batchId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"req-turn-1-" + tag
                        + "\",\"turnDate\":\"2026-02-01\",\"operator\":\"other\"}"))
                .andExpect(status().isConflict());

        // 补记第4天有效读数后，同一 requestId 重新审核通过
        temperature(batchId, tag, 4, "11.5");
        mvc.perform(post("/api/batches/{id}/release", batchId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"req-rel-" + tag + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.snapshotJson").isString());

        // 放行重放幂等；异参 409
        mvc.perform(post("/api/batches/{id}/release", batchId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"req-rel-" + tag + "\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/batches/{id}/release", batchId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"req-rel-2-" + tag + "\"}"))
                .andExpect(status().isConflict());

        // 查询：日期轴 + 放行引用
        mvc.perform(get("/api/batches/{id}", batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.days[0].date").value("2026-02-01"))
                .andExpect(jsonPath("$.days[3].validTemperatures[0].celsius").value(11.5))
                .andExpect(jsonPath("$.release.releaseId").isNumber());
    }

    @Test
    void validationRejectsBlankRequestId() throws Exception {
        mvc.perform(post("/api/batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"\",\"batchCode\":\"LOT-X\"}"))
                .andExpect(status().isBadRequest());
    }
}