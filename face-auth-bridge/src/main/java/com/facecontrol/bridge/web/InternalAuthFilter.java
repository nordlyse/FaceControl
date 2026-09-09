package com.facecontrol.bridge.web;

import com.facecontrol.bridge.config.BridgeProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class InternalAuthFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Internal-Face-Secret";

    private final BridgeProperties props;

    public InternalAuthFilter(BridgeProperties props) {
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/internal"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        String got = request.getHeader(HEADER);
        if (got == null || !got.equals(props.getInternalSecret())) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.getWriter().write("forbidden");
            return;
        }
        chain.doFilter(request, response);
    }
}
