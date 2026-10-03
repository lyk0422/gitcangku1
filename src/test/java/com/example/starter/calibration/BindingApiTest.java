package com.example.starter.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 绑定规则测试：重叠占用、幂等重放、失败不占键、快照固化（真实 H2 写入）。
 */
class BindingApiTest extends AbstractApiTest {

    @Test
    void 同一天线重叠占用返回409且不产生绑定() throws Exception {
        long calId = newCalibration("ANT-01", "2026-10-05T08:00:00Z", "0.1", "0.1");
        long t1 = newTask("T-A", "ANT-01", "2026-10-05T10:00:00Z", "2026-10-05T12:00:00Z");
        long t2 = newTask("T-B", "ANT-01", "2026-10-05T11:00:00Z", "2026-10-05T13:00:00Z");
        assertEquals(201, bind("req-a", t1, calId).getResponse().getStatus());
        MvcResult conflict = bind("req-b", t2, calId);
        assertEquals(409, conflict.getResponse().getStatus());
        assertEquals("ANTENNA_OCCUPIED", body(conflict).get("code").asText());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM task_binding", Integer.class));
    }

    @Test
    void 首尾相接与不同天线不算重叠() throws Exception {
        long calId = newCalibration("ANT-01", "2026-10-05T08:00:00Z", "0.1", "0.1");
        long cal2 = newCalibration("ANT-02", "2026-10-05T08:00:00Z", "0.1", "0.1");
        long t1 = newTask("T-A", "ANT-01", "2026-10-05T10:00:00Z", "2026-10-05T12:00:00Z");
        long t2 = newTask("T-B", "ANT-01", "2026-10-05T12:00:00Z", "2026-10-05T13:00:00Z");
        long t3 = newTask("T-C", "ANT-02", "2026-10-05T10:00:00Z", "2026-10-05T12:00:00Z");
        assertEquals(201, bind("req-a", t1, calId).getResponse().getStatus());
        assertEquals(201, bind("req-b", t2, calId).getResponse().getStatus());
        assertEquals(201, bind("req-c", t3, cal2).getResponse().getStatus());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM task_binding", Integer.class));
    }

    @Test
    void 同参重放返回原绑定且只有一条记录() throws Exception {
        long calId = newCalibration("ANT-01", "2026-10-05T08:00:00Z", "0.1", "0.1");
        long taskId = newTask("T-A", "ANT-01", "2026-10-05T10:00:00Z", "2026-10-05T12:00:00Z");
        long first = body(bind("req-same", taskId, calId)).get("id").asLong();
        MvcResult replay = bind("req-same", taskId, calId);
        assertEquals(first, body(replay).get("id").asLong());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM task_binding", Integer.class));
    }

    @Test
    void 同幂等键异参返回409() throws Exception {
        long calId = newCalibration("ANT-01", "2026-10-05T08:00:00Z", "0.1", "0.1");
        long t1 = newTask("T-A", "ANT-01", "2026-10-05T10:00:00Z", "2026-10-05T12:00:00Z");
        long t2 = newTask("T-B", "ANT-01", "2026-10-05T14:00:00Z", "2026-10-05T15:00:00Z");
        assertEquals(201, bind("req-dup", t1, calId).getResponse().getStatus());
        MvcResult conflict = bind("req-dup", t2, calId);
        assertEquals(409, conflict.getResponse().getStatus());
        assertEquals("REQUEST_ID_CONFLICT", body(conflict).get("code").asText());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM task_binding", Integer.class));
    }

    @Test
    void 失败不占键可换参重试成功() throws Exception {
        long calId = newCalibration("ANT-01", "2026-10-05T08:00:00Z", "0.1", "0.1");
        long badTask = newTask("T-BAD", "ANT-01", "2026-10-06T08:00:00Z", "2026-10-06T09:00:00Z");
        assertEquals(422, bind("req-retry", badTask, calId).getResponse().getStatus());
        long goodTask = newTask("T-GOOD", "ANT-01", "2026-10-05T10:00:00Z", "2026-10-05T11:00:00Z");
        MvcResult ok = bind("req-retry", goodTask, calId);
        assertEquals(201, ok.getResponse().getStatus());
        assertEquals(goodTask, body(ok).get("taskId").asLong());
    }

    @Test
    void 绑定固化校准编号与偏差不被后续校准覆盖() throws Exception {
        long cal1 = newCalibration("ANT-01", "2026-10-05T08:00:00Z", "0.10", "-0.08");
        long taskId = newTask("T-A", "ANT-01", "2026-10-05T10:00:00Z", "2026-10-05T12:00:00Z");
        assertEquals(201, bind("req-a", taskId, cal1).getResponse().getStatus());
        long cal2 = newCalibration("ANT-01", "2026-10-05T09:00:00Z", "0.15", "0.02");
        assertNotEquals(cal1, cal2);
        JsonNode task = getTask(taskId);
        JsonNode binding = task.get("bindings").get(0);
        assertEquals(cal1, binding.get("calibrationId").asLong());
        assertEquals(0, new java.math.BigDecimal("0.10")
                .compareTo(binding.get("azimuthDeviationDeg").decimalValue()));
        assertEquals(0, new java.math.BigDecimal("-0.08")
                .compareTo(binding.get("elevationDeviationDeg").decimalValue()));
    }

    @Test
    void 任务重复绑定返回409() throws Exception {
        long calId = newCalibration("ANT-01", "2026-10-05T08:00:00Z", "0.1", "0.1");
        long taskId = newTask("T-A", "ANT-01", "2026-10-05T10:00:00Z", "2026-10-05T12:00:00Z");
        assertEquals(201, bind("req-1", taskId, calId).getResponse().getStatus());
        MvcResult again = bind("req-2", taskId, calId);
        assertEquals(409, again.getResponse().getStatus());
        assertEquals("TASK_ALREADY_BOUND", body(again).get("code").asText());
    }
}