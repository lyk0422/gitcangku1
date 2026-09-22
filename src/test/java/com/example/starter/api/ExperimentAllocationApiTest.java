package com.example.starter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.starter.testsupport.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** 实验创建、顺序分配、盲法视图、退组、关闭与满额主流程及失败分支。 */
class ExperimentAllocationApiTest extends AbstractIntegrationTest {

    private static final String COORDINATOR = "coord-1";

    @Test
    void createExperimentFixesBlocksWithTwoAAndTwoBSeats() throws Exception {
        MvcResult result = postJson("/api/experiments", COORDINATOR, "COORDINATOR",
                Map.of("experimentId", "EXP-1", "blockCount", 3, "requestId", "req-create-1"));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = readBody(result);
        assertThat(body.get("experimentId").asText()).isEqualTo("EXP-1");
        assertThat(body.get("blockCount").asInt()).isEqualTo(3);
        assertThat(body.get("seatCount").asInt()).isEqualTo(12);
        assertThat(body.get("status").asText()).isEqualTo("OPEN");

        List<Map<String, Object>> seats = jdbcTemplate.queryForList(
                "SELECT block_no, seat_no, treatment_code FROM experiment_seat "
                        + "WHERE experiment_id = 'EXP-1' ORDER BY block_no, seat_no");
        assertThat(seats).hasSize(12);
        for (int block = 1; block <= 3; block++) {
            String[] treatments = seats.subList((block - 1) * 4, block * 4).stream()
                    .map(row -> (String) row.get("treatment_code"))
                    .toArray(String[]::new);
            assertThat(treatments).containsExactly("A", "A", "B", "B");
        }
    }

    @Test
    void blockCountOutsideRangeIsRejected() throws Exception {
        MvcResult result = postJson("/api/experiments", COORDINATOR, "COORDINATOR",
                Map.of("experimentId", "EXP-BAD", "blockCount", 9, "requestId", "req-bad-1"));
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void enrollAssignsSeatsInBlockSeatOrderAndReturnsOnlyBlindView() throws Exception {
        createExperiment("EXP-SEQ", 2, "req-seq-create");
        for (int i = 1; i <= 8; i++) {
            String participant = "P-" + i;
            MvcResult result = postJson("/api/experiments/EXP-SEQ/enroll", COORDINATOR, "COORDINATOR",
                    Map.of("participantId", participant, "requestId", "req-enroll-" + i));
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            JsonNode body = readBody(result);
            int expectedBlock = (i - 1) / 4 + 1;
            assertThat(body.get("blockNo").asInt()).isEqualTo(expectedBlock);
            assertThat(body.get("participantId").asText()).isEqualTo(participant);
            assertThat(body.get("status").asText()).isEqualTo("ENROLLED");
            assertThat(body.get("blindCode").asText()).matches("[0-9a-f]{24}");
            // 普通登记响应不得包含处理代码或席位序号
            assertThat(body.has("seatNo")).isFalse();
            assertThat(body.has("treatmentCode")).isFalse();
        }
    }

    @Test
    void sameParticipantCanOnlyHoldOneSeat() throws Exception {
        createExperiment("EXP-DUP", 2, "req-dup-create");
        assertThat(postJson("/api/experiments/EXP-DUP/enroll", COORDINATOR, "COORDINATOR",
                Map.of("participantId", "P-1", "requestId", "req-dup-enroll-1"))
                .getResponse().getStatus()).isEqualTo(201);

        MvcResult duplicate = postJson("/api/experiments/EXP-DUP/enroll", COORDINATOR, "COORDINATOR",
                Map.of("participantId", "P-1", "requestId", "req-dup-enroll-2"));
        assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);

        Integer seatsTaken = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'EXP-DUP'", Integer.class);
        assertThat(seatsTaken).isEqualTo(1);
    }

    @Test
    void fullExperimentRejectsFurtherEnrollmentWith422() throws Exception {
        createExperiment("EXP-FULL", 2, "req-full-create");
        for (int i = 1; i <= 8; i++) {
            postJson("/api/experiments/EXP-FULL/enroll", COORDINATOR, "COORDINATOR",
                    Map.of("participantId", "P-" + i, "requestId", "req-full-enroll-" + i));
        }
        MvcResult overflow = postJson("/api/experiments/EXP-FULL/enroll", COORDINATOR, "COORDINATOR",
                Map.of("participantId", "P-9", "requestId", "req-full-enroll-9"));
        assertThat(overflow.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void withdrawKeepsSeatAndDoesNotRearrangeOtherAllocations() throws Exception {
        createExperiment("EXP-WD", 2, "req-wd-create");
        postJson("/api/experiments/EXP-WD/enroll", COORDINATOR, "COORDINATOR",
                Map.of("participantId", "P-1", "requestId", "req-wd-enroll-1"));
        postJson("/api/experiments/EXP-WD/enroll", COORDINATOR, "COORDINATOR",
                Map.of("participantId", "P-2", "requestId", "req-wd-enroll-2"));

        MvcResult withdraw = postJson("/api/experiments/EXP-WD/withdraw", COORDINATOR, "COORDINATOR",
                Map.of("participantId", "P-1", "requestId", "req-wd-withdraw-1"));
        assertThat(withdraw.getResponse().getStatus()).isEqualTo(200);
        assertThat(readBody(withdraw).get("status").asText()).isEqualTo("WITHDRAWN");

        // 退组后普通查询可见且状态为 WITHDRAWN
        MvcResult query = getJson("/api/experiments/EXP-WD/allocations/P-1", COORDINATOR, "COORDINATOR");
        assertThat(query.getResponse().getStatus()).isEqualTo(200);
        JsonNode view = readBody(query);
        assertThat(view.get("status").asText()).isEqualTo("WITHDRAWN");
        assertThat(view.has("seatNo")).isFalse();
        assertThat(view.has("treatmentCode")).isFalse();

        // P-2 的区组不被重排，仍在 1 区组
        assertThat(readBody(getJson("/api/experiments/EXP-WD/allocations/P-2", COORDINATOR, "COORDINATOR"))
                .get("blockNo").asInt()).isEqualTo(1);

        // 第三个登记者跳过已被退组保留的 1-1 与 P-2 占用的 1-2，领取 1-3，说明退组席位未释放
        postJson("/api/experiments/EXP-WD/enroll", COORDINATOR, "COORDINATOR",
                Map.of("participantId", "P-3", "requestId", "req-wd-enroll-3"));
        Map<String, Object> third = jdbcTemplate.queryForMap(
                "SELECT block_no, seat_no, status FROM allocation "
                        + "WHERE experiment_id = 'EXP-WD' AND participant_id = 'P-3'");
        assertThat(third.get("block_no")).isEqualTo(1);
        assertThat(third.get("seat_no")).isEqualTo(3);

        // 席位 1-1 仍由已退组的 P-1 占用
        Integer retained = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'EXP-WD' "
                        + "AND block_no = 1 AND seat_no = 1 AND status = 'WITHDRAWN'", Integer.class);
        assertThat(retained).isEqualTo(1);
    }

    @Test
    void doubleWithdrawIsConflict() throws Exception {
        createExperiment("EXP-WD2", 2, "req-wd2-create");
        postJson("/api/experiments/EXP-WD2/enroll", COORDINATOR, "COORDINATOR",
                Map.of("participantId", "P-1", "requestId", "req-wd2-enroll-1"));
        assertThat(postJson("/api/experiments/EXP-WD2/withdraw", COORDINATOR, "COORDINATOR",
                Map.of("participantId", "P-1", "requestId", "req-wd2-withdraw-1"))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(postJson("/api/experiments/EXP-WD2/withdraw", COORDINATOR, "COORDINATOR",
                Map.of("participantId", "P-1", "requestId", "req-wd2-withdraw-2"))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void closedExperimentRejectsNewEnrollmentButKeepsHistory() throws Exception {
        createExperiment("EXP-CLOSE", 2, "req-close-create");
        postJson("/api/experiments/EXP-CLOSE/enroll", COORDINATOR, "COORDINATOR",
                Map.of("participantId", "P-1", "requestId", "req-close-enroll-1"));

        MvcResult close = postJson("/api/experiments/EXP-CLOSE/close", COORDINATOR, "COORDINATOR",
                Map.of("requestId", "req-close-1"));
        assertThat(close.getResponse().getStatus()).isEqualTo(200);
        assertThat(readBody(close).get("status").asText()).isEqualTo("CLOSED");

        MvcResult enrollAfterClose = postJson("/api/experiments/EXP-CLOSE/enroll", COORDINATOR, "COORDINATOR",
                Map.of("participantId", "P-2", "requestId", "req-close-enroll-2"));
        assertThat(enrollAfterClose.getResponse().getStatus()).isEqualTo(409);

        MvcResult history = getJson("/api/experiments/EXP-CLOSE/allocations/P-1", "rev-1", "REVIEWER");
        assertThat(history.getResponse().getStatus()).isEqualTo(200);
        assertThat(readBody(history).get("participantId").asText()).isEqualTo("P-1");
    }

    @Test
    void closingTwiceIsConflict() throws Exception {
        createExperiment("EXP-CLOSE2", 2, "req-close2-create");
        assertThat(postJson("/api/experiments/EXP-CLOSE2/close", COORDINATOR, "COORDINATOR",
                Map.of("requestId", "req-close2-1")).getResponse().getStatus()).isEqualTo(200);
        assertThat(postJson("/api/experiments/EXP-CLOSE2/close", COORDINATOR, "COORDINATOR",
                Map.of("requestId", "req-close2-2")).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void operationsOnMissingExperimentReturn404() throws Exception {
        assertThat(getJson("/api/experiments/NOPE/allocations/P-1", COORDINATOR, "COORDINATOR")
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(postJson("/api/experiments/NOPE/enroll", COORDINATOR, "COORDINATOR",
                Map.of("participantId", "P-1", "requestId", "req-missing-enroll"))
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void createDuplicateExperimentIdIsConflict() throws Exception {
        createExperiment("EXP-DUPID", 2, "req-dupid-1");
        MvcResult second = postJson("/api/experiments", COORDINATOR, "COORDINATOR",
                Map.of("experimentId", "EXP-DUPID", "blockCount", 3, "requestId", "req-dupid-2"));
        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        Integer blockCount = jdbcTemplate.queryForObject(
                "SELECT block_count FROM experiment WHERE id = 'EXP-DUPID'", Integer.class);
        assertThat(blockCount).isEqualTo(2);
    }

    @Test
    void missingRequestBodyFieldIsBadRequest() throws Exception {
        MvcResult result = postJson("/api/experiments", COORDINATOR, "COORDINATOR",
                new LinkedHashMap<>(Map.of("experimentId", "EXP-NOBLOCK", "requestId", "req-noblock")));
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    private void createExperiment(String experimentId, int blockCount, String requestId) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/experiments")
                        .header("X-Actor-Id", COORDINATOR)
                        .header("X-Role", "COORDINATOR")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "experimentId", experimentId,
                                "blockCount", blockCount,
                                "requestId", requestId))))
                .andExpect(status().isCreated());
    }
}
