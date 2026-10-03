package com.example.starter.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 复核作废影响测试：未开始转待重配、已完成保留旧依据、作废后不可再绑定（真实 H2 写入）。
 * 可控时钟固定在 2026-10-05T08:30:00Z。
 */
class InvalidationApiTest extends AbstractApiTest {

    @Test
    void 未开始任务的绑定在作废后转为待重配() throws Exception {
        long calId = newCalibration("ANT-01", "2026-10-05T08:00:00Z", "0.1", "0.1");
        long taskId = newTask("T-FUTURE", "ANT-01", "2026-10-05T10:00:00Z", "2026-10-05T12:00:00Z");
        assertEquals(201, bind("req-1", taskId, calId).getResponse().getStatus());

        MvcResult result = invalidate(calId, "复核发现偏差超限");
        assertEquals(200, result.getResponse().getStatus());
        JsonNode impact = body(result);
        assertEquals("INVALIDATED", impact.get("calibration").get("status").asText());
        JsonNode impacted = impact.get("impactedBindings").get(0);
        assertEquals("PENDING_REASSIGN", impacted.get("status").asText());
        assertTrue(impacted.get("calibrationInvalidated").asBoolean());

        JsonNode task = getTask(taskId);
        assertEquals("PENDING_REASSIGN", task.get("bindings").get(0).get("status").asText());
    }

    @Test
    void 已完成任务保留旧依据并标记作废() throws Exception {
        long calId = newCalibration("ANT-01", "2026-10-05T08:00:00Z", "0.1", "-0.05");
        long taskId = newTask("T-DONE", "ANT-01", "2026-10-05T08:35:00Z", "2026-10-05T09:00:00Z");
        assertEquals(201, bind("req-1", taskId, calId).getResponse().getStatus());
        completeTask(taskId);

        MvcResult result = invalidate(calId, "事后复核作废");
        assertEquals(200, result.getResponse().getStatus());
        JsonNode impacted = body(result).get("impactedBindings").get(0);
        assertEquals("BOUND", impacted.get("status").asText());
        assertTrue(impacted.get("calibrationInvalidated").asBoolean());
        assertEquals(calId, impacted.get("calibrationId").asLong());
        assertEquals(0, new java.math.BigDecimal("0.1")
                .compareTo(impacted.get("azimuthDeviationDeg").decimalValue()));
    }

    @Test
    void 作废后的校准不能再绑定() throws Exception {
        long calId = newCalibration("ANT-01", "2026-10-05T08:00:00Z", "0.1", "0.1");
        assertEquals(200, invalidate(calId, "复核作废").getResponse().getStatus());
        long taskId = newTask("T-NEW", "ANT-01", "2026-10-05T10:00:00Z", "2026-10-05T11:00:00Z");
        MvcResult result = bind("req-late", taskId, calId);
        assertEquals(422, result.getResponse().getStatus());
        assertEquals("CALIBRATION_INVALIDATED", body(result).get("code").asText());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM task_binding", Integer.class));
    }

    @Test
    void 重复作废返回409() throws Exception {
        long calId = newCalibration("ANT-01", "2026-10-05T08:00:00Z", "0.1", "0.1");
        assertEquals(200, invalidate(calId, "首次作废").getResponse().getStatus());
        MvcResult again = invalidate(calId, "再次作废");
        assertEquals(409, again.getResponse().getStatus());
        assertEquals("CALIBRATION_ALREADY_INVALIDATED", body(again).get("code").asText());
    }

    @Test
    void 待重配任务可用新校准重新绑定且旧绑定标记为已替换() throws Exception {
        long cal1 = newCalibration("ANT-01", "2026-10-05T08:00:00Z", "0.1", "0.1");
        long taskId = newTask("T-RE", "ANT-01", "2026-10-05T10:00:00Z", "2026-10-05T12:00:00Z");
        assertEquals(201, bind("req-1", taskId, cal1).getResponse().getStatus());
        assertEquals(200, invalidate(cal1, "复核作废").getResponse().getStatus());

        long cal2 = newCalibration("ANT-01", "2026-10-05T08:30:00Z", "0.05", "0.05");
        MvcResult rebound = bind("req-2", taskId, cal2);
        assertEquals(201, rebound.getResponse().getStatus());
        assertEquals(cal2, body(rebound).get("calibrationId").asLong());

        JsonNode task = getTask(taskId);
        JsonNode bindings = task.get("bindings");
        assertEquals(2, bindings.size());
        assertEquals("SUPERSEDED", bindings.get(0).get("status").asText());
        assertEquals("BOUND", bindings.get(1).get("status").asText());
        assertEquals(cal2, bindings.get(1).get("calibrationId").asLong());
    }
}