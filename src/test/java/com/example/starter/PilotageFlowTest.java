package com.example.starter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 主流程与拒绝原因测试：申请、指派、登轮点变更、登轮、离轮、幂等重放与时间轴查询。
 */
class PilotageFlowTest extends AbstractPilotageTest {

    @Test
    void fullChainWithIdempotentReplayAndTimeline() throws Exception {
        long pilot = pilotId("P001");
        String segResp = mvc.perform(post("/api/segments").contentType(MediaType.APPLICATION_JSON).content("""
                {"vesselCode":"MV-HAPPY","requiredGrade":2,"estStartUtc":"2026-10-04T09:00:00Z",
                 "estEndUtc":"2026-10-04T10:00:00Z","boardingPoint":"锚地A"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.originalBoardingPoint").value("锚地A"))
                .andReturn().getResponse().getContentAsString();
        long segId = om.readTree(segResp).get("id").asLong();

        String assignBody = """
                {"requestId":"r-happy-1","segmentId":%d,"pilotId":%d}""".formatted(segId, pilot);
        String a1 = mvc.perform(post("/api/assignments").contentType(MediaType.APPLICATION_JSON).content(assignBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.estStartUtc").value("2026-10-04T09:00:00Z"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn().getResponse().getContentAsString();
        long assignmentId = om.readTree(a1).get("id").asLong();

        mvc.perform(post("/api/assignments").contentType(MediaType.APPLICATION_JSON).content(assignBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(assignmentId))
                .andExpect(jsonPath("$.replayed").value(true));

        mvc.perform(post("/api/assignments").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"r-happy-1","segmentId":%d,"pilotId":%d}""".formatted(segId, pilotId("P002"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        mvc.perform(put("/api/segments/{id}/boarding-point", segId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"boardingPoint\":\"锚地B\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boardingPoint").value("锚地B"))
                .andExpect(jsonPath("$.originalBoardingPoint").value("锚地A"));

        clock.setInstant(Instant.parse("2026-10-04T09:05:00Z"));
        mvc.perform(post("/api/assignments/{id}/boarding", assignmentId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"b-happy-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("BOARDED"))
                .andExpect(jsonPath("$.boardedAtUtc").value("2026-10-04T09:05:00Z"));

        mvc.perform(post("/api/assignments/{id}/boarding", assignmentId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"b-happy-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        mvc.perform(post("/api/assignments/{id}/boarding", assignmentId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"b-happy-2\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        mvc.perform(post("/api/assignments/{id}/revoke", assignmentId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_NOT_REVOCABLE"));

        clock.setInstant(Instant.parse("2026-10-04T10:10:00Z"));
        mvc.perform(post("/api/assignments/{id}/complete", assignmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.departedAtUtc").value("2026-10-04T10:10:00Z"));

        mvc.perform(get("/api/pilots/{id}/timeline", pilot))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].vesselCode").value("MV-HAPPY"))
                .andExpect(jsonPath("$.entries[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.entries[0].boardedAtUtc").value("2026-10-04T09:05:00Z"))
                .andExpect(jsonPath("$.entries[0].departedAtUtc").value("2026-10-04T10:10:00Z"));

        mvc.perform(get("/api/vessels/{code}/timeline", "MV-HAPPY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].segmentStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.entries[0].assignmentStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.entries[0].boardingPoint").value("锚地B"))
                .andExpect(jsonPath("$.entries[0].originalBoardingPoint").value("锚地A"));
    }

    @Test
    void rejectsReversedTimesInsufficientGradeAndUnknownIds() throws Exception {
        mvc.perform(post("/api/segments").contentType(MediaType.APPLICATION_JSON).content("""
                {"vesselCode":"MV-BAD","requiredGrade":1,"estStartUtc":"2026-10-04T10:00:00Z",
                 "estEndUtc":"2026-10-04T09:00:00Z","boardingPoint":"锚地A"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SEGMENT_TIME_REVERSED"));

        long segId = newSegment("MV-GRADE", 3, "2026-10-04T09:00:00Z", "2026-10-04T10:00:00Z", "锚地A");
        mvc.perform(post("/api/assignments").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"r-grade-1","segmentId":%d,"pilotId":%d}""".formatted(segId, pilotId("P003"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("QUALIFICATION_INSUFFICIENT"));

        mvc.perform(post("/api/assignments").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"r-grade-2","segmentId":%d,"pilotId":9999}""".formatted(segId)))
                .andExpect(status().isNotFound());
    }
}