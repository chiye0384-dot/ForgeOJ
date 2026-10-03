/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.content;

import java.io.*;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounds the authoring JSON stream before Jackson materializes it, including chunked requests. */
@Component
public final class ContentRequestSizeFilter extends OncePerRequestFilter {
    static final int MAX_JSON_BYTES = 32 * 1024 * 1024;
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath()+"/api/v1/me/authored-problems");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        int limit=request.getContentType()!=null && request.getContentType().startsWith("application/zip") ? TestDatasetArchive.MAX_ARCHIVE_BYTES : MAX_JSON_BYTES;
        if(request.getContentLengthLong()>limit) {response.setStatus(413);response.setHeader("Cache-Control","no-store");return;}
        chain.doFilter(new HttpServletRequestWrapper(request) {
            private ServletInputStream limited;
            @Override public ServletInputStream getInputStream() throws IOException {
                if(limited==null) limited=new LimitedInput(request.getInputStream(),limit);return limited;
            }
            @Override public BufferedReader getReader() throws IOException {return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
        },response);
    }
    static final class LimitedInput extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long limit;
        private long read;
        LimitedInput(ServletInputStream delegate,long limit) {this.delegate=delegate;this.limit=limit;}
        @Override public int read() throws IOException {int value=delegate.read();if(value>=0 && ++read>limit) throw new IOException("CONTENT_BODY_LIMIT");return value;}
        @Override public int read(byte[] bytes,int offset,int length) throws IOException {
            if(length==0) return 0;
            int count=delegate.read(bytes,offset,(int)Math.min(length,Math.max(1,limit-read+1)));
            if(count>0 && (read+=count)>limit) throw new IOException("CONTENT_BODY_LIMIT");return count;
        }
        @Override public boolean isFinished() {return delegate.isFinished();}
        @Override public boolean isReady() {return delegate.isReady();}
        @Override public void setReadListener(ReadListener listener) {delegate.setReadListener(listener);}
        @Override public void close() throws IOException {delegate.close();}
    }
}
