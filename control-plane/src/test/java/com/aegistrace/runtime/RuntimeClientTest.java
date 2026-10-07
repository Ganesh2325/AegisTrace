package com.aegistrace.runtime;

import com.aegistrace.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeClientTest {
    @Test
    void distinguishesProviderTimeoutFromRuntimeFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/plan", exchange -> {
            byte[] body = "{\"detail\":\"model timeout\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(504, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var error = assertThrows(RuntimeClient.RuntimeCallException.class,
                    () -> client("http://127.0.0.1:" + server.getAddress().getPort()).plan(Map.of(), 1000));
            assertEquals("MODEL_TIMEOUT", error.getCategory());
            assertTrue(error.isRetryable());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void classifiesRuntimeHttpFailureAsInfrastructureFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/plan", exchange -> {
            byte[] body = "{\"detail\":\"unavailable\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var error = assertThrows(RuntimeClient.RuntimeCallException.class,
                    () -> client("http://127.0.0.1:" + server.getAddress().getPort()).plan(Map.of(), 1000));
            assertEquals("RUNTIME_FAILURE", error.getCategory());
            assertTrue(error.isRetryable());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void classifiesConnectionFailureAsRuntimeFailure() {
        var error = assertThrows(RuntimeClient.RuntimeCallException.class,
                () -> client("http://127.0.0.1:1").plan(Map.of(), 1000));
        assertEquals("RUNTIME_FAILURE", error.getCategory());
        assertTrue(error.isRetryable());
    }

    private RuntimeClient client(String runtimeUrl) {
        AppProperties properties = new AppProperties();
        properties.setRuntimeUrl(runtimeUrl);
        properties.setInternalToken("test-token");
        var beans = new DefaultListableBeanFactory();
        return new RuntimeClient(properties, new ObjectMapper(), beans.getBeanProvider(Tracer.class));
    }
}
