package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.CreateRollbackPlanRequest;
import com.example.starter.firmware.api.DispatchRollbackRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.ReceiptRollbackRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.ResumeRollbackPlanRequest;
import com.example.starter.firmware.api.RollbackDispatchResponse;
import com.example.starter.firmware.api.RollbackPlanDetailView;
import com.example.starter.firmware.api.RollbackPlanView;
import com.example.starter.firmware.api.RollbackTaskView;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.RollbackPlanStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.RollbackService;
import com.example.starter.firmware.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多跳版本回退主流程、失败分支、健康门禁、人工恢复、幂等与只读查询测试（H2 内存库，MODE=MySQL）。
 */
@SpringBootTest
class RollbackPlanTest {

    @Autowired
    private RollbackService rollbackService;
    @Autowired
    private ReleaseService releaseService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private DeviceService deviceService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM rollback_task");
        jdbc.update("DELETE FROM rollback_plan_hop");
        jdbc.update("DELETE FROM rollback_plan");
        jdbc.update("DELETE FROM device_task_occupation");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
    }

    // ---------- 场景搭建辅助 ----------

    private long releaseWithSuccess(String reqPrefix, String model, String from, String to, int ratio,
                                    List<String> successDevices) {
        long releaseId = releaseService.create(
                new CreateReleaseRequest(reqPrefix + "-c", model, from, to, ratio, 2, 100)).releaseId();
        for (String deviceId : successDevices) {
            PullResponse pulled = taskService.pull(deviceId, reqPrefix + "-p-" + deviceId);
            assertThat(pulled.task()).isNotNull();
            taskService.receipt(pulled.task().taskId(),
                    new ReceiptRequest(reqPrefix + "-r-" + deviceId, ReceiptResult.SUCCESS));
        }
        releaseService.cancel(releaseId, reqPrefix + "-x");
        return releaseId;
    }

    private RollbackTaskView dispatch(long planId, String requestId, String deviceId) {
        RollbackDispatchResponse response = rollbackService.dispatch(planId,
                new DispatchRollbackRequest(requestId, deviceId));
        return response.task();
    }

    private RollbackTaskView receipt(long taskId, String requestId, String receiptKey, ReceiptResult result) {
        return rollbackService.receipt(taskId, new ReceiptRollbackRequest(requestId, receiptKey, result));
    }

    /**
     * 搭建：d1 历史 v1→v2→v3（路径长度2）；d2 历史 v1→v9→v2→v3（路径长度3）；
     * 来源投放单为最后一张 v2→v3（d1、d2 均参与）。
     */
    private long scenarioTwoDevicesDifferentLengths() {
        deviceService.register(new RegisterDeviceRequest("reg-d1", "d1", "m1", "v1", 1));
        deviceService.register(new RegisterDeviceRequest("reg-d2", "d2", "m1", "v1", 99));
        // rel1 v1→v2：仅 d1 命中（d2 桶号99 >= 比例50）
        long rel1 = releaseWithSuccess("rel1", "m1", "v1", "v2", 50, List.of("d1"));
        // relA v1→v9：仅 d2
        long relA = releaseWithSuccess("relA", "m1", "v1", "v9", 100, List.of("d2"));
        // relB v9→v2：仅 d2
        long relB = releaseWithSuccess("relB", "m1", "v9", "v2", 100, List.of("d2"));
        // rel2 v2→v3：d1、d2 均参与
        long rel2 = releaseWithSuccess("rel2", "m1", "v2", "v3", 100, List.of("d1", "d2"));
        assertThat(rel1).isNotEqualTo(relA);
        assertThat(relB).isPositive();
        assertThat(deviceService.get("d1").currentVersion()).isEqualTo("v3");
        assertThat(deviceService.get("d2").currentVersion()).isEqualTo("v3");
        return rel2;
    }

    // ---------- 主流程 ----------

    @Test
    void 多跳主流程_路径长度可不同_逐跳成功原子切换版本_全部到达目标COMPLETED() {
        long rel2 = scenarioTwoDevicesDifferentLengths();

        RollbackPlanView plan = rollbackService.createPlan(new CreateRollbackPlanRequest(
                "rb-create", "plan-1", rel2, "v1", List.of("d2", "d1"), null, null));
        assertThat(plan.status()).isEqualTo("ACTIVE");
        assertThat(plan.currentHop()).isZero();
        assertThat(plan.maxHop()).isEqualTo(2);

        // hop0：两设备 v3→v2
        RollbackTaskView t1 = dispatch(plan.planId(), "rb-p-d1-0", "d1");
        RollbackTaskView t2 = dispatch(plan.planId(), "rb-p-d2-0", "d2");
        assertThat(t1.expectedVersion()).isEqualTo("v3");
        assertThat(t1.toVersion()).isEqualTo("v2");
        assertThat(t1.sourceReleaseId()).isEqualTo(rel2);
        assertThat(t2.round()).isEqualTo(1);
        receipt(t1.taskId(), "rb-r-d1-0", "key-d1-0", ReceiptResult.SUCCESS);
        assertThat(deviceService.get("d1").currentVersion()).isEqualTo("v2");
        receipt(t2.taskId(), "rb-r-d2-0", "key-d2-0", ReceiptResult.SUCCESS);
        assertThat(deviceService.get("d2").currentVersion()).isEqualTo("v2");
        assertThat(rollbackService.findPlan(plan.planId()).currentHop()).isEqualTo(1);

        // hop1：d1 v2→v1（最后一跳）；d2 v2→v9
        RollbackTaskView t1h1 = dispatch(plan.planId(), "rb-p-d1-1", "d1");
        RollbackTaskView t2h1 = dispatch(plan.planId(), "rb-p-d2-1", "d2");
        assertThat(t1h1.toVersion()).isEqualTo("v1");
        assertThat(t2h1.toVersion()).isEqualTo("v9");
        receipt(t2h1.taskId(), "rb-r-d2-1", "key-d2-1", ReceiptResult.SUCCESS);
        assertThat(deviceService.get("d2").currentVersion()).isEqualTo("v9");
        // 仅 d2 完成 hop1：d1 未成功，计划仍停在 hop1
        assertThat(rollbackService.findPlan(plan.planId()).currentHop()).isEqualTo(1);

        receipt(t1h1.taskId(), "rb-r-d1-1", "key-d1-1", ReceiptResult.SUCCESS);
        assertThat(deviceService.get("d1").currentVersion()).isEqualTo("v1");
        // d1 到达自身目标且两设备均完成 hop1，计划推进到 hop2（仅 d2 参与）
        var afterD1 = rollbackService.findPlan(plan.planId());
        assertThat(afterD1.currentHop()).isEqualTo(2);
        assertThat(afterD1.status()).isEqualTo(RollbackPlanStatus.ACTIVE);

        // d1 在 hop2 无路径：派发返回空
        assertThat(dispatch(plan.planId(), "rb-p-d1-x", "d1")).isNull();

        // hop2：仅 d2 v9→v1
        RollbackTaskView t2h2 = dispatch(plan.planId(), "rb-p-d2-2", "d2");
        assertThat(t2h2.toVersion()).isEqualTo("v1");
        receipt(t2h2.taskId(), "rb-r-d2-2", "key-d2-2", ReceiptResult.SUCCESS);

        var finished = rollbackService.findPlan(plan.planId());
        assertThat(finished.status()).isEqualTo(RollbackPlanStatus.COMPLETED);
        assertThat(deviceService.get("d1").currentVersion()).isEqualTo("v1");
        assertThat(deviceService.get("d2").currentVersion()).isEqualTo("v1");
        // 完成后占用全部释放
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_task_occupation", Long.class)).isZero();
    }

    @Test
    void 查询详情_返回设备逐跳历史与轮次统计_只读且冻结来源投放信息() {
        long rel2 = scenarioTwoDevicesDifferentLengths();
        RollbackPlanView plan = rollbackService.createPlan(new CreateRollbackPlanRequest(
                "rb-create", "plan-1", rel2, "v1", List.of("d1", "d2"), 2, 50));
        RollbackTaskView t1 = dispatch(plan.planId(), "p1", "d1");
        receipt(t1.taskId(), "r1", "k1", ReceiptResult.SUCCESS);
        RollbackTaskView t2 = dispatch(plan.planId(), "p2", "d2");
        receipt(t2.taskId(), "r2", "k2", ReceiptResult.FAILED);

        RollbackPlanDetailView detail = rollbackService.detail(plan.planId());
        assertThat(detail.plan().planKey()).isEqualTo("plan-1");
        // 5 条冻结路径行：d1 两条、d2 三条
        assertThat(detail.devicePaths()).hasSize(5);
        assertThat(detail.devicePaths()).anySatisfy(p -> {
            assertThat(p.deviceId()).isEqualTo("d1");
            assertThat(p.hopIndex()).isEqualTo(0);
            assertThat(p.expectedVersion()).isEqualTo("v3");
            assertThat(p.toVersion()).isEqualTo("v2");
            assertThat(p.sourceReleaseId()).isEqualTo(rel2);
            assertThat(p.sourceTaskId()).isPositive();
            assertThat(p.hopStatus()).isEqualTo("SUCCESS");
            assertThat(p.completedRound()).isEqualTo(1);
        });
        assertThat(detail.devicePaths()).anySatisfy(p -> {
            assertThat(p.deviceId()).isEqualTo("d2");
            assertThat(p.hopIndex()).isZero();
            assertThat(p.hopStatus()).isEqualTo("FAILED");
        });
        // 尚未派发的跳为 WAITING
        assertThat(detail.devicePaths()).anyMatch(p -> p.deviceId().equals("d1")
                && p.hopIndex() == 1 && p.hopStatus().equals("WAITING") && p.completedRound() == null);
        // round1 统计：派发2、成功1、失败1、待回执0
        assertThat(detail.rounds()).hasSize(1);
        RollbackPlanDetailView.RoundStatView stat = detail.rounds().get(0);
        assertThat(stat.hopIndex()).isZero();
        assertThat(stat.round()).isEqualTo(1);
        assertThat(stat.dispatched()).isEqualTo(2);
        assertThat(stat.success()).isEqualTo(1);
        assertThat(stat.failed()).isEqualTo(1);
        assertThat(stat.pending()).isZero();
        // 只读：连续查询不改变状态
        assertThat(rollbackService.detail(plan.planId()).plan().status()).isEqualTo("PAUSED");
    }

    // ---------- 健康门禁与人工恢复 ----------

    @Test
    void 失败率达到阈值_当次回执原子PAUSED_未派发与后续跳停止_恢复只含未成功设备() {
        long rel2 = scenarioTwoDevicesDifferentLengths();
        RollbackPlanView plan = rollbackService.createPlan(new CreateRollbackPlanRequest(
                "rb-create", "plan-1", rel2, "v1", List.of("d1", "d2"), 2, 50));

        RollbackTaskView t1 = dispatch(plan.planId(), "p1", "d1");
        RollbackTaskView t2 = dispatch(plan.planId(), "p2", "d2");
        receipt(t1.taskId(), "r1", "k1", ReceiptResult.SUCCESS);
        // 50% >= 阈值50%：d2 失败当次同事务 PAUSED
        receipt(t2.taskId(), "r2", "k2", ReceiptResult.FAILED);
        assertThat(rollbackService.findPlan(plan.planId()).status())
                .isEqualTo(RollbackPlanStatus.PAUSED);
        // FAILED 保留版本
        assertThat(deviceService.get("d2").currentVersion()).isEqualTo("v3");
        // PAUSED 后停止派发
        assertThat(dispatch(plan.planId(), "p3", "d2")).isNull();
        // 非 PAUSED 恢复 409 的反面：此时恢复成功
        RollbackPlanView resumed = rollbackService.resume(plan.planId(),
                new ResumeRollbackPlanRequest("rs1", "重试失败设备"));
        assertThat(resumed.status()).isEqualTo("ACTIVE");
        assertThat(resumed.currentRound()).isEqualTo(2);
        assertThat(resumed.roundSuccess()).isZero();
        assertThat(resumed.roundFailed()).isZero();

        // 新 round 只包含未成功设备 d2：d1 已 SUCCESS 不再派发
        RollbackTaskView t2r2 = dispatch(plan.planId(), "p4", "d2");
        assertThat(t2r2).isNotNull();
        assertThat(t2r2.round()).isEqualTo(2);
        assertThat(dispatch(plan.planId(), "p5", "d1")).isNull();
        // 旧失败任务保留为历史：hop0 round1 失败1，round2 待回执1；d2 当前跳状态为重试中 PENDING
        List<RollbackPlanDetailView.RoundStatView> statsAtPause =
                rollbackService.detail(plan.planId()).rounds();
        assertThat(statsAtPause).filteredOn(r -> r.hopIndex() == 0 && r.round() == 1)
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.success()).isEqualTo(1);
                    assertThat(r.failed()).isEqualTo(1);
                });
        assertThat(statsAtPause).filteredOn(r -> r.hopIndex() == 0 && r.round() == 2)
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.dispatched()).isEqualTo(1);
                    assertThat(r.pending()).isEqualTo(1);
                });

        // round2 成功后继续推进直至全部完成
        receipt(t2r2.taskId(), "r3", "k3", ReceiptResult.SUCCESS);
        assertThat(deviceService.get("d2").currentVersion()).isEqualTo("v2");
        RollbackTaskView d2h1 = dispatch(plan.planId(), "p6", "d2");
        RollbackTaskView d1h1 = dispatch(plan.planId(), "p7", "d1");
        receipt(d1h1.taskId(), "r4", "k4", ReceiptResult.SUCCESS);
        receipt(d2h1.taskId(), "r5", "k5", ReceiptResult.SUCCESS);
        RollbackTaskView d2h2 = dispatch(plan.planId(), "p8", "d2");
        receipt(d2h2.taskId(), "r6", "k6", ReceiptResult.SUCCESS);
        assertThat(rollbackService.findPlan(plan.planId()).status())
                .isEqualTo(RollbackPlanStatus.COMPLETED);
        // 两轮统计均在详情中
        List<RollbackPlanDetailView.RoundStatView> rounds = rollbackService.detail(plan.planId()).rounds();
        assertThat(rounds).filteredOn(r -> r.hopIndex() == 0)
                .extracting(RollbackPlanDetailView.RoundStatView::round)
                .containsExactly(1, 2);
    }

    @Test
    void 失败率低于阈值_继续执行不暂停() {
        long rel2 = scenarioTwoDevicesDifferentLengths();
        RollbackPlanView plan = rollbackService.createPlan(new CreateRollbackPlanRequest(
                "rb-create", "plan-9", rel2, "v1", List.of("d1", "d2"), 2, 51));
        RollbackTaskView t1 = dispatch(plan.planId(), "p1", "d1");
        RollbackTaskView t2 = dispatch(plan.planId(), "p2", "d2");
        receipt(t1.taskId(), "r1", "k1", ReceiptResult.SUCCESS);
        receipt(t2.taskId(), "r2", "k2", ReceiptResult.FAILED);
        // 50% < 51% 不暂停；但 d2 失败无法完成本跳，计划保持 ACTIVE/hop0
        assertThat(rollbackService.findPlan(plan.planId()).status())
                .isEqualTo(RollbackPlanStatus.ACTIVE);
        assertThat(rollbackService.findPlan(plan.planId()).currentHop()).isZero();
    }

    @Test
    void 样本未达下限_不评估暂停() {
        long rel2 = scenarioTwoDevicesDifferentLengths();
        // 下限2，仅 d2 失败、d1 尚未回执：样本1 < 2 不暂停
        RollbackPlanView plan = rollbackService.createPlan(new CreateRollbackPlanRequest(
                "rb-create", "plan-8", rel2, "v1", List.of("d1", "d2"), 2, 1));
        RollbackTaskView t2 = dispatch(plan.planId(), "p2", "d2");
        receipt(t2.taskId(), "r2", "k2", ReceiptResult.FAILED);
        assertThat(rollbackService.findPlan(plan.planId()).status())
                .isEqualTo(RollbackPlanStatus.ACTIVE);
    }

    // ---------- 失败分支 ----------

    @Test
    void 创建_来源单未结束409_设备不属于来源单409() {
        deviceService.register(new RegisterDeviceRequest("reg-d1", "d1", "m1", "v1", 1));
        long activeId = releaseService.create(new CreateReleaseRequest("rc", "m1", "v1", "v2", 100))
                .releaseId();
        assertThatThrownBy(() -> rollbackService.createPlan(new CreateRollbackPlanRequest(
                "rb1", "plan-a", activeId, "v1", List.of("d1"), null, null)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.code()).isEqualTo("RELEASE_NOT_FINISHED");
                });
        releaseService.cancel(activeId, "rc-cancel");

        long cancelledId = releaseWithSuccess("rel", "m1", "v1", "v2", 100, List.of("d1"));
        deviceService.register(new RegisterDeviceRequest("reg-d3", "d3", "m2", "v2", 1));
        assertThatThrownBy(() -> rollbackService.createPlan(new CreateRollbackPlanRequest(
                "rb2", "plan-b", cancelledId, "v1", List.of("d1", "d3"), null, null)))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo("DEVICE_NOT_IN_SOURCE_RELEASE"));
    }

    @Test
    void 创建_目标版本不在历史链409_当前版本不一致409_路径超长409() {
        long rel2 = scenarioTwoDevicesDifferentLengths();
        // 目标 vX 不在任何设备链上
        assertThatThrownBy(() -> rollbackService.createPlan(new CreateRollbackPlanRequest(
                "rb1", "plan-a", rel2, "vX", List.of("d1"), null, null)))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo("ROLLBACK_PATH_NOT_FOUND"));

        // 设备当前版本被外力改离末代 v3
        jdbc.update("UPDATE device SET current_version = 'v2' WHERE device_id = 'd1'");
        assertThatThrownBy(() -> rollbackService.createPlan(new CreateRollbackPlanRequest(
                "rb2", "plan-b", rel2, "v1", List.of("d1"), null, null)))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo("ROLLBACK_CURRENT_VERSION_MISMATCH"));
        jdbc.update("UPDATE device SET current_version = 'v3' WHERE device_id = 'd1'");

        // 路径长度超过5：直接构造6代连续 SUCCESS 历史
        buildLongChainDevice("long1", 6);
        assertThatThrownBy(() -> rollbackService.createPlan(new CreateRollbackPlanRequest(
                "rb3", "plan-c", chainSourceRelease, "v0", List.of("long1"), null, null)))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo("ROLLBACK_PATH_LENGTH_INVALID"));
    }

    private long chainSourceRelease;

    /**
     * 直接以 H2 数据构造 n 代连续升级成功历史：v0→v1→...→vn，全部发布单 CANCELLED。
     */
    private void buildLongChainDevice(String deviceId, int generations) {
        jdbc.update("INSERT INTO device (device_id, model, current_version, bucket_no) VALUES (?, 'mc', ?, 1)",
                deviceId, "v" + generations);
        long prevRelease = 0;
        for (int i = 0; i < generations; i++) {
            long releaseId = insertCancelledRelease("mc", "v" + i, "v" + (i + 1));
            insertSuccessTask(releaseId, deviceId);
            prevRelease = releaseId;
        }
        chainSourceRelease = prevRelease;
    }

    private long insertCancelledRelease(String model, String from, String to) {
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            java.sql.PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO release_order (version, model, from_version, to_version, ratio, status,"
                            + " sample_floor, failure_threshold_percent, monitor_round, active_model)"
                            + " VALUES (1, ?, ?, ?, 100, 'CANCELLED', 2, 100, 1, NULL)",
                    new String[]{"id"});
            ps.setString(1, model);
            ps.setString(2, from);
            ps.setString(3, to);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private long insertSuccessTask(long releaseId, String deviceId) {
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            java.sql.PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rollout_task (release_id, device_id, status, first_result)"
                            + " VALUES (?, ?, 'SUCCESS', 'SUCCESS')",
                    new String[]{"id"});
            ps.setLong(1, releaseId);
            ps.setString(2, deviceId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    @Test
    void 创建_历史代次不连续409() {
        // d9：两条成功历史 v1→v2 与 v8→v3，代次断裂
        jdbc.update("INSERT INTO device (device_id, model, current_version, bucket_no) VALUES ('d9','mm','v3',1)");
        long rel1 = insertCancelledRelease("mm", "v1", "v2");
        long rel2Broken = insertCancelledRelease("mm", "v8", "v3");
        insertSuccessTask(rel1, "d9");
        insertSuccessTask(rel2Broken, "d9");
        assertThatThrownBy(() -> rollbackService.createPlan(new CreateRollbackPlanRequest(
                "rb1", "plan-a", rel2Broken, "v1", List.of("d9"), null, null)))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo("ROLLBACK_HISTORY_MISMATCH"));
    }

    // ---------- 回执、取消与键约束 ----------

    @Test
    void 回执_改结果409_receiptKey冲突409_取消后不再受理() {
        long rel2 = scenarioTwoDevicesDifferentLengths();
        RollbackPlanView plan = rollbackService.createPlan(new CreateRollbackPlanRequest(
                "rb-create", "plan-1", rel2, "v1", List.of("d1", "d2"), null, null));
        RollbackTaskView t1 = dispatch(plan.planId(), "p1", "d1");

        receipt(t1.taskId(), "r1", "key-1", ReceiptResult.SUCCESS);
        // 改结果 409
        assertThatThrownBy(() -> receipt(t1.taskId(), "r2", "key-2", ReceiptResult.FAILED))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo("RECEIPT_RESULT_CONFLICT"));
        // 同结果但换 receiptKey 409
        assertThatThrownBy(() -> receipt(t1.taskId(), "r3", "key-other", ReceiptResult.SUCCESS))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo("RECEIPT_KEY_CONFLICT"));
        // 同结果同 receiptKey 重放成功
        RollbackTaskView replay = receipt(t1.taskId(), "r4", "key-1", ReceiptResult.SUCCESS);
        assertThat(replay.status()).isEqualTo("SUCCESS");

        // d2 先派发（PENDING），再取消计划：取消后该任务不再受理回执
        RollbackTaskView t2 = dispatch(plan.planId(), "p2", "d2");
        assertThat(t2).isNotNull();
        RollbackPlanView cancelled = rollbackService.cancel(plan.planId(), "cx1");
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> receipt(t2.taskId(), "r5", "key-5", ReceiptResult.SUCCESS))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo("TASK_CANCELLED"));
        // 取消后版本不变、占用释放
        assertThat(deviceService.get("d2").currentVersion()).isEqualTo("v3");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_task_occupation WHERE scope = 'ROLLBACK'", Long.class)).isZero();
        // 已取消不能恢复
        assertThatThrownBy(() -> rollbackService.resume(plan.planId(),
                new ResumeRollbackPlanRequest("rs", "x")))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo("PLAN_CANCELLED"));
        // 重复取消幂等
        assertThat(rollbackService.cancel(plan.planId(), "cx2").status()).isEqualTo("CANCELLED");
    }

    @Test
    void receiptKey全局唯一_跨任务复用409且任务仍PENDING() {
        long rel2 = scenarioTwoDevicesDifferentLengths();
        RollbackPlanView plan = rollbackService.createPlan(new CreateRollbackPlanRequest(
                "rb-create", "plan-7", rel2, "v1", List.of("d1", "d2"), null, null));
        RollbackTaskView t1 = dispatch(plan.planId(), "p1", "d1");
        RollbackTaskView t2 = dispatch(plan.planId(), "p2", "d2");
        receipt(t1.taskId(), "r1", "shared-key", ReceiptResult.SUCCESS);
        // 同一 receiptKey 用于另一任务：唯一约束冲突，事务回滚，t2 保持 PENDING 且版本不变
        assertThatThrownBy(() -> receipt(t2.taskId(), "r2", "shared-key", ReceiptResult.SUCCESS))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo("RECEIPT_KEY_CONFLICT"));
        assertThat(rollbackService.findPlan(plan.planId()).roundSuccess()).isEqualTo(1);
        assertThat(deviceService.get("d2").currentVersion()).isEqualTo("v3");
    }

    // ---------- 幂等 ----------

    @Test
    void 创建幂等_设备集合换序重放同一计划_异参409_失败不占键_planKey唯一() {
        long rel2 = scenarioTwoDevicesDifferentLengths();
        CreateRollbackPlanRequest first = new CreateRollbackPlanRequest(
                "rb-same", "plan-1", rel2, "v1", List.of("d1", "d2"), null, null);
        CreateRollbackPlanRequest reordered = new CreateRollbackPlanRequest(
                "rb-same", "plan-1", rel2, "v1", List.of("d2", "d1"), null, null);
        RollbackPlanView p1 = rollbackService.createPlan(first);
        RollbackPlanView p2 = rollbackService.createPlan(reordered);
        assertThat(p2.planId()).isEqualTo(p1.planId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollback_plan", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollback_plan_hop", Long.class)).isEqualTo(5);

        // 同 requestId 异参（目标版本不同）409
        assertThatThrownBy(() -> rollbackService.createPlan(new CreateRollbackPlanRequest(
                "rb-same", "plan-1", rel2, "v2", List.of("d1", "d2"), null, null)))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo("REQUEST_ID_CONFLICT"));

        // 取消首份计划以释放设备占用（历史不变），再验证失败不占键与 planKey 唯一
        rollbackService.cancel(p1.planId(), "rb-cancel");

        // 失败不占键：新 requestId 先因目标不可达失败，再用同 requestId 成功
        assertThatThrownBy(() -> rollbackService.createPlan(new CreateRollbackPlanRequest(
                "rb-retry", "plan-2", rel2, "vX", List.of("d1"), null, null)))
                .isInstanceOf(ApiException.class);
        RollbackPlanView retried = rollbackService.createPlan(new CreateRollbackPlanRequest(
                "rb-retry", "plan-2", rel2, "v2", List.of("d1"), null, null));
        assertThat(retried.planKey()).isEqualTo("plan-2");

        // planKey 全局唯一：换新 requestId 仍 409
        assertThatThrownBy(() -> rollbackService.createPlan(new CreateRollbackPlanRequest(
                "rb-other", "plan-2", rel2, "v2", List.of("d2"), null, null)))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo("PLAN_KEY_EXISTS"));
    }

    @Test
    void 计划取消后_同一来源投放单可再建计划_设备占用互斥只在生命周期内() {
        long rel2 = scenarioTwoDevicesDifferentLengths();
        RollbackPlanView first = rollbackService.createPlan(new CreateRollbackPlanRequest(
                "rb1", "plan-1", rel2, "v2", List.of("d1"), null, null));
        // 生命周期内同设备被占用：含 d1 的第二份计划 409
        assertThatThrownBy(() -> rollbackService.createPlan(new CreateRollbackPlanRequest(
                "rb2", "plan-2", rel2, "v1", List.of("d1", "d2"), null, null)))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo("DEVICE_BUSY"));
        // 取消后释放占用，同一来源单可再建计划（不限制来源单上的计划数量）
        rollbackService.cancel(first.planId(), "rb-cancel");
        RollbackPlanView second = rollbackService.createPlan(new CreateRollbackPlanRequest(
                "rb3", "plan-3", rel2, "v1", List.of("d1", "d2"), null, null));
        assertThat(second.planKey()).isEqualTo("plan-3");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollback_plan", Long.class)).isEqualTo(2);
    }

    @Test
    void 设备有PENDING正向任务时_不能创建回退计划() {
        deviceService.register(new RegisterDeviceRequest("reg-d1", "d1", "m1", "v1", 1));
        // ACTIVE 投放单产生 PENDING 任务但不取消、不回执
        long activeId = releaseService.create(new CreateReleaseRequest("rc", "m1", "v1", "v2", 100))
                .releaseId();
        PullResponse pulled = taskService.pull("d1", "pull-1");
        assertThat(pulled.task()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT scope FROM device_task_occupation WHERE device_id = 'd1'",
                String.class)).isEqualTo("FORWARD");
        // 直接将发布单置为 CANCELLED 模拟"投放刚结束但仍有 PENDING 回执窗口"，历史条件满足时仍须拒绝
        jdbc.update("UPDATE release_order SET status = 'CANCELLED', active_model = NULL WHERE id = ?", activeId);
        assertThat(ReleaseStatus.valueOf(jdbc.queryForObject(
                "SELECT status FROM release_order WHERE id = ?", String.class, activeId)))
                .isEqualTo(ReleaseStatus.CANCELLED);
        assertThatThrownBy(() -> rollbackService.createPlan(new CreateRollbackPlanRequest(
                "rb1", "plan-busy", activeId, "v1", List.of("d1"), null, null)))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo("DEVICE_BUSY"));
    }
}
