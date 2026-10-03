package com.example.starter.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 校准判定、有效期与任务窗口边界测试（真实 H2 写入）。
 * 合成场景：08:00 校准通过，有效期至次日 08:00。
 */
class CalibrationApiTest extends AbstractApiTest {

    @Test
    void 偏差均不超阈值时校准通过且有效期24小时() throws Exception {
        long id = newCalibration("ANT-01", "2026-10-05T08:00:00Z", "0.2", "-0.2");
        JsonNode view = getCalibration(id);
        assertTrue(view.get("passed").asBoolean());
        assertEquals("2026-10-05T08:00:00Z", view.get("validFrom").asText());
        assertEquals("2026-10-06T08:00:00Z", view.get("validUntil").asText());
        assertEquals("ACTIVE", view.get("status").asText());
    }

    @Test
    void 任一偏差超阈值时校准不通过且无有效期() throws Exception {
        long id = newCalibration("ANT-01", "2026-10-05T08:00:00Z", "0.2001", "0.0");
        JsonNode view = getCalibration(id);
        assertTrue(!view.get("passed").asBoolean());
        assertTrue(view.get("validFrom").isNull());
        assertTrue(view.get("validUntil").isNull());
    }

    @Test
    void 次日七点五十九结束的任务可绑定() throws Exception {
        long calId = newCalibration("ANT-01", "2026-10-05T08:00:00Z", "0.1", "0.1");
        long taskId = newTask("T-IN", "ANT-01", "2026-10-05T09:00:00Z", "2026-10-06T07:59:00Z");
        MvcResult result = bind("req-in", taskId, calId);
        assertEquals(201, result.getResponse().getStatus());
        assertEquals(calId, body(result).get("calibrationId").asLong());
        assertEquals("BOUND", body(result).get("status").asText());
    }

    @Test
    void 结束时刻等于有效期终点的任务可绑定() throws Exception {
        long calId = newCalibration("ANT-01", "2026-10-05T08:00:00Z", "0.1", "0.1");
        long taskId = newTask("T-EDGE", "ANT-01", "2026-10-05T09:00:00Z", "2026-10-06T08:00:00Z");
        MvcResult result = bind("req-edge", taskId, calId);
        assertEquals(201, result.getResponse().getStatus());
    }

    @Test
    void 次日八点才开始的任务被拒绝422() throws Exception {
        long calId = newCalibration("ANT-01", "2026-10-05T08:00:00Z", "0.1", "0.1");
        long taskId = newTask("T-LATE", "ANT-01", "2026-10-06T08:00:00Z", "2026-10-06T09:00:00Z");
        MvcResult result = bind("req-late", taskId, calId);
        assertEquals(422, result.getResponse().getStatus());
        assertEquals("TASK_WINDOW_OUTSIDE_VALIDITY", body(result).get("code").asText());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM task_binding", Integer.class));
    }

    @Test
    void 跨有效期任务被拒绝422() throws Exception {
        long calId = newCalibration("ANT-01", "2026-10-05T08:00:00Z", "0.1", "0.1");
        long taskId = newTask("T-CROSS", "ANT-01", "2026-10-06T07:00:00Z", "2026-10-06T09:00:00Z");
        MvcResult result = bind("req-cross", taskId, calId);
        assertEquals(422, result.getResponse().getStatus());
        assertEquals("TASK_WINDOW_OUTSIDE_VALIDITY", body(result).get("code").asText());
    }

    @Test
    void 未通过校准不能绑定且不延长有效期() throws Exception {
        long calId = newCalibration("ANT-01", "2026-10-05T08:00:00Z", "0.5", "0.1");
        long taskId = newTask("T-FAIL", "ANT-01", "2026-10-05T09:00:00Z", "2026-10-05T10:00:00Z");
        MvcResult result = bind("req-fail", taskId, calId);
        assertEquals(422, result.getResponse().getStatus());
        assertEquals("CALIBRATION_NOT_PASSED", body(result).get("code").asText());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM task_binding", Integer.class));
    }

    @Test
    void 校准链查询按时刻升序返回() throws Exception {
        newCalibration("ANT-01", "2026-10-05T08:00:00Z", "0.1", "0.1");
        newCalibration("ANT-01", "2026-10-06T09:00:00Z", "0.3", "0.1");
        MvcResult result = mockMvc.perform(get("/api/calibrations")
                        .param("antennaCode", "ANT-01"))
                .andReturn();
        JsonNode chain = body(result);
        assertEquals(2, chain.size());
        assertEquals("2026-10-05T08:00:00Z", chain.get(0).get("calibratedAt").asText());
        assertEquals("2026-10-06T09:00:00Z", chain.get(1).get("calibratedAt").asText());
        assertTrue(!chain.get(1).get("passed").asBoolean());
    }
}