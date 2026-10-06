package com.example.starter.plan;

import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

/**
 * 测试库释放：测试上下文关闭时对 H2 内存库执行 SHUTDOWN，
 * 弥补 DB_CLOSE_DELAY=-1 下数据库不随连接关闭而释放的问题（内存数据本就不跨进程恢复）。
 * 多个测试上下文共享同名库，重复关闭的异常直接忽略。
 */
@Component
public class TestDatabaseShutdown implements ApplicationListener<ContextClosedEvent> {

    private final DataSource dataSource;

    public TestDatabaseShutdown(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        } catch (Exception ignored) {
            // 数据库已被其他上下文关闭，忽略
        }
    }
}
