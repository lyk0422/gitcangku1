package com.example.starter;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.starter.support.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 接口层测试：接收、开封、幂等领用、召回与按瓶查询的完整 HTTP 流程。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class ReagentApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM dispense_record");
        jdbc.update("DELETE FROM recall_record");
        jdbc.update("DELETE FROM reagent_bottle");
    }

    @Test
    void fullReceiveOpenDispenseRecallFlow() throws Exception {
        MvcResult received = mvc.perform(post("/api/bottles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchNo":"LOT-1","capacityMl":10.0,
                                 "supplierExpiryAt":"2026-12-01T00:00:00Z"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.remainingMl").value(10.0))
                .andExpect(jsonPath("$.expirySource").value("SUPPLIER_EXPIRY"))
                .andReturn();
        long id = Long.parseLong(
                com.jayway.jsonpath.JsonPath.read(received.getResponse().getContentAsString(), "$.id")
                        .toString());

        mvc.perform(post("/api/bottles/{id}/open", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openedAt").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.expirySource").value("OPEN_EXPIRY"))
                .andExpect(jsonPath("$.effectiveDeadline").value("2026-01-31T00:00:00Z"));

        mvc.perform(post("/api/bottles/{id}/dispenses", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-1\",\"amountMl\":6.0}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.remainingAfterMl").value(4.0))
                .andExpect(jsonPath("$.deadlineBasis").value("OPEN_EXPIRY"))
                .andExpect(jsonPath("$.replayed").value(false));

        mvc.perform(post("/api/bottles/{id}/dispenses", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-1\",\"amountMl\":6.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.remainingAfterMl").value(4.0));

        mvc.perform(post("/api/bottles/{id}/dispenses", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-1\",\"amountMl\":1.0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        mvc.perform(get("/api/bottles/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bottle.remainingMl").value(4.0))
                .andExpect(jsonPath("$.bottle.expirySource").value("OPEN_EXPIRY"))
                .andExpect(jsonPath("$.dispenses", hasSize(1)))
                .andExpect(jsonPath("$.dispenses[0].amountMl").value(6.0));

        mvc.perform(post("/api/recalls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchNo\":\"LOT-1\",\"reason\":\"供应商召回\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quarantinedBottleIds[0]").value(id));

        mvc.perform(get("/api/bottles/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bottle.status").value("QUARANTINED"))
                .andExpect(jsonPath("$.bottle.quarantineReason").value("供应商召回"))
                .andExpect(jsonPath("$.dispenses", hasSize(1)));

        mvc.perform(post("/api/bottles/{id}/dispenses", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-2\",\"amountMl\":1.0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BOTTLE_QUARANTINED"));
    }

    @Test
    void unknownBottleReturns404() throws Exception {
        mvc.perform(get("/api/bottles/{id}", 999999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOTTLE_NOT_FOUND"));
    }

    @Test
    void invalidRequestBodyReturns400() throws Exception {
        mvc.perform(post("/api/bottles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchNo\":\"\",\"capacityMl\":10.0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}