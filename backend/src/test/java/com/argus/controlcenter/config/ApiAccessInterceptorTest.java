package com.argus.controlcenter.config;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** API 令牌保护的单元测试，验证缺失、错误和正确令牌三条路径。 */
class ApiAccessInterceptorTest {

    @Test
    void rejectsMissingOrWrongToken() throws Exception {
        SecurityProperties properties = new SecurityProperties();
        properties.setApiAuthRequired(true);
        properties.setApiAccessToken("test-token");
        ApiAccessInterceptor interceptor = new ApiAccessInterceptor(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/instances");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);

        request.addHeader("Authorization", "Bearer wrong-token");
        response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void acceptsCorrectTokenAndKeepsHealthProbePublic() throws Exception {
        SecurityProperties properties = new SecurityProperties();
        properties.setApiAuthRequired(true);
        properties.setApiAccessToken("test-token");
        ApiAccessInterceptor interceptor = new ApiAccessInterceptor(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/instances");
        request.addHeader("Authorization", "Bearer test-token");
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();

        MockHttpServletRequest health = new MockHttpServletRequest("GET", "/api/health");
        assertThat(interceptor.preHandle(health, new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void failsClosedWhenProtectionEnabledWithoutToken() {
        SecurityProperties properties = new SecurityProperties();
        properties.setApiAuthRequired(true);
        assertThatThrownBy(() -> new ApiAccessInterceptor(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARGUS_API_ACCESS_TOKEN");
    }
}
