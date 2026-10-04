package com.example.starter.snow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 雪况读数上报与勘误：成功、超限封闭、幂等重放、整点校验与旧读数保护。
 */
class ReadingApiTests extends BaseSnowTest {

    private Map<String, Object> readingBody(String requestId, String hourUtc, int depth, int newSnow) {
        return Map.of("requestId", requestId, "hourUtc", hourUtc,
                "snowDepthCm", depth, "newSnowCm", newSnow, "source", "SYNTHETIC");
    }

    @Test
    void 上报合规读数成功且区域保持开放() throws Exception {
        MvcResult res = post("/api/areas/R-1/readings", readingBody("req-1", "2026-01-10T09:00:00Z", 100, 3));
        assertEquals(201, res.getResponse().getStatus());
        assertEquals(1, count("SELECT COUNT(*) FROM snow_reading WHERE area_id = 'R-1'"));
        MvcResult status = get("/api/areas/R-1/status");
        assertTrue(status.getResponse().getContentAsString().contains("\"state\":\"OPEN\""));
    }

    @Test
    void 超限读数自动创建封闭命令() throws Exception {
        MvcResult res = post("/api/areas/R-2/readings", readingBody("req-1", "2026-01-10T09:00:00Z", 100, 9));
        assertEquals(201, res.getResponse().getStatus());
        assertTrue(res.getResponse().getContentAsString().contains("\"closureCreated\":true"));
        assertEquals(1, count("SELECT COUNT(*) FROM closure_command WHERE area_id = 'R-2' AND status = 'OPEN'"));
        MvcResult status = get("/api/areas/R-2/status");
        assertTrue(status.getResponse().getContentAsString().contains("\"state\":\"CLOSED\""));
    }

    @Test
    void 相同requestId同参重放返回原响应且不重复写入() throws Exception {
        MvcResult first = post("/api/areas/R-3/readings", readingBody("req-1", "2026-01-10T09:00:00Z", 100, 3));
        MvcResult replay = post("/api/areas/R-3/readings", readingBody("req-1", "2026-01-10T09:00:00Z", 100, 3));
        assertEquals(201, replay.getResponse().getStatus());
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(1, count("SELECT COUNT(*) FROM snow_reading WHERE area_id = 'R-3'"));
    }

    @Test
    void 相同requestId异参返回409() throws Exception {
        post("/api/areas/R-4/readings", readingBody("req-1", "2026-01-10T09:00:00Z", 100, 3));
        MvcResult res = post("/api/areas/R-4/readings", readingBody("req-1", "2026-01-10T09:00:00Z", 100, 4));
        assertEquals(409, res.getResponse().getStatus());
        assertTrue(res.getResponse().getContentAsString().contains("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void 非整点读数返回422() throws Exception {
        MvcResult res = post("/api/areas/R-5/readings", readingBody("req-1", "2026-01-10T09:30:00Z", 100, 3));
        assertEquals(422, res.getResponse().getStatus());
        assertTrue(res.getResponse().getContentAsString().contains("INVALID_HOUR"));
    }

    @Test
    void 重复整点读数返回409旧读数仅可勘误() throws Exception {
        seedArea("R-6");
        seedReading("R-6", windowHour(0), 100, 3);
        MvcResult res = post("/api/areas/R-6/readings", readingBody("req-1", windowHour(0).toString(), 100, 4));
        assertEquals(409, res.getResponse().getStatus());
        assertTrue(res.getResponse().getContentAsString().contains("READING_ALREADY_EXISTS"));
    }

    @Test
    void 勘误追加成功且原读数不变() throws Exception {
        seedArea("R-7");
        seedReading("R-7", windowHour(0), 100, 3);
        MvcResult res = post("/api/areas/R-7/readings/" + windowHour(0) + "/corrections",
                Map.of("requestId", "c-1", "reviewer", "checker-1", "correctedNewSnowCm", 4, "note", "复核修正"));
        assertEquals(201, res.getResponse().getStatus());
        assertEquals(3, count("SELECT new_snow_cm FROM snow_reading WHERE area_id = 'R-7'"));
        assertEquals(1, count("SELECT COUNT(*) FROM reading_correction"));
    }

    @Test
    void 勘误后超限同样触发封闭() throws Exception {
        seedArea("R-8");
        seedReading("R-8", windowHour(0), 100, 3);
        MvcResult res = post("/api/areas/R-8/readings/" + windowHour(0) + "/corrections",
                Map.of("requestId", "c-1", "reviewer", "checker-1", "correctedNewSnowCm", 8));
        assertEquals(201, res.getResponse().getStatus());
        assertTrue(res.getResponse().getContentAsString().contains("\"closureCreated\":true"));
        assertEquals(1, count("SELECT COUNT(*) FROM closure_command WHERE area_id = 'R-8' AND status = 'OPEN'"));
    }

    @Test
    void 勘误不存在的读数返回404() throws Exception {
        seedArea("R-9");
        MvcResult res = post("/api/areas/R-9/readings/2026-01-10T09:00:00Z/corrections",
                Map.of("requestId", "c-1", "reviewer", "checker-1", "correctedNewSnowCm", 4));
        assertEquals(404, res.getResponse().getStatus());
    }
}