package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.starter.service.AttachmentService;
import com.example.starter.service.BusinessException;
import com.example.starter.service.TagService;
import com.example.starter.service.TransferService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 幂等：附着与交接按 requestId 同参重放、异参 409；失败事务不消费请求键。
 */
class IdempotencyTest extends AbstractDatabaseTest {

    @Autowired
    private TagService tagService;
    @Autowired
    private AttachmentService attachmentService;
    @Autowired
    private TransferService transferService;

    @Test
    void attachSameRequestIdSameParamsReplays() {
        tagService.register("T1", "ST-A");
        var first = attachmentService.attach("req-1", "T1", "AN-1", "ST-A",
                Instant.parse("2026-10-04T09:00:00Z"));
        var replay = attachmentService.attach("req-1", "T1", "AN-1", "ST-A",
                Instant.parse("2026-10-04T09:00:00Z"));
        assertEquals(first.id(), replay.id());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attachment WHERE tag_code = 'T1'", Integer.class);
        assertEquals(1, count);
    }

    @Test
    void attachSameRequestIdDifferentParamsRejected409() {
        tagService.register("T1", "ST-A");
        attachmentService.attach("req-1", "T1", "AN-1", "ST-A",
                Instant.parse("2026-10-04T09:00:00Z"));
        var e = assertThrows(BusinessException.class, () -> attachmentService.attach(
                "req-1", "T1", "AN-2", "ST-A", Instant.parse("2026-10-04T09:00:00Z")));
        assertEquals(409, e.status());
        assertEquals("REQUEST_ID_CONFLICT", e.code());
    }

    @Test
    void failedAttachDoesNotConsumeRequestKey() {
        tagService.register("T1", "ST-A");
        attachmentService.attach("req-1", "T1", "AN-1", "ST-A",
                Instant.parse("2026-10-04T09:00:00Z"));
        attachmentService.detach("T1", "ST-A", Instant.parse("2026-10-04T10:00:00Z"));

        var rejected = assertThrows(BusinessException.class, () -> attachmentService.attach(
                "req-2", "T1", "AN-2", "ST-A", Instant.parse("2026-10-04T09:59:00Z")));
        assertEquals(422, rejected.status());

        var ok = attachmentService.attach("req-2", "T1", "AN-2", "ST-A",
                Instant.parse("2026-10-04T10:00:00Z"));
        assertEquals("AN-2", ok.animalCode());
        Integer keys = jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_key WHERE request_id = 'req-2'", Integer.class);
        assertEquals(1, keys);
    }

    @Test
    void transferSameRequestIdReplaysAndDifferentParamsRejected409() {
        tagService.register("T1", "ST-A");
        attachmentService.attach("req-a", "T1", "AN-1", "ST-A",
                Instant.parse("2026-10-04T08:00:00Z"));
        attachmentService.detach("T1", "ST-A", Instant.parse("2026-10-04T10:00:00Z"));

        var first = transferService.initiate("req-t", "T1", "ST-A", "ST-B",
                Instant.parse("2026-10-04T10:00:00Z"));
        var replay = transferService.initiate("req-t", "T1", "ST-A", "ST-B",
                Instant.parse("2026-10-04T10:00:00Z"));
        assertEquals(first.id(), replay.id());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer WHERE tag_code = 'T1'", Integer.class);
        assertEquals(1, count);

        var e = assertThrows(BusinessException.class, () -> transferService.initiate(
                "req-t", "T1", "ST-A", "ST-C", Instant.parse("2026-10-04T10:00:00Z")));
        assertEquals(409, e.status());
        assertEquals("REQUEST_ID_CONFLICT", e.code());
    }

    @Test
    void failedTransferDoesNotConsumeRequestKey() {
        tagService.register("T1", "ST-A");
        attachmentService.attach("req-a", "T1", "AN-1", "ST-A",
                Instant.parse("2026-10-04T08:00:00Z"));

        var rejected = assertThrows(BusinessException.class, () -> transferService.initiate(
                "req-t", "T1", "ST-A", "ST-B", Instant.parse("2026-10-04T09:00:00Z")));
        assertEquals(409, rejected.status());
        assertEquals("TAG_NOT_DETACHED", rejected.code());

        attachmentService.detach("T1", "ST-A", Instant.parse("2026-10-04T10:00:00Z"));
        var ok = transferService.initiate("req-t", "T1", "ST-A", "ST-B",
                Instant.parse("2026-10-04T10:00:00Z"));
        assertEquals("PENDING", ok.status());
    }
}