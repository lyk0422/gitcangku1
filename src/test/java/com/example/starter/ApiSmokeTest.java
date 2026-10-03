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
import org.springframework.test.web.servlet.MvcResult;

/** HTTP 入口冒烟：合成三枚电芯，一枚 0.6 V 整批拒绝，隔离后其余两枚交接成功。 */
@AutoConfigureMockMvc
class ApiSmokeTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fullFlowOverHttp() throws Exception {
        MvcResult batchResult = mockMvc.perform(post("/api/batches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn();
        String batch = com.jayway.jsonpath.JsonPath.read(
                batchResult.getResponse().getContentAsString(), "$.batchCode");

        for (String cell : new String[] {"C-S1", "C-S2", "C-S3"}) {
            givenCell(cell);
            mockMvc.perform(post("/api/batches/{batch}/cells", batch)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"" + requestId() + "\","
                                    + "\"cellCode\":\"" + cell + "\","
                                    + "\"initialVoltage\":3.7}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cellCode").value(cell));
        }
        finalReading(batch, "C-S1", 0.4).andExpect(status().isOk());
        finalReading(batch, "C-S2", 0.6).andExpect(status().isOk());
        finalReading(batch, "C-S3", 0.3).andExpect(status().isOk());

        // 一枚 0.6 V：整批拒绝并指出电芯编号
        mockMvc.perform(post("/api/batches/{batch}/handover", batch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId() + "\","
                                + "\"fromParty\":\"RECYCLE-STATION\","
                                + "\"toParty\":\"DISASSEMBLY-ZONE\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.cellCodes[0]").value("C-S2"));

        mockMvc.perform(post("/api/batches/{batch}/isolate", batch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cellCode\":\"C-S2\",\"reason\":\"终测 0.6 V 超限\"}"))
                .andExpect(status().isOk());

        String handoverReq = requestId();
        MvcResult handover = mockMvc.perform(post("/api/batches/{batch}/handover", batch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + handoverReq + "\","
                                + "\"fromParty\":\"RECYCLE-STATION\","
                                + "\"toParty\":\"DISASSEMBLY-ZONE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cells.length()").value(2))
                .andExpect(jsonPath("$.toParty").value("DISASSEMBLY-ZONE"))
                .andReturn();
        String handoverBody = handover.getResponse().getContentAsString();

        // 同 requestId 同参重放：原样返回首次响应
        mockMvc.perform(post("/api/batches/{batch}/handover", batch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + handoverReq + "\","
                                + "\"fromParty\":\"RECYCLE-STATION\","
                                + "\"toParty\":\"DISASSEMBLY-ZONE\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().json(handoverBody));

        // 同 requestId 异参：409
        mockMvc.perform(post("/api/batches/{batch}/handover", batch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + handoverReq + "\","
                                + "\"fromParty\":\"RECYCLE-STATION\","
                                + "\"toParty\":\"OTHER-ZONE\"}"))
                .andExpect(status().isConflict());

        // 查询：读数链、隔离原因、交接快照
        mockMvc.perform(get("/api/batches/{batch}", batch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HANDED_OVER"))
                .andExpect(jsonPath("$.cells.length()").value(3))
                .andExpect(jsonPath("$.handover.cells.length()").value(2));
    }

    @Test
    void unknownBatchReturns404() throws Exception {
        mockMvc.perform(get("/api/batches/BATCH-NONE"))
                .andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.ResultActions finalReading(
            String batch, String cell, double voltage) throws Exception {
        return mockMvc.perform(post("/api/batches/{batch}/readings", batch)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + requestId() + "\","
                        + "\"cellCode\":\"" + cell + "\","
                        + "\"voltage\":" + voltage + ","
                        + "\"measuredAt\":\"" + T0.plusSeconds(3600) + "\"}"));
    }
}