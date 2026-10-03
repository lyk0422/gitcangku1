package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.starter.service.AttachmentService;
import com.example.starter.service.BusinessException;
import com.example.starter.service.TagService;
import com.example.starter.service.TimelineService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 附着/摘除主流程与拒绝分支：左闭右开区间、冻结历史、时间倒退与交叉区间。
 */
class AttachmentFlowTest extends AbstractDatabaseTest {

    @Autowired
    private TagService tagService;
    @Autowired
    private AttachmentService attachmentService;
    @Autowired
    private TimelineService timelineService;

    private String rid() {
        return UUID.randomUUID().toString();
    }

    @Test
    void attachThenDetachFreezesHistory() {
        tagService.register("T1", "ST-A");
        var attached = attachmentService.attach(rid(), "T1", "AN-1", "ST-A",
                Instant.parse("2026-10-04T09:00:00Z"));
        assertNull(attached.detachedAt());

        var detached = attachmentService.detach("T1", "ST-A",
                Instant.parse("2026-10-04T10:00:00Z"));
        assertEquals(Instant.parse("2026-10-04T10:00:00Z"), detached.detachedAt());

        var events = timelineService.tagTimeline("T1");
        assertEquals(2, events.size());
        assertEquals("ATTACH", events.get(0).type());
        assertEquals("DETACH", events.get(1).type());
        assertEquals("ST-A", events.get(0).stationCode());
    }

    @Test
    void exampleScenarioDetachAtTenAllowsAttachAtTenRejectsNineFiftyNine() {
        tagService.register("L", "ST-A");
        attachmentService.attach(rid(), "L", "AN-1", "ST-A",
                Instant.parse("2026-10-04T08:00:00Z"));
        attachmentService.detach("L", "ST-A", Instant.parse("2026-10-04T10:00:00Z"));

        var crossing = assertThrows(BusinessException.class, () -> attachmentService.attach(
                rid(), "L", "AN-2", "ST-A", Instant.parse("2026-10-04T09:59:00Z")));
        assertEquals(422, crossing.status());
        assertEquals("TAG_INTERVAL_CROSSING", crossing.code());

        var ok = attachmentService.attach(rid(), "L", "AN-2", "ST-A",
                Instant.parse("2026-10-04T10:00:00Z"));
        assertEquals("AN-2", ok.animalCode());
    }

    @Test
    void attachWhileStillAttachedRejected409() {
        tagService.register("T1", "ST-A");
        attachmentService.attach(rid(), "T1", "AN-1", "ST-A",
                Instant.parse("2026-10-04T09:00:00Z"));
        var e = assertThrows(BusinessException.class, () -> attachmentService.attach(
                rid(), "T1", "AN-2", "ST-A", Instant.parse("2026-10-04T09:30:00Z")));
        assertEquals(409, e.status());
        assertEquals("TAG_STILL_ATTACHED", e.code());
    }

    @Test
    void attachByNonHolderRejected409() {
        tagService.register("T1", "ST-A");
        var e = assertThrows(BusinessException.class, () -> attachmentService.attach(
                rid(), "T1", "AN-1", "ST-B", Instant.parse("2026-10-04T09:00:00Z")));
        assertEquals(409, e.status());
        assertEquals("TAG_NOT_HELD_BY_STATION", e.code());
    }

    @Test
    void animalWithActiveTagRejected409() {
        tagService.register("T1", "ST-A");
        tagService.register("T2", "ST-A");
        attachmentService.attach(rid(), "T1", "AN-1", "ST-A",
                Instant.parse("2026-10-04T09:00:00Z"));
        var e = assertThrows(BusinessException.class, () -> attachmentService.attach(
                rid(), "T2", "AN-1", "ST-A", Instant.parse("2026-10-04T09:30:00Z")));
        assertEquals(409, e.status());
        assertEquals("ANIMAL_HAS_ACTIVE_TAG", e.code());
    }

    @Test
    void animalIntervalCrossingRejected422() {
        tagService.register("T1", "ST-A");
        tagService.register("T2", "ST-A");
        attachmentService.attach(rid(), "T1", "AN-1", "ST-A",
                Instant.parse("2026-10-04T09:00:00Z"));
        attachmentService.detach("T1", "ST-A", Instant.parse("2026-10-04T10:00:00Z"));
        var e = assertThrows(BusinessException.class, () -> attachmentService.attach(
                rid(), "T2", "AN-1", "ST-A", Instant.parse("2026-10-04T09:59:00Z")));
        assertEquals(422, e.status());
        assertEquals("ANIMAL_INTERVAL_CROSSING", e.code());
    }

    @Test
    void detachTimeRegressionRejected422() {
        tagService.register("T1", "ST-A");
        attachmentService.attach(rid(), "T1", "AN-1", "ST-A",
                Instant.parse("2026-10-04T09:00:00Z"));
        var e = assertThrows(BusinessException.class, () -> attachmentService.detach(
                "T1", "ST-A", Instant.parse("2026-10-04T08:59:00Z")));
        assertEquals(422, e.status());
        assertEquals("DETACH_TIME_REGRESSION", e.code());
    }

    @Test
    void detachByNonHolderRejected409() {
        tagService.register("T1", "ST-A");
        attachmentService.attach(rid(), "T1", "AN-1", "ST-A",
                Instant.parse("2026-10-04T09:00:00Z"));
        var e = assertThrows(BusinessException.class, () -> attachmentService.detach(
                "T1", "ST-B", Instant.parse("2026-10-04T10:00:00Z")));
        assertEquals(409, e.status());
        assertEquals("TAG_NOT_HELD_BY_STATION", e.code());
    }
}