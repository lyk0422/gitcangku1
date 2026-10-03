package com.example.starter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** 接收、开封、领用主流程与失败分支的接口级测试（真实 H2）。 */
class ReagentApiTest extends AbstractReagentTest {

    private static final Instant T0 = TestClockConfig.T0;

    @Test
    void receiveOpenDispenseFlow() throws Exception {
        String body = """
                {"lotNumber":"LOT-T1","capacityMl":10.0,"supplierExpiryUtc":"%s"}
                """.formatted(T0.plus(60, ChronoUnit.DAYS));
        String resp = mvc.perform(post("/api/bottles").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.remainingMl").value(10.0))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(resp.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mvc.perform(post("/api/bottles/{id}/open", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openedAtUtc").value(T0.toString()))
                .andExpect(jsonPath("$.deadlineSource").value("OPEN_EXPIRY_30D"))
                .andExpect(jsonPath("$.effectiveDeadlineUtc")
                        .value(T0.plus(30, ChronoUnit.DAYS).toString()));

        mvc.perform(post("/api/bottles/{id}/dispenses", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r1\",\"amountMl\":6.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingAfterMl").value(4.0))
                .andExpect(jsonPath("$.deadlineSource").value("OPEN_EXPIRY_30D"));

        mvc.perform(get("/api/bottles/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bottle.remainingMl").value(4.0))
                .andExpect(jsonPath("$.bottle.deadlineSource").value("OPEN_EXPIRY_30D"))
                .andExpect(jsonPath("$.dispenses.length()").value(1))
                .andExpect(jsonPath("$.dispenses[0].requestId").value("r1"));
    }

    @Test
    void supplierExpiryEarlierWins() throws Exception {
        Instant supplier = T0.plus(10, ChronoUnit.DAYS);
        long id = receiveBottle("LOT-T2", "10.0", supplier);
        mvc.perform(post("/api/bottles/{id}/open", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deadlineSource").value("SUPPLIER_EXPIRY"))
                .andExpect(jsonPath("$.effectiveDeadlineUtc").value(supplier.toString()));
    }

    @Test
    void dispenseAtExactDeadlineRejected() throws Exception {
        long id = receiveBottle("LOT-T3", "10.0", T0.plus(60, ChronoUnit.DAYS));
        service.open(id);
        Instant deadline = T0.plus(30, ChronoUnit.DAYS);

        clock.set(deadline.minusSeconds(1));
        mvc.perform(post("/api/bottles/{id}/dispenses", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-ok\",\"amountMl\":1.0}"))
                .andExpect(status().isOk());

        clock.set(deadline);
        mvc.perform(post("/api/bottles/{id}/dispenses", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-edge\",\"amountMl\":1.0}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/bottles/{id}", id))
                .andExpect(jsonPath("$.bottle.remainingMl").value(9.0));
    }

    @Test
    void invalidAmountsRejectedAndKeyNotOccupied() throws Exception {
        long id = receiveBottle("LOT-T4", "10.0", T0.plus(60, ChronoUnit.DAYS));
        service.open(id);
        for (String bad : new String[] {"0", "-1.0", "0.15"}) {
            mvc.perform(post("/api/bottles/{id}/dispenses", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"rx\",\"amountMl\":" + bad + "}"))
                    .andExpect(status().isBadRequest());
        }
        // 失败请求不占键：同一 requestId 换合法参数可成功
        mvc.perform(post("/api/bottles/{id}/dispenses", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"rx\",\"amountMl\":1.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingAfterMl").value(9.0));
    }

    @Test
    void notOpenedAndOverRemainingRejected() throws Exception {
        long id = receiveBottle("LOT-T5", "10.0", T0.plus(60, ChronoUnit.DAYS));
        mvc.perform(post("/api/bottles/{id}/dispenses", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-no\",\"amountMl\":1.0}"))
                .andExpect(status().isUnprocessableEntity());

        service.open(id);
        mvc.perform(post("/api/bottles/{id}/dispenses", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-over\",\"amountMl\":10.1}"))
                .andExpect(status().isUnprocessableEntity());
        // 超量失败不占键，剩余量不变
        mvc.perform(post("/api/bottles/{id}/dispenses", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-over\",\"amountMl\":2.0}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/bottles/{id}", id))
                .andExpect(jsonPath("$.bottle.remainingMl").value(8.0));
    }

    @Test
    void detailNotFound() throws Exception {
        mvc.perform(get("/api/bottles/{id}", 999999))
                .andExpect(status().isNotFound());
    }
}