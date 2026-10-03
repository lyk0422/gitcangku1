package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 站点间交接：发起/确认/取消规则、持有权转移、时间线中的确认时刻与拒绝原因。
 */
class TransferApiTest extends ApiTestSupport {

    private long prepareDetachedTagWithPendingTransfer() {
        registerTag("T-01", "ST-ALPHA");
        attach("req-t1", "T-01", "AN-8001", "ST-ALPHA", "2026-02-01T08:00:00Z");
        detach("T-01", "ST-ALPHA", "2026-02-01T10:00:00Z");
        var init = initTransfer("req-t2", "T-01", "ST-ALPHA", "ST-BRAVO", "2026-02-01T10:00:00Z");
        assertThat(init.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return json(init).get("id").asLong();
    }

    @Test
    void fullTransferChainMovesHolderAndAllowsReattach() {
        long transferId = prepareDetachedTagWithPendingTransfer();

        // 未确认前接收站点不能附着，原站点也因待确认交接被阻塞
        var earlyAttach = attach("req-t3", "T-01", "AN-8002", "ST-BRAVO", "2026-02-01T10:05:00Z");
        assertThat(earlyAttach.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var confirmed = confirmTransfer(transferId, "ST-BRAVO", "2026-02-01T10:06:00Z");
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(confirmed).get("status").asText()).isEqualTo("CONFIRMED");

        // 确认后持有权在 ST-BRAVO，原站点附着被拒，接收站点可附着
        var oldHolder = attach("req-t4", "T-01", "AN-8002", "ST-ALPHA", "2026-02-01T10:07:00Z");
        assertThat(oldHolder.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(oldHolder).get("code").asText()).isEqualTo("NOT_HOLDER_STATION");

        var reattached = attach("req-t5", "T-01", "AN-8002", "ST-BRAVO", "2026-02-01T10:07:00Z");
        assertThat(reattached.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 时间线展示站点、交接确认时刻与拒绝原因
        var timeline = json(rest.getForEntity("/api/tags/T-01/timeline", String.class));
        assertThat(timeline.get("holderStationCode").asText()).isEqualTo("ST-BRAVO");
        assertThat(timeline.get("transfers").get(0).get("confirmedAt").asText())
                .isEqualTo("2026-02-01T10:06:00Z");
        assertThat(timeline.get("attachments")).hasSize(2);
        assertThat(timeline.get("rejections")).isNotEmpty();
    }

    @Test
    void transferRequiresDetachedTagAndHolderInitiator() {
        registerTag("T-02", "ST-ALPHA");
        attach("req-u1", "T-02", "AN-8101", "ST-ALPHA", "2026-02-01T08:00:00Z");

        var notDetached = initTransfer("req-u2", "T-02", "ST-ALPHA", "ST-BRAVO", "2026-02-01T09:00:00Z");
        assertThat(notDetached.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(notDetached).get("code").asText()).isEqualTo("TAG_STILL_ATTACHED");

        detach("T-02", "ST-ALPHA", "2026-02-01T10:00:00Z");
        var notHolder = initTransfer("req-u3", "T-02", "ST-BRAVO", "ST-CHARLIE", "2026-02-01T10:00:00Z");
        assertThat(notHolder.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(notHolder).get("code").asText()).isEqualTo("NOT_HOLDER_STATION");

        var unknownTarget = initTransfer("req-u4", "T-02", "ST-ALPHA", "ST-NOPE", "2026-02-01T10:00:00Z");
        assertThat(unknownTarget.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(json(unknownTarget).get("code").asText()).isEqualTo("STATION_NOT_FOUND");
    }

    @Test
    void confirmRejectsWrongStationAndTimeRegression() {
        long transferId = prepareDetachedTagWithPendingTransfer();

        var wrongStation = confirmTransfer(transferId, "ST-CHARLIE", "2026-02-01T10:05:00Z");
        assertThat(wrongStation.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(json(wrongStation).get("code").asText()).isEqualTo("TARGET_STATION_MISMATCH");

        var regression = confirmTransfer(transferId, "ST-BRAVO", "2026-02-01T09:59:00Z");
        assertThat(regression.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(json(regression).get("code").asText()).isEqualTo("TIME_REGRESSION");

        // 失败不留下半个所有权转移：持有站点未变，交接仍待确认
        assertThat(json(rest.getForEntity("/api/tags/T-01", String.class))
                .get("holderStationCode").asText()).isEqualTo("ST-ALPHA");
        var ok = confirmTransfer(transferId, "ST-BRAVO", "2026-02-01T10:05:00Z");
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void cancelRestoresHolderOnlyWhenPending() {
        long transferId = prepareDetachedTagWithPendingTransfer();

        var cancelled = rest.postForEntity("/api/transfers/" + transferId + "/cancel", null, String.class);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(cancelled).get("status").asText()).isEqualTo("CANCELLED");

        // 取消后原持有站点恢复完整持有权，可再次附着
        var reattached = attach("req-v1", "T-01", "AN-8002", "ST-ALPHA", "2026-02-01T10:05:00Z");
        assertThat(reattached.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        detach("T-01", "ST-ALPHA", "2026-02-01T11:00:00Z");

        // 已取消的交接不能再确认
        var confirmAfterCancel = confirmTransfer(transferId, "ST-BRAVO", "2026-02-01T11:05:00Z");
        assertThat(confirmAfterCancel.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void confirmedTransferCannotBeCancelled() {
        long transferId = prepareDetachedTagWithPendingTransfer();
        confirmTransfer(transferId, "ST-BRAVO", "2026-02-01T10:05:00Z");

        var cancel = rest.postForEntity("/api/transfers/" + transferId + "/cancel", null, String.class);
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(cancel).get("code").asText()).isEqualTo("TRANSFER_ALREADY_CONFIRMED");
        assertThat(json(rest.getForEntity("/api/tags/T-01", String.class))
                .get("holderStationCode").asText()).isEqualTo("ST-BRAVO");
    }

    @Test
    void transferInitiateIsIdempotent() {
        prepareDetachedTagWithPendingTransfer();
        var replay = initTransfer("req-t2", "T-01", "ST-ALPHA", "ST-BRAVO", "2026-02-01T10:00:00Z");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(countRows("tag_transfer")).isEqualTo(1);

        var mismatch = initTransfer("req-t2", "T-01", "ST-ALPHA", "ST-CHARLIE", "2026-02-01T10:00:00Z");
        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(mismatch).get("code").asText()).isEqualTo("IDEMPOTENCY_MISMATCH");
        assertThat(countRows("tag_transfer")).isEqualTo(1);
    }

    @Test
    void duplicatePendingTransferRejected() {
        prepareDetachedTagWithPendingTransfer();
        var second = initTransfer("req-w1", "T-01", "ST-ALPHA", "ST-CHARLIE", "2026-02-01T10:01:00Z");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(second).get("code").asText()).isEqualTo("TRANSFER_PENDING");
    }
}