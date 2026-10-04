package com.example.snowpack;

import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 解除封闭双人审核的 API 测试（真实 H2）。 */
class ReleaseReviewApiTest extends BaseIntegrationTest {

    @Test
    void twoDistinctReviewersCompleteReleaseAndTimelineIsQueryable() throws Exception {
        closedZoneWithGoodWindow("r-a");

        confirm("r-a", "v-1", "alice")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("IN_PROGRESS")))
                .andExpect(jsonPath("$.firstReviewer", is("alice")))
                .andExpect(jsonPath("$.decisionVersion", nullValue()));

        confirm("r-a", "v-2", "bob")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.secondReviewer", is("bob")))
                .andExpect(jsonPath("$.decisionVersion", is(1)));

        mockMvc.perform(get("/api/zones/{zoneId}/status", "r-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("OPEN")))
                .andExpect(jsonPath("$.window.complete", is(true)))
                .andExpect(jsonPath("$.window.consecutive", is(true)))
                .andExpect(jsonPath("$.window.allWithinLimit", is(true)))
                .andExpect(jsonPath("$.latestReview.status", is("COMPLETED")))
                .andExpect(jsonPath("$.latestReview.firstReviewer", is("alice")))
                .andExpect(jsonPath("$.latestReview.secondReviewer", is("bob")))
                .andExpect(jsonPath("$.timeline[0].type", is("CLOSURE")))
                .andExpect(jsonPath("$.timeline[1].type", is("CLOSURE_LIFTED")))
                .andExpect(jsonPath("$.timeline[2].type", is("RELEASE")))
                .andExpect(jsonPath("$.timeline[2].versionNo", is(1)));

        org.junit.jupiter.api.Assertions.assertEquals(1, countRows("release_decision", "r-a"));
    }

    @Test
    void newOverLimitReadingAfterReleaseCreatesNewClosureButKeepsDecision() throws Exception {
        closedZoneWithGoodWindow("r-b");
        confirm("r-b", "v-1", "alice").andExpect(status().isOk());
        confirm("r-b", "v-2", "bob").andExpect(status().isOk());

        postReading("r-b", "r-11", "2026-10-04T11:00:00Z", 130, 9)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overLimit", is(true)))
                .andExpect(jsonPath("$.closureCreated", is(true)));

        mockMvc.perform(get("/api/zones/{zoneId}/status", "r-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CLOSED")));

        // 原解除决定保留，新封闭命令追加
        org.junit.jupiter.api.Assertions.assertEquals(1, countRows("release_decision", "r-b"));
        org.junit.jupiter.api.Assertions.assertEquals(2, countRows("closure_command", "r-b"));
        org.junit.jupiter.api.Assertions.assertEquals(
                "LIFTED", jdbc.queryForObject(
                        "SELECT status FROM closure_command WHERE zone_id = 'r-b' ORDER BY id LIMIT 1",
                        String.class));
        org.junit.jupiter.api.Assertions.assertEquals(
                "ACTIVE", jdbc.queryForObject(
                        "SELECT status FROM closure_command WHERE zone_id = 'r-b' ORDER BY id DESC LIMIT 1",
                        String.class));
    }

    @Test
    void syntheticWindow456IsRejectedBecauseLastHourExceedsLimit() throws Exception {
        postClosure("r-c", "c-1", "patrol closure").andExpect(status().isOk());
        postReading("r-c", "r-08", "2026-10-04T08:00:00Z", 110, 4).andExpect(status().isOk());
        postReading("r-c", "r-09", "2026-10-04T09:00:00Z", 115, 5).andExpect(status().isOk());
        postReading("r-c", "r-10", "2026-10-04T10:00:00Z", 121, 6).andExpect(status().isOk());

        // 三小时新增积雪 4/5/6，平均值 5 不超限，但最后一小时 6 超限，必须拒绝
        confirm("r-c", "v-1", "alice")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", is("OVER_LIMIT_READING_IN_WINDOW")));
    }

    @Test
    void missingHourlyReadingReturns422() throws Exception {
        postClosure("r-d", "c-1", "patrol closure").andExpect(status().isOk());
        postReading("r-d", "r-08", "2026-10-04T08:00:00Z", 110, 4).andExpect(status().isOk());
        postReading("r-d", "r-10", "2026-10-04T10:00:00Z", 120, 5).andExpect(status().isOk());

        confirm("r-d", "v-1", "alice")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", is("MISSING_HOURLY_READING")));
    }

    @Test
    void fewerThanThreeReadingsReturns422() throws Exception {
        postClosure("r-e", "c-1", "patrol closure").andExpect(status().isOk());
        postReading("r-e", "r-10", "2026-10-04T10:00:00Z", 120, 5).andExpect(status().isOk());

        confirm("r-e", "v-1", "alice")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", is("MISSING_HOURLY_READING")));
    }

    @Test
    void sameReviewerTwiceReturns422() throws Exception {
        closedZoneWithGoodWindow("r-f");
        confirm("r-f", "v-1", "alice").andExpect(status().isOk());
        confirm("r-f", "v-2", "alice")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", is("SAME_REVIEWER")));
        org.junit.jupiter.api.Assertions.assertEquals(0, countRows("release_decision", "r-f"));
    }

    @Test
    void reviewOnOpenZoneReturns409() throws Exception {
        postReading("r-g", "r-08", "2026-10-04T08:00:00Z", 110, 4).andExpect(status().isOk());
        postReading("r-g", "r-09", "2026-10-04T09:00:00Z", 115, 5).andExpect(status().isOk());
        postReading("r-g", "r-10", "2026-10-04T10:00:00Z", 120, 5).andExpect(status().isOk());

        confirm("r-g", "v-1", "alice")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("ZONE_NOT_CLOSED")));
    }

    @Test
    void overLimitReadingInvalidatesPendingReviewAndSecondConfirmFails() throws Exception {
        closedZoneWithGoodWindow("r-h");
        confirm("r-h", "v-1", "alice").andExpect(status().isOk());

        postReading("r-h", "r-11", "2026-10-04T11:00:00Z", 128, 8)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closureCreated", is(false)))
                .andExpect(jsonPath("$.reviewsInvalidated", is(1)));

        // 第二人确认落到新的窗口校验：最近三小时含超限读数，拒绝
        confirm("r-h", "v-2", "bob")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", is("OVER_LIMIT_READING_IN_WINDOW")));

        mockMvc.perform(get("/api/zones/{zoneId}/status", "r-h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CLOSED")))
                .andExpect(jsonPath("$.latestReview.status", is("INVALIDATED")))
                .andExpect(jsonPath("$.latestReview.invalidateReason", is("OVER_LIMIT_READING")))
                .andExpect(jsonPath("$.latestReview.firstReviewer", is("alice")));
        org.junit.jupiter.api.Assertions.assertEquals(0, countRows("release_decision", "r-h"));
    }

    @Test
    void newWithinLimitReadingBeforeSecondConfirmReturns409StaleVersion() throws Exception {
        closedZoneWithGoodWindow("r-i");
        confirm("r-i", "v-1", "alice").andExpect(status().isOk());

        postReading("r-i", "r-11", "2026-10-04T11:00:00Z", 122, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewsInvalidated", is(0)));

        confirm("r-i", "v-2", "bob")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("STALE_READING_VERSION")));
        org.junit.jupiter.api.Assertions.assertEquals(0, countRows("release_decision", "r-i"));
    }

    @Test
    void reviewConfirmIsIdempotentByRequestId() throws Exception {
        closedZoneWithGoodWindow("r-j");
        confirm("r-j", "v-1", "alice").andExpect(status().isOk());
        confirm("r-j", "v-1", "alice")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed", is(true)))
                .andExpect(jsonPath("$.status", is("IN_PROGRESS")));
        confirm("r-j", "v-1", "mallory")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("REQUEST_ID_CONFLICT")));

        confirm("r-j", "v-2", "bob").andExpect(status().isOk());
        confirm("r-j", "v-2", "bob")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed", is(true)))
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.decisionVersion", is(1)));
        org.junit.jupiter.api.Assertions.assertEquals(1, countRows("release_decision", "r-j"));
    }

    @Test
    void staleReadingVersionReturns409() throws Exception {
        closedZoneWithGoodWindow("r-k");
        confirm("r-k", "v-1", "alice").andExpect(status().isOk());

        // 审核发起后到来新的合格读数：读数版本变化，第二人确认必须基于一致版本
        postReading("r-k", "r-11", "2026-10-04T11:00:00Z", 122, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overLimit", is(false)));

        confirm("r-k", "v-2", "bob")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("STALE_READING_VERSION")));
        org.junit.jupiter.api.Assertions.assertEquals(0, countRows("release_decision", "r-k"));
    }

    @Test
    void reviewReplayReturnsCurrentState() throws Exception {
        closedZoneWithGoodWindow("r-l");
        confirm("r-l", "v-1", "alice").andExpect(status().isOk());

        // 第一人同参重放返回当前状态，异参 409
        confirm("r-l", "v-1", "alice")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("IN_PROGRESS")))
                .andExpect(jsonPath("$.replayed", is(true)));
        confirm("r-l", "v-1", "mallory")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("REQUEST_ID_CONFLICT")));

        confirm("r-l", "v-2", "bob").andExpect(status().isOk());

        // 完成后重放第一人请求返回完成状态与版本号
        confirm("r-l", "v-1", "alice")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.decisionVersion", is(1)))
                .andExpect(jsonPath("$.replayed", is(true)));
        org.junit.jupiter.api.Assertions.assertEquals(1, countRows("release_decision", "r-l"));
    }
}