package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.support.TestClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 标签登记、附着、摘除主流程与边界：左闭右开区间、拒绝原因、幂等重放、失败不消费请求键。
 */
class TagLifecycleApiTest extends ApiTestSupport {

    @Test
    void registerAttachDetachHappyChain() {
        var registered = registerTag("L-01", "ST-ALPHA");
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(registered).get("status").asText()).isEqualTo("DETACHED");
        assertThat(json(registered).get("registeredAt").asText()).isEqualTo("2026-01-01T00:00:00Z");

        var attached = attach("req-a1", "L-01", "AN-1001", "ST-ALPHA", "2026-02-01T08:00:00Z");
        assertThat(attached.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(attached).get("endAt").isNull()).isTrue();

        var detached = detach("L-01", "ST-ALPHA", "2026-02-01T10:00:00Z");
        assertThat(detached.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(detached).get("endAt").asText()).isEqualTo("2026-02-01T10:00:00Z");

        // 摘除冻结上一段关联，历史行保留
        var timeline = rest.getForEntity("/api/tags/L-01/timeline", String.class);
        var attachments = json(timeline).get("attachments");
        assertThat(attachments).hasSize(1);
        assertThat(attachments.get(0).get("startAt").asText()).isEqualTo("2026-02-01T08:00:00Z");
        assertThat(attachments.get(0).get("endAt").asText()).isEqualTo("2026-02-01T10:00:00Z");
        assertThat(countRows("attachment")).isEqualTo(1);
    }

    @Test
    void boundaryIntervalsAreLeftClosedRightOpen() {
        registerTag("L-02", "ST-ALPHA");
        attach("req-b1", "L-02", "AN-2001", "ST-ALPHA", "2026-02-01T08:00:00Z");
        detach("L-02", "ST-ALPHA", "2026-02-01T10:00:00Z");

        // 题干示例：10:00 摘除后，10:00 起新动物可附着
        var atBoundary = attach("req-b2", "L-02", "AN-2002", "ST-ALPHA", "2026-02-01T10:00:00Z");
        assertThat(atBoundary.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        detach("L-02", "ST-ALPHA", "2026-02-01T11:00:00Z");

        // 09:59 与上一段 [08:00,10:00) 交叉，被拒绝
        var crossing = attach("req-b3", "L-02", "AN-2003", "ST-ALPHA", "2026-02-01T09:59:00Z");
        assertThat(crossing.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(json(crossing).get("code").asText()).isEqualTo("TAG_INTERVAL_OVERLAP");

        // 同一动物在有效时刻也不能再附着第二只标签
        registerTag("L-03", "ST-ALPHA");
        attach("req-b4", "L-03", "AN-2004", "ST-ALPHA", "2026-02-01T08:30:00Z");
        var animalCrossing = attach("req-b5", "L-02", "AN-2004", "ST-ALPHA", "2026-02-01T12:00:00Z");
        assertThat(animalCrossing.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(json(animalCrossing).get("code").asText()).isEqualTo("ANIMAL_INTERVAL_OVERLAP");
    }

    @Test
    void attachRejectsWrongStateAndStation() {
        registerTag("L-04", "ST-ALPHA");
        attach("req-c1", "L-04", "AN-3001", "ST-ALPHA", "2026-02-01T08:00:00Z");

        var stillAttached = attach("req-c2", "L-04", "AN-3002", "ST-ALPHA", "2026-02-01T09:00:00Z");
        assertThat(stillAttached.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(stillAttached).get("code").asText()).isEqualTo("TAG_STILL_ATTACHED");

        detach("L-04", "ST-ALPHA", "2026-02-01T10:00:00Z");
        var wrongStation = attach("req-c3", "L-04", "AN-3002", "ST-BRAVO", "2026-02-01T10:00:00Z");
        assertThat(wrongStation.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(wrongStation).get("code").asText()).isEqualTo("NOT_HOLDER_STATION");

        var detachAgain = detach("L-04", "ST-ALPHA", "2026-02-01T11:00:00Z");
        assertThat(detachAgain.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(detachAgain).get("code").asText()).isEqualTo("TAG_NOT_ATTACHED");
    }

    @Test
    void detachRejectsTimeRegression() {
        registerTag("L-05", "ST-ALPHA");
        attach("req-d1", "L-05", "AN-4001", "ST-ALPHA", "2026-02-01T08:00:00Z");
        var regression = detach("L-05", "ST-ALPHA", "2026-02-01T07:59:00Z");
        assertThat(regression.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(json(regression).get("code").asText()).isEqualTo("TIME_REGRESSION");
        // 失败不留下半个状态：标签仍附着
        assertThat(json(rest.getForEntity("/api/tags/L-05", String.class)).get("status").asText())
                .isEqualTo("ATTACHED");
    }

    @Test
    void idempotentReplayAndMismatch() {
        registerTag("L-06", "ST-ALPHA");
        var first = attach("req-e1", "L-06", "AN-5001", "ST-ALPHA", "2026-02-01T08:00:00Z");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 同参重放：返回首次结果，不产生新记录
        var replay = attach("req-e1", "L-06", "AN-5001", "ST-ALPHA", "2026-02-01T08:00:00Z");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(replay)).isEqualTo(json(first));
        assertThat(countRows("attachment")).isEqualTo(1);

        // 异参重放：409
        var mismatch = attach("req-e1", "L-06", "AN-9999", "ST-ALPHA", "2026-02-01T08:00:00Z");
        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(mismatch).get("code").asText()).isEqualTo("IDEMPOTENCY_MISMATCH");
        assertThat(countRows("attachment")).isEqualTo(1);
    }

    @Test
    void failedTransactionDoesNotConsumeRequestKey() {
        registerTag("L-07", "ST-ALPHA");
        attach("req-f1", "L-07", "AN-6001", "ST-ALPHA", "2026-02-01T08:00:00Z");

        // 该请求键先用于一次失败的附着（标签未摘除）
        var failed = attach("req-f2", "L-07", "AN-6002", "ST-ALPHA", "2026-02-01T09:00:00Z");
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(countRows("idempotency_record")).isEqualTo(1);

        // 摘除后同一请求键可重新使用并成功
        detach("L-07", "ST-ALPHA", "2026-02-01T10:00:00Z");
        var retried = attach("req-f2", "L-07", "AN-6002", "ST-ALPHA", "2026-02-01T10:00:00Z");
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(countRows("attachment")).isEqualTo(2);
    }

    @Test
    void animalTimelineShowsAttachmentsAndRejections() {
        registerTag("L-08", "ST-ALPHA");
        registerTag("L-09", "ST-ALPHA");
        attach("req-g1", "L-08", "AN-7001", "ST-ALPHA", "2026-02-01T08:00:00Z");
        attach("req-g2", "L-09", "AN-7001", "ST-ALPHA", "2026-02-01T09:00:00Z");

        var timeline = rest.getForEntity("/api/animals/AN-7001/timeline", String.class);
        assertThat(timeline.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = json(timeline);
        assertThat(body.get("attachments")).hasSize(1);
        assertThat(body.get("rejections")).hasSize(1);
        assertThat(body.get("rejections").get(0).get("reason").asText())
                .contains("ANIMAL_INTERVAL_OVERLAP");
        assertThat(body.get("rejections").get(0).get("occurredAt").asText())
                .isEqualTo(TestClockConfig.T0.toString());
    }
}