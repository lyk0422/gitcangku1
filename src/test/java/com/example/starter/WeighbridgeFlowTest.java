package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** 主流程与 422 拒绝分支：合成皮重8000、毛重12000，复称12100被采纳后冻结净重4100。 */
class WeighbridgeFlowTest extends BaseWebTest {

    @Test
    void mainFlow_reweighAdopted_netFrozen4100() throws Exception {
        long ticketId = grossedTicket("flow1");

        JsonNode detail = body(getJson("/api/weigh-tickets/" + ticketId));
        assertEquals(4000, detail.get("previewNetKg").asInt(), "12000-8000 试算净重4000");

        clock.set(GROSS_TIME.plus(Duration.ofMinutes(5)));
        MvcResult rw = postJson("/api/weigh-tickets/" + ticketId + "/reweighs",
                Map.of("requestId", "flow1-rw1", "grossKg", 12100, "reason", "司机对净重有异议"));
        assertEquals(201, rw.getResponse().getStatus());
        long reweighId = body(rw).get("id").asLong();

        detail = body(getJson("/api/weigh-tickets/" + ticketId));
        assertEquals("OPEN", detail.get("dispute").get("status").asText());
        long disputeId = detail.get("dispute").get("id").asLong();

        MvcResult adj = postJson("/api/disputes/" + disputeId + "/adjudicate",
                Map.of("requestId", "flow1-adj", "adoptedSource", "REWEIGH", "reweighId", reweighId,
                        "reason", "复称设备校验合格，采纳复称", "adjudicator", "裁决员甲"));
        assertEquals(201, adj.getResponse().getStatus());
        assertEquals("RESOLVED", body(adj).get("status").asText());

        MvcResult freeze = postJson("/api/weigh-tickets/" + ticketId + "/freeze",
                Map.of("requestId", "flow1-freeze", "expectedReweighIds", List.of(reweighId)));
        assertEquals(201, freeze.getResponse().getStatus());
        JsonNode f = body(freeze);
        assertEquals(4100, f.get("netKg").asInt(), "12100-8000 冻结净重4100");
        assertEquals("REWEIGH", f.get("adoptedSource").asText());

        detail = body(getJson("/api/weigh-tickets/" + ticketId));
        assertEquals("FROZEN", detail.get("ticket").get("status").asText());
        assertEquals("ADOPTED", detail.get("reweighs").get(0).get("status").asText());
        assertEquals(4100, detail.get("freeze").get("netKg").asInt());
        assertTrue(body(getJson("/api/weigh-tickets?vehicleNo=SYN-TRUCK-01")).size() >= 1);
    }

    @Test
    void rejectedReweigh_originalGrossStillAdoptable_net4000() throws Exception {
        long ticketId = grossedTicket("flow2");
        clock.set(GROSS_TIME.plus(Duration.ofMinutes(10)));
        MvcResult rw = postJson("/api/weigh-tickets/" + ticketId + "/reweighs",
                Map.of("requestId", "flow2-rw1", "grossKg", 12100, "reason", "司机异议"));
        long reweighId = body(rw).get("id").asLong();
        long disputeId = jdbc.queryForObject(
                "SELECT id FROM dispute WHERE ticket_id = " + ticketId, Long.class);

        MvcResult adj = postJson("/api/disputes/" + disputeId + "/adjudicate",
                Map.of("requestId", "flow2-adj", "adoptedSource", "ORIGINAL",
                        "reason", "复称设备未校验，拒绝复称，采纳原毛重", "adjudicator", "裁决员乙"));
        assertEquals(201, adj.getResponse().getStatus());

        MvcResult freeze = postJson("/api/weigh-tickets/" + ticketId + "/freeze",
                Map.of("requestId", "flow2-freeze", "expectedReweighIds", List.of(reweighId)));
        assertEquals(201, freeze.getResponse().getStatus());
        JsonNode f = body(freeze);
        assertEquals(4000, f.get("netKg").asInt(), "复称被拒绝，原毛重12000仍被采纳");
        assertEquals("ORIGINAL", f.get("adoptedSource").asText());
        JsonNode detail = body(getJson("/api/weigh-tickets/" + ticketId));
        assertEquals("REJECTED", detail.get("reweighs").get(0).get("status").asText());
        assertEquals("复称设备未校验，拒绝复称，采纳原毛重",
                detail.get("dispute").get("adjudicationReason").asText(), "裁决理由留痕");
    }

    @Test
    void grossWithoutTare_rejected422() throws Exception {
        long id = createTicket("rej1-create");
        MvcResult r = postJson("/api/weigh-tickets/" + id + "/gross",
                Map.of("requestId", "rej1-gross", "grossKg", 12000, "grossTimeUtc", GROSS_TIME.toString()));
        assertEquals(422, r.getResponse().getStatus());
        assertEquals("NO_ENTRY_WEIGHING", body(r).get("code").asText());
    }

    @Test
    void grossTimeRegression_rejected422() throws Exception {
        long id = createTicket("rej2-create");
        postJson("/api/weigh-tickets/" + id + "/tare",
                Map.of("requestId", "rej2-tare", "tareKg", 8000, "tareTimeUtc", TARE_TIME.toString()));
        MvcResult r = postJson("/api/weigh-tickets/" + id + "/gross",
                Map.of("requestId", "rej2-gross", "grossKg", 12000,
                        "grossTimeUtc", TARE_TIME.minus(Duration.ofMinutes(1)).toString()));
        assertEquals(422, r.getResponse().getStatus());
        assertEquals("TIME_REGRESSION", body(r).get("code").asText());
    }

    @Test
    void negativeNetWeight_rejected422() throws Exception {
        long id = createTicket("rej3-create");
        postJson("/api/weigh-tickets/" + id + "/tare",
                Map.of("requestId", "rej3-tare", "tareKg", 8000, "tareTimeUtc", TARE_TIME.toString()));
        MvcResult r = postJson("/api/weigh-tickets/" + id + "/gross",
                Map.of("requestId", "rej3-gross", "grossKg", 7999, "grossTimeUtc", GROSS_TIME.toString()));
        assertEquals(422, r.getResponse().getStatus());
        assertEquals("NEGATIVE_NET_WEIGHT", body(r).get("code").asText());
    }

    @Test
    void reweighAfter30Minutes_rejected422() throws Exception {
        long ticketId = grossedTicket("rej4");
        clock.set(GROSS_TIME.plus(Duration.ofMinutes(31)));
        MvcResult r = postJson("/api/weigh-tickets/" + ticketId + "/reweighs",
                Map.of("requestId", "rej4-rw", "grossKg", 12100, "reason", "逾期争议"));
        assertEquals(422, r.getResponse().getStatus());
        assertEquals("REWEIGH_WINDOW_EXPIRED", body(r).get("code").asText());
    }

    @Test
    void unknownTransportOrder_rejected422() throws Exception {
        MvcResult r = postJson("/api/weigh-tickets",
                Map.of("requestId", "rej5-create", "transportOrderNo", "TO-NONE", "vehicleNo", "SYN-TRUCK-01"));
        assertEquals(422, r.getResponse().getStatus());
        assertEquals("UNKNOWN_TRANSPORT_ORDER", body(r).get("code").asText());
    }
}