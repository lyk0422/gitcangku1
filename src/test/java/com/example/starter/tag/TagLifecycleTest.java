package com.example.starter.tag;

import com.example.starter.tag.service.TagService;
import com.example.starter.tag.web.AnimalTimelineResponse;
import com.example.starter.tag.web.AttachRequest;
import com.example.starter.tag.web.AttachResponse;
import com.example.starter.tag.web.DetachRequest;
import com.example.starter.tag.web.HandoffConfirmRequest;
import com.example.starter.tag.web.HandoffInitiateRequest;
import com.example.starter.tag.web.HandoffRejectRequest;
import com.example.starter.tag.web.HandoffCancelRequest;
import com.example.starter.tag.web.HandoffResponse;
import com.example.starter.tag.web.TagRegisterRequest;
import com.example.starter.tag.web.TagTimelineResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 H2(MODE=MySQL) 上的业务链测试：登记→附着→摘除→交接确认→再附着，
 * 覆盖左闭右开、拒绝原因、幂等重放/异参、取消/拒绝及时间线。
 */
@Import(FixedClockConfig.class)
class TagLifecycleTest extends AbstractTagIntegrationTest {

    @Autowired
    private TagService tagService;

    TagLifecycleTest() {
    }

    private static final Instant T09 = Instant.parse("2026-03-10T09:00:00Z");
    private static final Instant T0959 = Instant.parse("2026-03-10T09:59:00Z");
    private static final Instant T1000 = Instant.parse("2026-03-10T10:00:00Z");
    private static final Instant T1100 = Instant.parse("2026-03-10T11:00:00Z");

    private void register(String tagCode, String siteCode) {
        TagRegisterRequest request = new TagRegisterRequest();
        request.setTagCode(tagCode);
        request.setSiteCode(siteCode);
        tagService.registerTag(request);
    }

    private AttachRequest attachRequest(String requestId, String tag, String animal,
                                        String site, Instant at) {
        AttachRequest request = new AttachRequest();
        request.setRequestId(requestId);
        request.setTagCode(tag);
        request.setAnimalCode(animal);
        request.setSiteCode(site);
        request.setAttachAt(at);
        return request;
    }

    @Test
    void fullChainDetachHandoffConfirmAndReattach() {
        register("L", "S1");

        AttachResponse first = tagService.attach(
                attachRequest("req-1", "L", "A1", "S1", T09));
        assertFalse(first.isReplay());
        assertEquals("L", first.getTagCode());
        assertEquals("A1", first.getAnimalCode());

        // 09:59 标签仍在 A1 身上，交叉附着必须被拒绝。
        var overlap = assertThrows(com.example.starter.tag.service.UnprocessableException.class,
                () -> tagService.attach(attachRequest("req-x", "L", "A2", "S1", T0959)));
        assertTrue(overlap.getMessage().contains("overlaps"));

        DetachRequest detach = new DetachRequest();
        detach.setTagCode("L");
        detach.setSiteCode("S1");
        detach.setDetachAt(T1000);
        tagService.detach(detach);

        HandoffInitiateRequest initiate = new HandoffInitiateRequest();
        initiate.setRequestId("req-h1");
        initiate.setTagCode("L");
        initiate.setFromSiteCode("S1");
        initiate.setToSiteCode("S2");
        initiate.setInitiateAt(T1000);
        HandoffResponse handoff = tagService.initiateHandoff(initiate);
        assertEquals("PENDING", handoff.getStatus().name());
        String code = handoff.getHandoffCode();

        // 未确认期间 S2 还不能附着（持有权未转移）。
        var beforeConfirm = assertThrows(
                com.example.starter.tag.service.ConflictException.class,
                () -> tagService.attach(attachRequest("req-2", "L", "A2", "S2", T1000)));
        assertTrue(beforeConfirm.getMessage().contains("not held"));

        HandoffConfirmRequest confirm = new HandoffConfirmRequest();
        confirm.setHandoffCode(code);
        confirm.setSiteCode("S2");
        confirm.setConfirmAt(T1000);
        HandoffResponse confirmed = tagService.confirmHandoff(confirm);
        assertEquals("CONFIRMED", confirmed.getStatus().name());
        assertEquals(T1000, confirmed.getConfirmedAt());

        // 10:00 后新动物可附着：新附着从 10:00 开始，旧区间在 10:00 结束，左闭右开不冲突。
        AttachResponse second = tagService.attach(
                attachRequest("req-3", "L", "A2", "S2", T1000));
        assertFalse(second.isReplay());
        assertNotEquals(first.getAttachmentId(), second.getAttachmentId());

        TagTimelineResponse timeline = tagService.tagTimeline("L");
        assertEquals("S2", timeline.getHolderSiteCode());
        assertEquals(2, timeline.getAttachments().size());
        assertEquals("A1", timeline.getAttachments().get(0).getAnimalCode());
        assertEquals(T1000, timeline.getAttachments().get(0).getDetachAt());
        assertEquals("S1", timeline.getAttachments().get(0).getDetachSiteCode());
        assertEquals("A2", timeline.getAttachments().get(1).getAnimalCode());
        assertNull(timeline.getAttachments().get(1).getDetachAt());
        assertEquals(1, timeline.getHandoffs().size());
        assertEquals("CONFIRMED", timeline.getHandoffs().get(0).getStatus().name());
        assertEquals(T1000, timeline.getHandoffs().get(0).getConfirmedAt());

        AnimalTimelineResponse animalTimeline = tagService.animalTimeline("A1");
        assertEquals(1, animalTimeline.getAttachments().size());
        assertEquals(T09, animalTimeline.getAttachments().get(0).getAttachAt());
    }

    @Test
    void attachRejectedWhenAnimalWearsAnotherTagAtSameTime() {
        register("L1", "S1");
        register("L2", "S1");
        tagService.attach(attachRequest("a-1", "L1", "A1", "S1", T09));

        var ex = assertThrows(com.example.starter.tag.service.UnprocessableException.class,
                () -> tagService.attach(attachRequest("a-2", "L2", "A1", "S1", T0959)));
        assertTrue(ex.getMessage().contains("animal already wears"));
    }

    @Test
    void attachRejectedForNonHolderSite() {
        register("L", "S1");
        var ex = assertThrows(com.example.starter.tag.service.ConflictException.class,
                () -> tagService.attach(attachRequest("a-3", "L", "A1", "S2", T09)));
        assertTrue(ex.getMessage().contains("not held"));
    }

    @Test
    void detachWithoutActiveAttachmentConflicts() {
        register("L", "S1");
        DetachRequest detach = new DetachRequest();
        detach.setTagCode("L");
        detach.setSiteCode("S1");
        detach.setDetachAt(T1000);
        var ex = assertThrows(com.example.starter.tag.service.ConflictException.class,
                () -> tagService.detach(detach));
        assertTrue(ex.getMessage().contains("no active attachment"));
    }

    @Test
    void detachTimeBackwardsRejected() {
        register("L", "S1");
        tagService.attach(attachRequest("a-4", "L", "A1", "S1", T1000));
        DetachRequest detach = new DetachRequest();
        detach.setTagCode("L");
        detach.setSiteCode("S1");
        detach.setDetachAt(T09);
        var ex = assertThrows(com.example.starter.tag.service.UnprocessableException.class,
                () -> tagService.detach(detach));
        assertTrue(ex.getMessage().contains("later than the attachment start"));
    }

    @Test
    void handoffInitiateRequiresHolderAndConfirmRequiresDetachedTag() {
        register("L", "S1");
        tagService.attach(attachRequest("a-5", "L", "A1", "S1", T09));

        // 只有当前持有站点能发起交接。
        HandoffInitiateRequest wrongHolder = new HandoffInitiateRequest();
        wrongHolder.setRequestId("h-5x");
        wrongHolder.setTagCode("L");
        wrongHolder.setFromSiteCode("S2");
        wrongHolder.setToSiteCode("S1");
        wrongHolder.setInitiateAt(T0959);
        var notHolder = assertThrows(com.example.starter.tag.service.ConflictException.class,
                () -> tagService.initiateHandoff(wrongHolder));
        assertTrue(notHolder.getMessage().contains("current holder site"));

        HandoffInitiateRequest initiate = new HandoffInitiateRequest();
        initiate.setRequestId("h-5");
        initiate.setTagCode("L");
        initiate.setFromSiteCode("S1");
        initiate.setToSiteCode("S2");
        initiate.setInitiateAt(T0959);
        String code = tagService.initiateHandoff(initiate).getHandoffCode();

        // 标签尚未摘除：目标站点确认必须 409，持有权不转移。
        HandoffConfirmRequest confirm = new HandoffConfirmRequest();
        confirm.setHandoffCode(code);
        confirm.setSiteCode("S2");
        confirm.setConfirmAt(T1000);
        var notDetached = assertThrows(com.example.starter.tag.service.ConflictException.class,
                () -> tagService.confirmHandoff(confirm));
        assertTrue(notDetached.getMessage().contains("must be detached"));
        assertEquals("S1", tagService.tagTimeline("L").getHolderSiteCode());

        // 摘除后同一交接单确认成功，持有权转移到 S2。
        DetachRequest detach = new DetachRequest();
        detach.setTagCode("L");
        detach.setSiteCode("S1");
        detach.setDetachAt(T1000);
        tagService.detach(detach);
        HandoffResponse confirmed = tagService.confirmHandoff(confirm);
        assertEquals("CONFIRMED", confirmed.getStatus().name());
        assertEquals("S2", tagService.tagTimeline("L").getHolderSiteCode());
    }

    @Test
    void handoffRejectsWrongTargetAndBackwardsConfirm() {
        register("L", "S1");
        tagService.attach(attachRequest("a-6", "L", "A1", "S1", T09));
        DetachRequest detach = new DetachRequest();
        detach.setTagCode("L");
        detach.setSiteCode("S1");
        detach.setDetachAt(T1000);
        tagService.detach(detach);

        HandoffInitiateRequest initiate = new HandoffInitiateRequest();
        initiate.setRequestId("h-6");
        initiate.setTagCode("L");
        initiate.setFromSiteCode("S1");
        initiate.setToSiteCode("S2");
        initiate.setInitiateAt(T1000);
        String code = tagService.initiateHandoff(initiate).getHandoffCode();

        HandoffConfirmRequest wrongSite = new HandoffConfirmRequest();
        wrongSite.setHandoffCode(code);
        wrongSite.setSiteCode("S1");
        wrongSite.setConfirmAt(T1000);
        var wrong = assertThrows(com.example.starter.tag.service.UnprocessableException.class,
                () -> tagService.confirmHandoff(wrongSite));
        assertTrue(wrong.getMessage().contains("target site"));

        HandoffConfirmRequest backwards = new HandoffConfirmRequest();
        backwards.setHandoffCode(code);
        backwards.setSiteCode("S2");
        backwards.setConfirmAt(T09);
        var back = assertThrows(com.example.starter.tag.service.UnprocessableException.class,
                () -> tagService.confirmHandoff(backwards));
        assertTrue(back.getMessage().contains("initiate time"));
    }

    @Test
    void rejectHandoffKeepsHolderAndRecordsReason() {
        register("L", "S1");
        tagService.attach(attachRequest("a-7", "L", "A1", "S1", T09));
        DetachRequest detach = new DetachRequest();
        detach.setTagCode("L");
        detach.setSiteCode("S1");
        detach.setDetachAt(T1000);
        tagService.detach(detach);

        HandoffInitiateRequest initiate = new HandoffInitiateRequest();
        initiate.setRequestId("h-7");
        initiate.setTagCode("L");
        initiate.setFromSiteCode("S1");
        initiate.setToSiteCode("S2");
        initiate.setInitiateAt(T1000);
        String code = tagService.initiateHandoff(initiate).getHandoffCode();

        HandoffRejectRequest reject = new HandoffRejectRequest();
        reject.setHandoffCode(code);
        reject.setSiteCode("S2");
        reject.setReason("tag damaged");
        reject.setAt(T1000);
        HandoffResponse rejected = tagService.rejectHandoff(reject);
        assertEquals("REJECTED", rejected.getStatus().name());
        assertEquals("tag damaged", rejected.getRejectReason());

        // 拒绝后持有权仍在 S1，S1 可以再次附着。
        AttachResponse again = tagService.attach(
                attachRequest("a-8", "L", "A2", "S1", T1100));
        assertEquals("S1", again.getSiteCode());

        TagTimelineResponse timeline = tagService.tagTimeline("L");
        assertEquals("S1", timeline.getHolderSiteCode());
        assertEquals("tag damaged", timeline.getHandoffs().get(0).getRejectReason());
        assertNotNull(timeline.getHandoffs().get(0).getClosedAt());
    }

    @Test
    void cancelPendingHandoffRestoresHoldAndConfirmedCannotCancel() {
        register("L", "S1");
        tagService.attach(attachRequest("a-9", "L", "A1", "S1", T09));
        DetachRequest detach = new DetachRequest();
        detach.setTagCode("L");
        detach.setSiteCode("S1");
        detach.setDetachAt(T1000);
        tagService.detach(detach);

        HandoffInitiateRequest initiate = new HandoffInitiateRequest();
        initiate.setRequestId("h-9");
        initiate.setTagCode("L");
        initiate.setFromSiteCode("S1");
        initiate.setToSiteCode("S2");
        initiate.setInitiateAt(T1000);
        String code = tagService.initiateHandoff(initiate).getHandoffCode();

        // 非发起站点不能取消。
        HandoffCancelRequest wrong = new HandoffCancelRequest();
        wrong.setHandoffCode(code);
        wrong.setSiteCode("S2");
        wrong.setAt(T1000);
        assertThrows(com.example.starter.tag.service.UnprocessableException.class,
                () -> tagService.cancelHandoff(wrong));

        HandoffCancelRequest cancel = new HandoffCancelRequest();
        cancel.setHandoffCode(code);
        cancel.setSiteCode("S1");
        cancel.setAt(T1000);
        HandoffResponse cancelled = tagService.cancelHandoff(cancel);
        assertEquals("CANCELLED", cancelled.getStatus().name());

        // 取消后持有权不变，S1 可再次发起交接并确认；已确认单据不能取消。
        initiate.setRequestId("h-10");
        String code2 = tagService.initiateHandoff(initiate).getHandoffCode();
        HandoffConfirmRequest confirm = new HandoffConfirmRequest();
        confirm.setHandoffCode(code2);
        confirm.setSiteCode("S2");
        confirm.setConfirmAt(T1100);
        tagService.confirmHandoff(confirm);

        HandoffCancelRequest cancelConfirmed = new HandoffCancelRequest();
        cancelConfirmed.setHandoffCode(code2);
        cancelConfirmed.setSiteCode("S1");
        cancelConfirmed.setAt(T1100);
        var ex = assertThrows(com.example.starter.tag.service.ConflictException.class,
                () -> tagService.cancelHandoff(cancelConfirmed));
        assertTrue(ex.getMessage().contains("confirmed handoff cannot be cancelled"));
    }

    @Test
    void sameRequestIdReplaysAttachDifferentParamsConflict() {
        register("L", "S1");
        AttachResponse first = tagService.attach(
                attachRequest("dup-1", "L", "A1", "S1", T09));

        AttachResponse replay = tagService.attach(
                attachRequest("dup-1", "L", "A1", "S1", T09));
        assertTrue(replay.isReplay());
        assertEquals(first.getAttachmentId(), replay.getAttachmentId());

        var diff = assertThrows(com.example.starter.tag.service.ConflictException.class,
                () -> tagService.attach(attachRequest("dup-1", "L", "A2", "S1", T1100)));
        assertTrue(diff.getMessage().contains("different parameters"));

        // 只产生了一条附着记录。
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment WHERE tag_id = (SELECT id FROM tag WHERE tag_code='L')",
                Integer.class);
        assertEquals(1, count);
    }

    @Test
    void failedAttachDoesNotConsumeRequestId() {
        register("L", "S1");
        // 首次用 S2 附着（非持有站点）失败。
        assertThrows(com.example.starter.tag.service.ConflictException.class,
                () -> tagService.attach(attachRequest("dup-2", "L", "A1", "S2", T09)));
        // 请求键未被消费：同 requestId 改由 S1 以相同参数应成功（新业务尝试）。
        AttachResponse ok = tagService.attach(
                attachRequest("dup-2", "L", "A1", "S1", T09));
        assertFalse(ok.isReplay());

        Integer keyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'dup-2'",
                Integer.class);
        assertEquals(1, keyCount);
    }

    @Test
    void unknownReferencesReturn422Semantics() {
        assertThrows(com.example.starter.tag.service.UnprocessableException.class,
                () -> tagService.tagTimeline("NOPE"));
    }
}
