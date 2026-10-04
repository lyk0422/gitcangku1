package com.example.starter.snow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 解除封闭双人审核：窗口校验、双人确认、失效与重新封闭。
 */
class LiftReviewApiTests extends BaseSnowTest {

    private long createReview(String area, String requestId, String reviewer) throws Exception {
        MvcResult res = post("/api/areas/" + area + "/lift-reviews",
                Map.of("requestId", requestId, "reviewer", reviewer));
        assertEquals(201, res.getResponse().getStatus());
        return om.readTree(res.getResponse().getContentAsString()).get("reviewId").asLong();
    }

    private MvcResult confirm(String area, long reviewId, String requestId, String reviewer) throws Exception {
        return post("/api/areas/" + area + "/lift-reviews/" + reviewId + "/confirmations",
                Map.of("requestId", requestId, "reviewer", reviewer));
    }

    private void postReading(String area, String requestId, String hourUtc, int newSnow) throws Exception {
        MvcResult res = post("/api/areas/" + area + "/readings",
                Map.of("requestId", requestId, "hourUtc", hourUtc, "snowDepthCm", 100, "newSnowCm", newSnow));
        assertEquals(201, res.getResponse().getStatus());
    }

    @Test
    void 区域未封闭时解除返回409() throws Exception {
        seedArea("L-1");
        seedWindow("L-1", 3, 4, 5);
        MvcResult res = post("/api/areas/L-1/lift-reviews", Map.of("requestId", "r-1", "reviewer", "alice"));
        assertEquals(409, res.getResponse().getStatus());
        assertTrue(res.getResponse().getContentAsString().contains("AREA_NOT_CLOSED"));
    }

    @Test
    void 缺整点读数时解除返回422() throws Exception {
        seedArea("L-2");
        seedClosure("L-2", "历史异常");
        seedReading("L-2", windowHour(0), 100, 3);
        seedReading("L-2", windowHour(2), 100, 4);
        MvcResult res = post("/api/areas/L-2/lift-reviews", Map.of("requestId", "r-1", "reviewer", "alice"));
        assertEquals(422, res.getResponse().getStatus());
        assertTrue(res.getResponse().getContentAsString().contains("MISSING_HOURLY_READINGS"));
    }

    @Test
    void 合成三小时新增积雪4_5_6时解除被拒() throws Exception {
        seedArea("L-3");
        seedClosure("L-3", "历史异常");
        seedWindow("L-3", 4, 5, 6);
        MvcResult res = post("/api/areas/L-3/lift-reviews", Map.of("requestId", "r-1", "reviewer", "alice"));
        assertEquals(422, res.getResponse().getStatus());
        assertTrue(res.getResponse().getContentAsString().contains("WINDOW_EXCEEDS_LIMIT"));
        assertEquals(0, count("SELECT COUNT(*) FROM lift_review WHERE area_id = 'L-3'"));
    }

    @Test
    void 双人确认后生成解除版本并解除封闭() throws Exception {
        seedArea("L-4");
        seedClosure("L-4", "历史异常");
        seedWindow("L-4", 3, 4, 5);
        long reviewId = createReview("L-4", "r-1", "alice");
        MvcResult res = confirm("L-4", reviewId, "r-2", "bob");
        assertEquals(200, res.getResponse().getStatus());
        String body = res.getResponse().getContentAsString();
        assertTrue(body.contains("\"status\":\"CONFIRMED\""));
        assertTrue(body.contains("\"liftVersion\":1"));
        assertEquals(1, count("SELECT COUNT(*) FROM lift_decision WHERE area_id = 'L-4' AND version = 1"));
        assertEquals(0, count("SELECT COUNT(*) FROM closure_command WHERE area_id = 'L-4' AND status = 'OPEN'"));
        String status = get("/api/areas/L-4/status").getResponse().getContentAsString();
        assertTrue(status.contains("\"state\":\"OPEN\""));
        assertTrue(status.contains("alice") && status.contains("bob"));
        assertTrue(status.contains("LIFT_DECIDED") && status.contains("CLOSURE_LIFTED"));
    }

    @Test
    void 同一审核员重复签字返回409() throws Exception {
        seedArea("L-5");
        seedClosure("L-5", "历史异常");
        seedWindow("L-5", 3, 4, 5);
        long reviewId = createReview("L-5", "r-1", "alice");
        MvcResult res = confirm("L-5", reviewId, "r-2", "alice");
        assertEquals(409, res.getResponse().getStatus());
        assertTrue(res.getResponse().getContentAsString().contains("DUPLICATE_REVIEWER"));
        assertEquals(0, count("SELECT COUNT(*) FROM lift_decision WHERE area_id = 'L-5'"));
    }

    @Test
    void 新超限读数使未完成审核失效且查询可见原因() throws Exception {
        seedArea("L-6");
        seedClosure("L-6", "历史异常");
        seedWindow("L-6", 3, 4, 5);
        long reviewId = createReview("L-6", "r-1", "alice");
        postReading("L-6", "r-x", "2026-01-10T12:00:00Z", 9);
        assertEquals("INVALIDATED", jdbc.queryForObject(
                "SELECT status FROM lift_review WHERE id = ?", String.class, reviewId));
        MvcResult res = confirm("L-6", reviewId, "r-2", "bob");
        assertEquals(409, res.getResponse().getStatus());
        String status = get("/api/areas/L-6/status").getResponse().getContentAsString();
        assertTrue(status.contains("NEW_OVER_LIMIT_READING"));
        assertEquals(0, count("SELECT COUNT(*) FROM lift_decision WHERE area_id = 'L-6'"));
    }

    @Test
    void 解除成功后新超限读数创建新封闭且保留原解除决定() throws Exception {
        seedArea("L-7");
        seedClosure("L-7", "历史异常");
        seedWindow("L-7", 3, 4, 5);
        long reviewId = createReview("L-7", "r-1", "alice");
        assertEquals(200, confirm("L-7", reviewId, "r-2", "bob").getResponse().getStatus());
        postReading("L-7", "r-x", "2026-01-10T12:00:00Z", 8);
        assertEquals(1, count("SELECT COUNT(*) FROM lift_decision WHERE area_id = 'L-7'"));
        assertEquals(1, count("SELECT COUNT(*) FROM closure_command WHERE area_id = 'L-7' AND status = 'CLOSED'"));
        assertEquals(1, count("SELECT COUNT(*) FROM closure_command WHERE area_id = 'L-7' AND status = 'OPEN'"));
        String status = get("/api/areas/L-7/status").getResponse().getContentAsString();
        assertTrue(status.contains("\"state\":\"CLOSED\""));
        assertTrue(status.contains("LIFT_DECIDED"));
    }

    @Test
    void 审核期间读数版本变化时确认返回409() throws Exception {
        seedArea("L-8");
        seedClosure("L-8", "历史异常");
        seedWindow("L-8", 3, 4, 5);
        long reviewId = createReview("L-8", "r-1", "alice");
        postReading("L-8", "r-x", "2026-01-10T12:00:00Z", 2);
        MvcResult res = confirm("L-8", reviewId, "r-2", "bob");
        assertEquals(409, res.getResponse().getStatus());
        assertTrue(res.getResponse().getContentAsString().contains("READINGS_VERSION_CONFLICT"));
        assertEquals(0, count("SELECT COUNT(*) FROM lift_decision WHERE area_id = 'L-8'"));
    }

    @Test
    void 审核创建幂等重放与异参冲突() throws Exception {
        seedArea("L-9");
        seedClosure("L-9", "历史异常");
        seedWindow("L-9", 3, 4, 5);
        long reviewId = createReview("L-9", "r-1", "alice");
        long again = createReview("L-9", "r-1", "alice");
        assertEquals(reviewId, again);
        assertEquals(1, count("SELECT COUNT(*) FROM lift_review WHERE area_id = 'L-9'"));
        MvcResult res = post("/api/areas/L-9/lift-reviews", Map.of("requestId", "r-1", "reviewer", "carol"));
        assertEquals(409, res.getResponse().getStatus());
    }
}