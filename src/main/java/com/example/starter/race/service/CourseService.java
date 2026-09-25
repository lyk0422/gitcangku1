package com.example.starter.race.service;

import com.example.starter.race.api.ClaimRecordRequest;
import com.example.starter.race.api.CourseRecordHistoryResponse;
import com.example.starter.race.api.CourseResponse;
import com.example.starter.race.api.RegisterCourseRequest;

/**
 * 赛道登记与赛道纪录认定应用服务；写方法在单个数据库事务内完成
 * “业务校验 + 原子指针切换/链追加 + 幂等记录”。
 */
public interface CourseService {

    /** 登记赛道；初始无纪录。重复登记同 courseKey 返回409。 */
    ServiceResult registerCourse(RegisterCourseRequest request);

    /**
     * 认定赛道纪录：赛事须已封榜、选手在封榜快照中且未取消资格、
     * 最终计时严格优于当前纪录（无纪录时任何合法完赛计时均可）。
     * 不满足更优条件返回422并携带实际当前纪录。
     */
    ServiceResult claimRecord(String courseKey, ClaimRecordRequest request);

    /** 查询赛道登记信息（含当前纪录ID）；不存在为404。只读。 */
    CourseResponse getCourse(String courseKey);

    /** 查询当前纪录与完整历史纪录链；不存在为404。只读，不触发认定。 */
    CourseRecordHistoryResponse getRecordHistory(String courseKey);
}
