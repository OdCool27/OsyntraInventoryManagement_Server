package org.osyntra.osyntrainventorymanagement.identity;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.osyntra.osyntrainventorymanagement.controllers.ApiProblems;

import java.io.IOException;

public class SessionAuthenticationFilter extends OncePerRequestFilter {
    private final SessionService sessions;
    private final ApiProblems problems;

    public SessionAuthenticationFilter(SessionService sessions, ApiProblems problems) {
        this.sessions = sessions;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                if (!authorization.startsWith("Bearer ")) throw IdentitySupport.denied();
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(sessions.authenticate(authorization.substring(7)));
                SecurityContextHolder.setContext(context);
            } catch (ResponseStatusException exception) {
                response.setHeader("WWW-Authenticate", "Bearer realm=\"Orvexa\"");
                problems.write(HttpStatus.UNAUTHORIZED, "Session is invalid, expired or revoked", request, response);
                return;
            } catch (org.springframework.dao.DataAccessException exception) {
                response.setHeader("Retry-After", "1");
                problems.write(HttpStatus.SERVICE_UNAVAILABLE, "Authentication storage is temporarily unavailable", request, response);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
