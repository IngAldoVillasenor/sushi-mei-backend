package com.sushimei.sushimei.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestCorrelationFilterTest {

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @Test
    void preservesSafeRequestIdAndClearsMdcAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/promotions/active");
        request.addHeader(RequestCorrelationFilter.HEADER_NAME, "android-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] observedRequestId = new String[1];

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                observedRequestId[0] = MDC.get(RequestCorrelationFilter.MDC_KEY));

        assertThat(observedRequestId[0]).isEqualTo("android-123");
        assertThat(response.getHeader(RequestCorrelationFilter.HEADER_NAME)).isEqualTo("android-123");
        assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isNull();
    }

    @Test
    void replacesUnsafeRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/promotions/active");
        request.addHeader(RequestCorrelationFilter.HEADER_NAME, "not safe with spaces");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> { });

        assertThat(response.getHeader(RequestCorrelationFilter.HEADER_NAME))
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
