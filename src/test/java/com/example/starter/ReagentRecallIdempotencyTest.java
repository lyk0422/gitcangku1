package com.example.starter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** 领用幂等与批号召回隔离的接口级测试（真实 H2）。 */
class ReagentRecallIdempotencyTest extends AbstractReagentTest {

    private static final java.time.Instant T0 = TestClockConfig.T0;

    @Test
    void idempotentReplayAndConflict() throws Exception {
        long id = receiveBottle("LOT-I1", "10.0", T0.plus(60, ChronoUnit.DAYS));
        service.open(id);

        String first = mvc.perform(post("/api/bottles/{id}/dispenses", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r1\",\"amountMl\":2.0}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // 同参重放：返回首次记录，剩余量不变
        mvc.perform(post("/api/bottles/{id}/dispenses", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r1\",\"amountMl\":2.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first.replaceAll(".*\"id\":(\\d+).*", "$1")))
                .andExpect(jsonPath("$.remainingAfterMl").value(8.0));
        mvc.perform(get("/api/bottles/{id}", id))
                .andExpect(jsonPath("$.bottle.remainingMl").value(8.0))
                .andExpect(jsonPath("$.dispenses.length()").value(1));

        // 异参 409：不同数量
        mvc.perform(post("/api/bottles/{id}/dispenses", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r1\",\"amountMl\":3.0}"))
                .andExpect(status().isConflict());
        // 异参 409：同一 requestId 用于其他瓶
        long other = receiveBottle("LOT-I2", "10.0", T0.plus(60, ChronoUnit.DAYS));
        service.open(other);
        mvc.perform(post("/api/bottles/{id}/dispenses", other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r1\",\"amountMl\":2.0}"))
                .andExpect(status().isConflict());
    }

    @Test
    void recallQuarantinesOnlyBottlesWithRemaining() throws Exception {
        long empty = receiveBottle("LOT-R1", "10.0", T0.plus(60, ChronoUnit.DAYS));
        service.open(empty);
        service.dispense(empty, "r-empty", new java.math.BigDecimal("10.0"));
        long partial = receiveBottle("LOT-R1", "10.0", T0.plus(60, ChronoUnit.DAYS));
        service.open(partial);
        service.dispense(partial, "r-part", new java.math.BigDecimal("4.0"));

        mvc.perform(post("/api/lots/{lot}/recall", "LOT-R1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"供应商召回通知 RC-2026-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quarantinedCount").value(1))
                .andExpect(jsonPath("$.quarantinedBottleIds[0]").value(partial));

        // 已隔离瓶不能继续领用
        mvc.perform(post("/api/bottles/{id}/dispenses", partial)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-after\",\"amountMl\":1.0}"))
                .andExpect(status().isUnprocessableEntity());

        // 查询可见隔离原因；历史成功领用保留，无负数扣减
        mvc.perform(get("/api/bottles/{id}", partial))
                .andExpect(jsonPath("$.bottle.quarantined").value(true))
                .andExpect(jsonPath("$.bottle.quarantineReason").value("供应商召回通知 RC-2026-01"))
                .andExpect(jsonPath("$.bottle.remainingMl").value(6.0))
                .andExpect(jsonPath("$.dispenses.length()").value(1))
                .andExpect(jsonPath("$.dispenses[0].remainingAfterMl").value(6.0));
        // 已耗尽瓶未被隔离
        mvc.perform(get("/api/bottles/{id}", empty))
                .andExpect(jsonPath("$.bottle.quarantined").value(false))
                .andExpect(jsonPath("$.dispenses.length()").value(1));
    }

    @Test
    void recallUnknownLotNotFound() throws Exception {
        mvc.perform(post("/api/lots/{lot}/recall", "LOT-NONE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\"}"))
                .andExpect(status().isNotFound());
    }
}