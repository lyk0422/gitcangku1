package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可控时钟测试：固定 UTC 时钟下登记与解除锚点，断言时间字段不随 JVM 时区变化、微秒精度稳定。
 */
@Import(ControlledClockCitationTest.FixedClockConfig.class)
class ControlledClockCitationTest extends AbstractIntegrationTest {

    private static final Instant FIXED = Instant.parse("2026-09-27T10:00:00.123456Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneOffset.UTC);
        }
    }

    @Test
    @DisplayName("固定时钟：锚点登记与解除时间输出 UTC 微秒时间戳，不随本地时区变化")
    void timestampsUseControlledUtcClock() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello world", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());

        ApiResult registered = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationId\":\"isbn-1\",\"startOffset\":0,\"endOffset\":5,\"lockReason\":\"r\"}]",
                newRequestId());
        assertThat(registered.status()).isEqualTo(201);
        long anchorId = registered.body().get("anchors").get(0).get("anchorId").asLong();
        assertThat(registered.body().get("anchors").get(0).get("createdAt").asText())
                .isEqualTo("2026-09-27T10:00:00.123456Z");

        ApiResult released = releaseAnchor(docId, anchorId, "erin", "LEGAL", "撤销", newRequestId());
        assertThat(released.status()).isEqualTo(200);
        assertThat(released.body().get("releasedAt").asText()).isEqualTo("2026-09-27T10:00:00.123456Z");

        ApiResult detail = getJson("/api/documents/" + docId + "/anchors/" + anchorId);
        assertThat(detail.body().get("history").get(0).get("eventAt").asText())
                .isEqualTo("2026-09-27T10:00:00.123456Z");
        assertThat(detail.body().get("history").get(1).get("eventAt").asText())
                .isEqualTo("2026-09-27T10:00:00.123456Z");
    }
}
