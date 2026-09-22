package com.example.starter.blind;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试上下文销毁时释放命名 H2 内存库：DB_CLOSE_DELAY=-1 使库不随连接关闭，
 * 因此在 JVM 退出前显式 SHUTDOWN，避免库在上下文结束后仍驻留。
 */
public class H2ShutdownBean implements DisposableBean {

    private final JdbcTemplate jdbc;

    public H2ShutdownBean(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void destroy() {
        jdbc.execute("SHUTDOWN");
    }
}
