package com.stefankabasaj.courtly.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RequestIdFilter")
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    private String run(String incoming, MockHttpServletResponse response) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (incoming != null) request.addHeader("X-Request-Id", incoming);
        AtomicReference<String> seen = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> seen.set(MDC.get("requestId")));
        return seen.get();
    }

    @Test
    @DisplayName("keeps the id nginx sent, logs it, and returns it")
    void keepsIncoming() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(run("abc-123", response)).isEqualTo("abc-123");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("abc-123");
        assertThat(MDC.get("requestId")).isNull();   // cleaned up after the request
    }

    @Test
    @DisplayName("makes one up when there is none")
    void generates() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        String id = run(null, response);
        assertThat(id).isNotBlank();
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(id);
    }

    @Test
    @DisplayName("replaces an id that could mess with the logs")
    void rejectsUnsafe() throws Exception {
        String id = run("evil\nINFO fake log line", new MockHttpServletResponse());
        assertThat(id).doesNotContain("\n").doesNotContain("evil");
    }
}
