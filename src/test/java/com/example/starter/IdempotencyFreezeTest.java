package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** 幂等重放、冻结规则与冻结后勘误。 */
class IdempotencyFreezeTest extends BaseWebTest {

    @Test
    void createTicket_sameParamsReplayed_differentParams409() throws Exception {
        Map<String, String> req = Map.of("requestId", "idem1-create", "transportOrderNo", "TO-SYN-0001",
                "vehicleNo", "SYN-TRUCK-01");
        MvcResult first = postJson("/api/weigh-tickets", req);
        assertEquals(201, first.getResponse().getStatus());
        MvcResult replay = postJson("/api/weigh-tickets", req);
        assertEquals(200, replay.getResponse().getStatus(), "同参重放返回原单");
        assertEquals(body(first).get("id").asLong(), body(replay).get("id").asLong());

        MvcResult conflict = postJson("/api/weigh-tickets",
                Map.of("requestId", "idem1-create", "transportOrderNo", "TO-SYN-0002", "vehicleNo", "SYN-TRUCK-02"));
        assertEquals(409, conflict.getResponse().getStatus(), "异参重放409");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM weigh_ticket WHERE create_request_id = 'idem1-create'", Long.class).intValue());
    }

    @Test
    void freeze_sameParamsReplayed_differentParams409() throws Exception {
        long ticketId = grossedTicket("idem2");
        MvcResult first = postJson("/api/weigh-tickets/" + ticketId + "/freeze",
                Map.of("requestId", "idem2-freeze", "expectedReweighIds", List.of()));
        assertEquals(201, first.getResponse().getStatus());
        assertEquals(4000, body(first).get("netKg").asInt());

        MvcResult replay = postJson("/api/weigh-tickets/" + ticketId + "/freeze",
                Map.of("requestId", "idem2-freeze", "expectedReweighIds", List.of()));
        assertEquals(200, replay.getResponse().getStatus());
        assertEquals(4000, body(replay).get("netKg").asInt());

        MvcResult conflict = postJson("/api/weigh-tickets/" + ticketId + "/freeze",
                Map.of("requestId", "idem2-freeze", "expectedReweighIds", List.of(999L)));
        assertEquals(409, conflict.getResponse().getStatus());
        MvcResult other = postJson("/api/weigh-tickets/" + ticketId + "/freeze",
                Map.of("requestId", "idem2-freeze-2", "expectedReweighIds", List.of()));
        assertEquals(409, other.getResponse().getStatus(), "重复冻结409");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_freeze WHERE ticket_id = " + ticketId, Long.class).intValue());
    }

    @Test
    void freezeWithOpenDispute_rejected409() throws Exception {
        long ticketId = grossedTicket("idem3");
        clock.set(GROSS_TIME.plus(Duration.ofMinutes(5)));
        postJson("/api/weigh-tickets/" + ticketId + "/reweighs",
                Map.of("requestId", "idem3-rw", "grossKg", 12100, "reason", "异议"));
        MvcResult r = postJson("/api/weigh-tickets/" + ticketId + "/freeze",
                Map.of("requestId", "idem3-freeze", "expectedReweighIds", List.of()));
        assertEquals(409, r.getResponse().getStatus(), "未裁决争议不能结算");
        assertEquals("UNRESOLVED_DISPUTE", body(r).get("code").asText());
    }

    @Test
    void freezeWithStaleVersionSet_rejected409() throws Exception {
        long ticketId = grossedTicket("idem4");
        clock.set(GROSS_TIME.plus(Duration.ofMinutes(5)));
        MvcResult rw = postJson("/api/weigh-tickets/" + ticketId + "/reweighs",
                Map.of("requestId", "idem4-rw", "grossKg", 12100, "reason", "异议"));
        long reweighId = body(rw).get("id").asLong();
        long disputeId = jdbc.queryForObject("SELECT id FROM dispute WHERE ticket_id = " + ticketId, Long.class);
        postJson("/api/disputes/" + disputeId + "/adjudicate",
                Map.of("requestId", "idem4-adj", "adoptedSource", "REWEIGH", "reweighId", reweighId,
                        "reason", "采纳复称", "adjudicator", "裁决员甲"));
        MvcResult r = postJson("/api/weigh-tickets/" + ticketId + "/freeze",
                Map.of("requestId", "idem4-freeze", "expectedReweighIds", List.of()));
        assertEquals(409, r.getResponse().getStatus(), "冻结必须引用同一已提交版本集合");
        assertEquals("VERSION_SET_MISMATCH", body(r).get("code").asText());
    }

    @Test
    void frozenTicket_newWeighingOnlyErrata_snapshotUntouched() throws Exception {
        long ticketId = grossedTicket("idem5");
        postJson("/api/weigh-tickets/" + ticketId + "/freeze",
                Map.of("requestId", "idem5-freeze", "expectedReweighIds", List.of()));

        MvcResult gross = postJson("/api/weigh-tickets/" + ticketId + "/gross",
                Map.of("requestId", "idem5-gross2", "grossKg", 12500, "grossTimeUtc", GROSS_TIME.toString()));
        assertEquals(409, gross.getResponse().getStatus(), "冻结后不能补记毛重");
        clock.set(GROSS_TIME.plus(Duration.ofMinutes(6)));
        MvcResult rw = postJson("/api/weigh-tickets/" + ticketId + "/reweighs",
                Map.of("requestId", "idem5-rw", "grossKg", 12100, "reason", "冻结后复称"));
        assertEquals(409, rw.getResponse().getStatus(), "冻结后不能复称");

        MvcResult errata = postJson("/api/weigh-tickets/" + ticketId + "/errata",
                Map.of("requestId", "idem5-errata", "newGrossKg", 12500, "reason", "衡器校准后补测"));
        assertEquals(201, errata.getResponse().getStatus(), "冻结后的新称量形成勘误申请");
        MvcResult errataReplay = postJson("/api/weigh-tickets/" + ticketId + "/errata",
                Map.of("requestId", "idem5-errata", "newGrossKg", 12500, "reason", "衡器校准后补测"));
        assertEquals(200, errataReplay.getResponse().getStatus());

        JsonNode detail = body(getJson("/api/weigh-tickets/" + ticketId));
        assertEquals(4000, detail.get("freeze").get("netKg").asInt(), "勘误不覆盖结算快照");
        assertEquals(1, detail.get("errata").size());
        assertEquals(12500, detail.get("errata").get(0).get("newGrossKg").asInt());
    }
}