package com.example.starter.blind;

import org.springframework.stereotype.Component;

/**
 * 基于 ThreadLocal 的当前请求操作者上下文，由过滤器在请求结束时清理。
 */
@Component
public class ActorContext {

    private static final ThreadLocal<Actor> HOLDER = new ThreadLocal<>();

    public void set(Actor actor) {
        HOLDER.set(actor);
    }

    public Actor get() {
        return HOLDER.get();
    }

    public void clear() {
        HOLDER.remove();
    }
}
