package com.example.starter.pass.web;

import com.example.starter.pass.api.ErrorBody;
import com.example.starter.pass.api.SlotView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 端到端 HTTP 入口测试（随机端口真实启动 Web 容器与 H2）：
 * 验证状态码、可区分拒绝原因码、时间线查询与取消释放后的再次占用。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PassControllerHttpTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<String> createdSatellites = new ArrayList<>();

    @AfterEach
    void cleanup() {
        if (createdSatellites.isEmpty()) {
            return;
        }
        String in = String.join(",", createdSatellites.stream().map(s -> "?").toList());
        jdbcTemplate.update("DELETE FROM idempotent_request WHERE allocation_id IN "
                + "(SELECT id FROM slot_allocation WHERE satellite_code IN (" + in + "))",
                createdSatellites.toArray());
        jdbcTemplate.update("DELETE FROM slot_allocation WHERE satellite_code IN (" + in + ")",
                createdSatellites.toArray());
        jdbcTemplate.update("DELETE FROM pass_prediction WHERE satellite_code IN (" + in + ")",
                createdSatellites.toArray());
        jdbcTemplate.update("DELETE FROM satellite WHERE code IN (" + in + ")",
                createdSatellites.toArray());
        createdSatellites.clear();
    }

    @Test
    void httpEndToEndAllocateConflictCompleteQueryAndCancel() {
        String sat = "W-" + UUID.randomUUID().toString().substring(0, 8);
        createdSatellites.add(sat);

        Map<String, Object> prediction = Map.of(
                "satelliteCode", sat,
                "visibleStart", "2026-10-01T12:00:00Z",
                "visibleEnd", "2026-10-01T12:10:00Z",
                "minElevationDeg", 15.00,
                "plannedBytes", 300_000);
        ResponseEntity<Map> created = restTemplate.postForEntity(
                "/api/predictions", new HttpEntity<>(prediction), Map.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertEquals(1, ((Number) created.getBody().get("revisionNo")).intValue());

        Map<String, Object> allocate = Map.of(
                "requestId", UUID.randomUUID().toString(),
                "satelliteCode", sat,
                "stationCode", "A",
                "windowStart", "2026-10-01T12:00:00Z",
                "windowEnd", "2026-10-01T12:05:00Z");
        ResponseEntity<SlotView> first = restTemplate.postForEntity(
                "/api/slots/allocate", new HttpEntity<>(allocate), SlotView.class);
        assertEquals(HttpStatus.CREATED, first.getStatusCode());
        SlotView slot = first.getBody();
        assertNotNull(slot);
        assertEquals("CONFIRMED", slot.status());
        assertEquals("ANT-A", slot.antennaCode());
        long slotId = slot.id();

        // 同天线重叠请求 → HTTP 409 且原因码可区分
        Map<String, Object> overlap = Map.of(
                "requestId", UUID.randomUUID().toString(),
                "satelliteCode", sat,
                "stationCode", "A",
                "windowStart", "2026-10-01T12:00:00Z",
                "windowEnd", "2026-10-01T12:05:00Z");
        ResponseEntity<ErrorBody> conflict = restTemplate.postForEntity(
                "/api/slots/allocate", new HttpEntity<>(overlap), ErrorBody.class);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals("ANTENNA_CONFLICT", conflict.getBody().code());

        // 完成回报
        Map<String, Object> completion = Map.of(
                "requestId", UUID.randomUUID().toString(),
                "actualBytes", 299_999);
        ResponseEntity<SlotView> received = restTemplate.exchange(
                "/api/slots/{id}/complete", HttpMethod.POST,
                new HttpEntity<>(completion), SlotView.class, slotId);
        assertEquals(HttpStatus.OK, received.getStatusCode());
        assertEquals("RECEIVED", received.getBody().status());
        assertEquals(299_999L, received.getBody().actualBytes());

        // 时间线：该卫星最终只有一个接收站、一份计划
        ResponseEntity<SlotView[]> timeline = restTemplate.getForEntity(
                "/api/satellites/{code}/slots", SlotView[].class, sat);
        assertEquals(HttpStatus.OK, timeline.getStatusCode());
        SlotView[] slots = timeline.getBody();
        assertEquals(1, slots.length);
        assertEquals("A", slots[0].stationCode());
        assertEquals(1, slots[0].predictionRevisionNo());

        // 预测版本时间线
        ResponseEntity<Map[]> versions = restTemplate.getForEntity(
                "/api/satellites/{code}/predictions", Map[].class, sat);
        assertEquals(HttpStatus.OK, versions.getStatusCode());
        assertEquals(1, versions.getBody().length);

        // 已接收再取消 → 409 终态冲突
        ResponseEntity<ErrorBody> lateCancel = restTemplate.exchange(
                "/api/slots/{id}/cancel", HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "迟到的取消")), ErrorBody.class, slotId);
        assertEquals(HttpStatus.CONFLICT, lateCancel.getStatusCode());
        assertEquals("SLOT_ALREADY_TERMINATED", lateCancel.getBody().code());
    }

    @Test
    void httpCancelReleasesAntennaForNextBooking() {
        String sat = "X-" + UUID.randomUUID().toString().substring(0, 8);
        createdSatellites.add(sat);

        restTemplate.postForEntity("/api/predictions", new HttpEntity<>(Map.of(
                "satelliteCode", sat,
                "visibleStart", "2026-10-01T12:00:00Z",
                "visibleEnd", "2026-10-01T12:10:00Z",
                "minElevationDeg", 15.00,
                "plannedBytes", 100_000)), Map.class);

        ResponseEntity<SlotView> first = restTemplate.postForEntity(
                "/api/slots/allocate", new HttpEntity<>(Map.of(
                        "requestId", UUID.randomUUID().toString(),
                        "satelliteCode", sat,
                        "stationCode", "A",
                        "windowStart", "2026-10-01T12:00:00Z",
                        "windowEnd", "2026-10-01T12:05:00Z")), SlotView.class);
        long slotId = first.getBody().id();

        // 取消前：重叠 409
        ResponseEntity<ErrorBody> blocked = restTemplate.postForEntity(
                "/api/slots/allocate", new HttpEntity<>(Map.of(
                        "requestId", UUID.randomUUID().toString(),
                        "satelliteCode", sat,
                        "stationCode", "A",
                        "windowStart", "2026-10-01T12:00:00Z",
                        "windowEnd", "2026-10-01T12:05:00Z")), ErrorBody.class);
        assertEquals(HttpStatus.CONFLICT, blocked.getStatusCode());

        // 取消（明确原因）后：同窗口可再分配
        ResponseEntity<SlotView> cancelled = restTemplate.exchange(
                "/api/slots/{id}/cancel", HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "设备检修取消")), SlotView.class, slotId);
        assertEquals(HttpStatus.OK, cancelled.getStatusCode());
        assertEquals("CANCELLED", cancelled.getBody().status());
        assertEquals("设备检修取消", cancelled.getBody().cancelReason());

        ResponseEntity<SlotView> rebooked = restTemplate.postForEntity(
                "/api/slots/allocate", new HttpEntity<>(Map.of(
                        "requestId", UUID.randomUUID().toString(),
                        "satelliteCode", sat,
                        "stationCode", "A",
                        "windowStart", "2026-10-01T12:00:00Z",
                        "windowEnd", "2026-10-01T12:05:00Z")), SlotView.class);
        assertEquals(HttpStatus.CREATED, rebooked.getStatusCode());
        assertEquals("CONFIRMED", rebooked.getBody().status());
        assertArrayEquals(new String[]{"CANCELLED", "CONFIRMED"},
                java.util.Arrays.stream(restTemplate.getForEntity(
                                "/api/satellites/{code}/slots", SlotView[].class, sat).getBody())
                        .map(SlotView::status).sorted().toArray(String[]::new));
    }

    @Test
    void httpValidationAndNotFoundAreMapped() {
        // Bean 校验失败 → 400
        ResponseEntity<ErrorBody> invalid = restTemplate.postForEntity(
                "/api/slots/allocate", new HttpEntity<>(Map.of(
                        "satelliteCode", "",
                        "stationCode", "A")), ErrorBody.class);
        assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());
        assertEquals("INVALID_REQUEST", invalid.getBody().code());

        // 不存在的时隙 → 404
        ResponseEntity<ErrorBody> missing = restTemplate.getForEntity(
                "/api/slots/999999", ErrorBody.class);
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        assertEquals("NOT_FOUND", missing.getBody().code());
    }
}
