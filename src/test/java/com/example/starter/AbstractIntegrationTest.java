package com.example.starter;

import com.example.starter.support.MutableClock;
import com.example.starter.support.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 集成测试基类：真实 H2（MODE=MySQL）内存库，逐用例清理场景数据并重建站点。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
public abstract class AbstractIntegrationTest {

    protected static final String STATION_A = "ST-A";
    protected static final String STATION_B = "ST-B";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM slot_allocation");
        jdbc.update("DELETE FROM pass_prediction");
        jdbc.update("DELETE FROM ground_station");
        jdbc.update("INSERT INTO ground_station"
                        + " (code, name, antenna_capability, min_elevation_deg, capacity_bytes_per_min)"
                        + " VALUES (?, ?, ?, ?, ?)",
                STATION_A, "站点A", "S-BAND", 5.00, 1_000_000);
        jdbc.update("INSERT INTO ground_station"
                        + " (code, name, antenna_capability, min_elevation_deg, capacity_bytes_per_min)"
                        + " VALUES (?, ?, ?, ?, ?)",
                STATION_B, "站点B", "X-BAND", 10.00, 4_000_000);
        clock.setInstant(java.time.Instant.parse("2026-10-04T11:00:00Z"));
    }

    /** 发布预测请求体。 */
    protected static String predictionBody(String satellite, String start, String end,
            String minElevation, long plannedBytes) {
        return """
                {"satelliteCode":"%s","windowStartUtc":"%s","windowEndUtc":"%s",\
                "minElevationDeg":%s,"plannedBytes":%d}"""
                .formatted(satellite, start, end, minElevation, plannedBytes);
    }

    /** 分配申请请求体。 */
    protected static String allocateBody(String requestId, String satellite, String station,
            String slotStart, String slotEnd) {
        return """
                {"requestId":"%s","satelliteCode":"%s","stationCode":"%s",\
                "slotStartUtc":"%s","slotEndUtc":"%s"}"""
                .formatted(requestId, satellite, station, slotStart, slotEnd);
    }
}