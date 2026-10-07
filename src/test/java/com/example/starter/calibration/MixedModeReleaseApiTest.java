package com.example.starter.calibration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 线性与二次混批放行测试：任一失败全批回滚，不产生部分成功；混批全合法时整批原子放行。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MixedModeReleaseApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private void createCerts() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-L","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-Q","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","mode":"QUADRATIC",
                                 "a":"1","b":"0","c":"0","domainLo":"1","domainHi":"3"}"""))
                .andExpect(status().isCreated());
    }

    private void submit(String key, String instrument, String reading, String lo, String hi,
                        String by) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"%s",
                                 "lowerLimit":"%s","upperLimit":"%s","submittedBy":"%s"}
                                """.formatted(key, instrument, reading, lo, hi, by)))
                .andExpect(status().isCreated());
    }

    @Test
    void mixedValidBatchReleasedAtomically() throws Exception {
        createCerts();
        submit("L-1", "INS-L", "5", "0", "9", "alice");       // 线性合格
        submit("Q-1", "INS-Q", "4", "2", "2", "bob");         // 二次合格（根 2）

        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"L-1\",\"Q-1\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released.length()").value(2));

        mvc.perform(get("/api/measurements/{key}", "Q-1"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(true));
        mvc.perform(get("/api/measurements/usable"))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void mixedBatchWithFailureRollsBackEntireBatch() throws Exception {
        createCerts();
        submit("L-OK", "INS-L", "5", "0", "9", "alice");            // 线性合格
        submit("Q-BAD", "INS-Q", "4.000001", "2", "2", "bob");      // 二次不合格

        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"L-OK\",\"Q-BAD\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_REJECTED"))
                .andExpect(jsonPath("$.failures[?(@.key=='Q-BAD')].reasons[0]").value("NOT_PASSED"))
                .andExpect(jsonPath("$.failures.length()").value(1));

        // 整批回滚：合格的线性测量也未被放行，无放行历史
        mvc.perform(get("/api/measurements/{key}", "L-OK"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.usable").value(false));
        Integer releaseCount = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        assertEquals(0, releaseCount);
    }

    @Test
    void mixedBatchSameActorRejectedAndNothingReleased() throws Exception {
        createCerts();
        submit("L-1", "INS-L", "5", "0", "9", "alice");
        submit("Q-1", "INS-Q", "4", "2", "2", "bob");

        // 放行人等于二次测量提交人 → 整批 409
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"L-1\",\"Q-1\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[?(@.key=='Q-1')].reasons[0]").value("SAME_ACTOR"));

        mvc.perform(get("/api/measurements/{key}", "L-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get("/api/measurements/{key}", "Q-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }
}
