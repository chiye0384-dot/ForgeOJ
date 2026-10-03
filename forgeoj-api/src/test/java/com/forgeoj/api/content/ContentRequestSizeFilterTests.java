/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.content;

import static org.assertj.core.api.Assertions.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ContentRequestSizeFilterTests {
    @Test void rejectsDeclaredOversizeBeforeTheBodyIsRead() throws Exception {
        var request=new MockHttpServletRequest("PUT","/api/v1/me/authored-problems/id/tests/zip") {
            @Override public long getContentLengthLong() {return TestDatasetArchive.MAX_ARCHIVE_BYTES+1L;}
        };
        request.setContentType("application/zip");var response=new MockHttpServletResponse();
        new ContentRequestSizeFilter().doFilter(request,response,(req,res)->{throw new AssertionError("Body processing must not start");});
        assertThat(response.getStatus()).isEqualTo(413);assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");assertThat(response.getContentAsByteArray()).isEmpty();
    }
    @Test void boundsActualUnknownLengthReadsAndAcceptsExactBoundary() throws Exception {
        var request=new MockHttpServletRequest();request.setContent(new byte[]{1,2,3,4,5});
        try(var stream=new ContentRequestSizeFilter.LimitedInput(request.getInputStream(),4)) {
            assertThat(stream.readNBytes(4)).containsExactly(1,2,3,4);
            assertThatThrownBy(stream::read).isInstanceOf(IOException.class).hasMessage("CONTENT_BODY_LIMIT");
        }
        var exact=new MockHttpServletRequest();exact.setContent(new byte[]{1,2,3,4});
        try(var stream=new ContentRequestSizeFilter.LimitedInput(exact.getInputStream(),4)) {assertThat(stream.readAllBytes()).hasSize(4);}
    }
    @Test void leavesExistingJudgingAndAuthenticationRoutesUntouched() throws Exception {
        var request=new MockHttpServletRequest("POST","/api/v1/submissions");var response=new MockHttpServletResponse();
        new ContentRequestSizeFilter().doFilter(request,response,(req,res)->assertThat(req).isSameAs(request));
    }
}
