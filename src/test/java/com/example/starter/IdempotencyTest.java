package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.starter.error.ConflictException;
import com.example.starter.service.IdempotencyService;
import com.example.starter.service.IdempotentResponse;
import com.example.starter.web.dto.ApiResponses.HandoverResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 幂等边界：同参重放、异参 409、失败不占键。 */
class IdempotencyTest extends BaseIntegrationTest {

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void sameRequestIdSameParamsReplaysFinalReading() {
        String batch = givenBatch();
        givenCell("C-I1");
        dischargeService.intake(requestId(), batch, "C-I1", 3.7);
        String req = requestId();
        String hash = hashOf(batch, "C-I1", "0.4");

        IdempotentResponse first = idempotencyService.execute(req, "finalReading", hash,
                () -> dischargeService.finalReading(req, batch, "C-I1", 0.4,
                        T0.plusSeconds(3600)));
        IdempotentResponse replay = idempotencyService.execute(req, "finalReading", hash,
                () -> {
                    throw new IllegalStateException("重放不应再执行业务");
                });

        assertEquals(200, replay.status());
        assertEquals(first.body(), replay.body());
        assertEquals(2, count("discharge_reading"));
        assertEquals(1, count("idempotency_key"));
    }

    @Test
    void sameRequestIdDifferentParamsConflicts() {
        String batch = givenBatch();
        givenCell("C-J1");
        dischargeService.intake(requestId(), batch, "C-J1", 3.7);
        String req = requestId();
        idempotencyService.execute(req, "finalReading", hashOf(batch, "C-J1", "0.4"),
                () -> dischargeService.finalReading(req, batch, "C-J1", 0.4,
                        T0.plusSeconds(3600)));

        assertThrows(ConflictException.class,
                () -> idempotencyService.execute(req, "finalReading",
                        hashOf(batch, "C-J1", "0.3"),
                        () -> dischargeService.finalReading(req, batch, "C-J1", 0.3,
                                T0.plusSeconds(3600))));
        assertEquals(2, count("discharge_reading"));
    }

    @Test
    void handoverReplaysAndConflicts() throws Exception {
        String batch = givenBatch();
        givenCell("C-K1");
        dischargeService.intake(requestId(), batch, "C-K1", 3.7);
        dischargeService.finalReading(requestId(), batch, "C-K1", 0.4, T0.plusSeconds(3600));
        String req = requestId();
        String hash = hashOf(batch, "A", "B");

        IdempotentResponse first = idempotencyService.execute(req, "handover", hash,
                () -> dischargeService.handover(req, batch, "A", "B"));
        IdempotentResponse replay = idempotencyService.execute(req, "handover", hash,
                () -> {
                    throw new IllegalStateException("重放不应再执行业务");
                });

        HandoverResponse firstBody = objectMapper.readValue(first.body(), HandoverResponse.class);
        HandoverResponse replayBody = objectMapper.readValue(replay.body(), HandoverResponse.class);
        assertEquals(firstBody.handoverCode(), replayBody.handoverCode());
        assertEquals(1, count("handover"));

        assertThrows(ConflictException.class,
                () -> idempotencyService.execute(req, "handover", hashOf(batch, "A", "C"),
                        () -> dischargeService.handover(req, batch, "A", "C")));
    }

    @Test
    void failedRequestDoesNotConsumeRequestId() {
        String batchA = givenBatch();
        String batchB = givenBatch();
        givenCell("C-L1");
        dischargeService.intake(requestId(), batchA, "C-L1", 3.7);
        String req = requestId();
        String hash = hashOf(batchB, "C-L1", "3.7");

        assertThrows(ConflictException.class,
                () -> idempotencyService.execute(req, "intake", hash,
                        () -> dischargeService.intake(req, batchB, "C-L1", 3.7)));
        assertEquals(0, count("idempotency_key"));

        // 电芯从批次 A 隔离后，同一 requestId 可重新入批成功
        dischargeService.isolate(batchA, "C-L1", "异常隔离后释放");
        IdempotentResponse response = idempotencyService.execute(req, "intake", hash,
                () -> dischargeService.intake(req, batchB, "C-L1", 3.7));
        assertEquals(200, response.status());
        assertEquals(batchB, jdbc.queryForObject(
                "SELECT batch_code FROM battery_cell WHERE cell_code = ?", String.class, "C-L1"));
        assertEquals(1, count("idempotency_key"));
    }

    private static String hashOf(String... parts) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(
                    String.join("|", parts).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}