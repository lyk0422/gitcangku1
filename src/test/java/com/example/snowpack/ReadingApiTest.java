package com.example.snowpack;

import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 雪况上报、勘误与封闭命令的 API 测试（真实 H2）。 */
class ReadingApiTest extends BaseIntegrationTest {

    private long readingIdOf(String zoneId) {
        return jdbc.queryForObject("SELECT id FROM snow_reading WHERE zone_id = ?", Long.class, zoneId);
    }

    @Test
    void postReadingSucceedsAndSameParamsReplayReturnsSameRecord() throws Exception {
        postReading("z-a", "r-1", "2026-10-04T08:00:00Z", 110, 4)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overLimit", is(false)))
                .andExpect(jsonPath("$.replayed", is(false)));

        long id = readingIdOf("z-a");
        postReading("z-a", "r-1", "2026-10-04T08:00:00Z", 110, 4)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is((int) id)))
                .andExpect(jsonPath("$.replayed", is(true)));

        org.junit.jupiter.api.Assertions.assertEquals(1, countRows("snow_reading", "z-a"));
    }

    @Test
    void sameRequestIdWithDifferentParamsReturns409() throws Exception {
        postReading("z-b", "r-1", "2026-10-04T08:00:00Z", 110, 4)
                .andExpect(status().isOk());
        postReading("z-b", "r-1", "2026-10-04T08:00:00Z", 110, 9)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("REQUEST_ID_CONFLICT")));
    }

    @Test
    void nonHourlyTimestampReturns422() throws Exception {
        postReading("z-c", "r-1", "2026-10-04T08:30:00Z", 110, 4)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", is("NON_HOURLY_TIMESTAMP")));
    }

    @Test
    void duplicateHourReadingReturns409AndErrataAppends() throws Exception {
        postReading("z-d", "r-1", "2026-10-04T08:00:00Z", 110, 4)
                .andExpect(status().isOk());
        postReading("z-d", "r-2", "2026-10-04T08:00:00Z", 111, 3)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("DUPLICATE_HOUR_READING")));

        long readingId = readingIdOf("z-d");
        postErrata("z-d", readingId, "e-1", "sensor recalibrated", "CONFIRMED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readingId", is((int) readingId)))
                .andExpect(jsonPath("$.replayed", is(false)));
        postErrata("z-d", readingId, "e-1", "sensor recalibrated", "CONFIRMED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed", is(true)));
        postErrata("z-d", readingId, "e-1", "different note", "CONFIRMED")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("REQUEST_ID_CONFLICT")));
        postErrata("z-d", 999999L, "e-2", "no such reading", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("READING_NOT_FOUND")));

        // 原读数未被勘误修改
        org.junit.jupiter.api.Assertions.assertEquals(
                4, jdbc.queryForObject(
                        "SELECT new_snow_cm FROM snow_reading WHERE zone_id = 'z-d'", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(
                1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM reading_errata WHERE reading_id = ?",
                        Integer.class, readingId));
    }

    @Test
    void overLimitReadingCreatesClosureAndMarksZoneClosed() throws Exception {
        postReading("z-e", "r-1", "2026-10-04T08:00:00Z", 110, 12)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overLimit", is(true)))
                .andExpect(jsonPath("$.closureCreated", is(true)));

        mockMvc.perform(get("/api/zones/{zoneId}/status", "z-e"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CLOSED")))
                .andExpect(jsonPath("$.timeline[0].type", is("CLOSURE")))
                .andExpect(jsonPath("$.timeline[0].at", is("2026-10-04T00:00:00Z")));
    }

    @Test
    void manualClosureIsIdempotentAndConflictsWhenAlreadyClosed() throws Exception {
        postClosure("z-f", "c-1", "patrol decision")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source", is("MANUAL")))
                .andExpect(jsonPath("$.replayed", is(false)));
        postClosure("z-f", "c-1", "patrol decision")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed", is(true)));
        postClosure("z-f", "c-1", "other reason")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("REQUEST_ID_CONFLICT")));
        postClosure("z-f", "c-2", "second closure")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("ZONE_ALREADY_CLOSED")));

        org.junit.jupiter.api.Assertions.assertEquals(1, countRows("closure_command", "z-f"));
    }

    @Test
    void statusOfUnknownZoneReturns404() throws Exception {
        mockMvc.perform(get("/api/zones/{zoneId}/status", "z-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("ZONE_NOT_FOUND")));
    }
}