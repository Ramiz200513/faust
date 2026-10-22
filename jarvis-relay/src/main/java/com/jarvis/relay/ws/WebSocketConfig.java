package com.jarvis.relay.ws;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Wires our handler to a URL. Devices connect to  ws://<host>:8080/ws
 * setAllowedOrigins("*") keeps it simple for development; tighten later.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final JarvisWebSocketHandler handler;

    public WebSocketConfig(JarvisWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws").setAllowedOrigins("*");
    }

    /**
     * Скриншоты в base64 — это сотни КБ, а лимит текстового сообщения у Spring
     * по умолчанию всего 8 КБ. Поднимаем буфер, иначе большое сообщение рвёт
     * соединение ("connection abort"). Также ставим щадящий idle-таймаут.
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(8 * 1024 * 1024);   // 8 МБ
        container.setMaxBinaryMessageBufferSize(8 * 1024 * 1024);
        container.setMaxSessionIdleTimeout(600000L);              // 10 минут
        return container;
    }
}
