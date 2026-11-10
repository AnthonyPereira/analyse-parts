package com.analyseparts;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AdminTokenFilterTest {

    private static MockHttpServletResponse call(AdminTokenFilter filter, String uri, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        if (token != null) {
            request.addHeader(AdminTokenFilter.HEADER, token);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void withATokenAdminRoutesNeedIt() throws Exception {
        AdminTokenFilter filter = new AdminTokenFilter("s3cret");
        assertThat(call(filter, "/api/admin/ingestion/prices", null).getStatus()).isEqualTo(401);
        assertThat(call(filter, "/api/admin/ingestion/prices", "wrong").getStatus()).isEqualTo(401);
        assertThat(call(filter, "/api/admin/ingestion/prices", "s3cret").getStatus()).isEqualTo(200);
        // le reste du site est public
        assertThat(call(filter, "/api/scores", null).getStatus()).isEqualTo(200);
    }

    @Test
    void withoutATokenEverythingPasses() throws Exception {
        assertThat(call(new AdminTokenFilter(""), "/api/admin/ingestion/prices", null).getStatus()).isEqualTo(200);
    }
}
