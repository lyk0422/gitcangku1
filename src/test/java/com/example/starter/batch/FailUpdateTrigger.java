package com.example.starter.batch;

import org.h2.api.Trigger;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 测试用 H2 触发器：当被更新行的 batch_key 等于 {@link #failKey} 时抛错，
 * 用于制造"召回闭包中某个已放行后代的待处置更新失败"这一真实数据库失败，
 * 验证整次召回事务回滚。仅测试类路径使用。
 */
public class FailUpdateTrigger implements Trigger {

    /**
     * 置为非 null 后，更新该 batch_key 行的语句将失败；置 null 放行所有更新。
     */
    public static volatile String failKey;

    @Override
    public void fire(Connection conn, Object[] oldRow, Object[] newRow) throws SQLException {
        if (failKey != null && oldRow != null && failKey.equals(oldRow[1])) {
            throw new SQLException("模拟待处置更新失败: " + failKey);
        }
    }
}
