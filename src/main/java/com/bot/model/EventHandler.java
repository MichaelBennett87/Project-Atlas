package com.bot.model;

public interface EventHandler<T> {
    void onEvent(T event);
}
