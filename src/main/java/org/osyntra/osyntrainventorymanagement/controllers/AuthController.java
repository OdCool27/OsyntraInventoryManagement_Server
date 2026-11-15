package org.osyntra.osyntrainventorymanagement.controllers;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.osyntra.osyntrainventorymanagement.identity.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

import java.util.*;

@RestController
@RequestMapping({"/api/v1/auth", "/api/auth"})
@org.springframework.validation.annotation.Validated
public class AuthController {
    private final IdentityService identity;
    private final SessionService sessions;
    private final MfaService mfa;
    private final AuthRateLimiter limits;
    private final MailOutbox mail;

    public AuthController(IdentityService identity, SessionService sessions, MfaService mfa, AuthRateLimiter limits, MailOutbox mail) {
        this.identity = identity;
        this.sessions = sessions;
        this.mfa = mfa;
        this.limits = limits;
        this.mail = mail;
    }

    private void limit(HttpServletRequest request, String action, String email, int count) {
        limits.check(action + ":ip:" + request.getRemoteAddr(), 100, 900000);
        if (email != null) limits.check(action + ":email:" + IdentitySupport.normalize(email), count, 900000);
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> register(@Valid @RequestBody IdentityDtos.Register r, HttpServletRequest request) {
        limit(request, "register", r.email(), 5);
        return identity.register(r);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody IdentityDtos.Login r, HttpServletRequest request) {
        limit(request, "login", r.email(), 20);
        return identity.login(r);
    }

    @PostMapping("/email-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> requestVerification(@Valid @RequestBody IdentityDtos.EmailRequest r, HttpServletRequest request) {
        limit(request, "verification", r.email(), 5);
        return identity.requestEmail(r.email(), false);
    }

    @PostMapping("/email-verification/confirm")
    public Map<String, Object> verify(@Valid @RequestBody IdentityDtos.Token r, HttpServletRequest request) {
        limit(request, "token-verify", null, 20);
        return identity.verifyEmail(r.token());
    }

    @PostMapping("/password-reset")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> requestReset(@Valid @RequestBody IdentityDtos.EmailRequest r, HttpServletRequest request) {
        limit(request, "reset", r.email(), 5);
        return identity.requestEmail(r.email(), true);
    }

    @PostMapping("/password-reset/confirm")
    public Map<String, Object> reset(@Valid @RequestBody IdentityDtos.Reset r, HttpServletRequest request) {
        limit(request, "token-reset", null, 20);
        return identity.reset(r);
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        return identity.self();
    }

    @PostMapping("/business")
    public Map<String, Object> select(@Valid @RequestBody IdentityDtos.SelectBusiness r) {
        return sessions.select(r.membershipId());
    }

    @GetMapping("/sessions")
    public List<Map<String, Object>> sessions() {
        return sessions.list();
    }

    @GetMapping("/security-events")
    public List<Map<String, Object>> events(
            @RequestParam(defaultValue = "50") @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(200) int limit,
            @RequestParam(defaultValue = "0") @jakarta.validation.constraints.Min(0) int offset) {
        return identity.securityEvents(limit, offset);
    }

    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id) {
        SessionService.full();
        sessions.revoke(SessionService.current().userId(), id.toString());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout() {
        var current = SessionService.current();
        sessions.revoke(current.userId(), current.sessionId());
    }

    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll() {
        SessionService.full();
        sessions.revokeAll(SessionService.current().userId());
    }

    private void mfaLimit(HttpServletRequest request) {
        limit(request, "mfa", SessionService.current().email(), 10);
    }

    @PostMapping("/mfa/setup")
    public Map<String, Object> setup(@Valid @RequestBody(required = false) IdentityDtos.MfaSetup r, HttpServletRequest request) {
        mfaLimit(request);
        return mfa.setup(r == null ? null : r.password());
    }

    @PostMapping("/mfa/confirm")
    public Map<String, Object> confirm(@Valid @RequestBody IdentityDtos.MfaCode r, HttpServletRequest request) {
        mfaLimit(request);
        return mfa.confirm(r.code());
    }

    @PostMapping("/mfa/verify")
    public Map<String, Object> verify(@Valid @RequestBody IdentityDtos.MfaCode r, HttpServletRequest request) {
        mfaLimit(request);
        return mfa.verify(r.code(), r.recoveryCode());
    }

    @PostMapping("/mfa/recover")
    public Map<String, Object> recover(@Valid @RequestBody IdentityDtos.Recovery r, HttpServletRequest request) {
        mfaLimit(request);
        return mfa.recover(r.password(), r.recoveryCode());
    }

    @PostMapping("/mfa/disable")
    public Map<String, Object> disable(@Valid @RequestBody IdentityDtos.DisableMfa r, HttpServletRequest request) {
        mfaLimit(request);
        return mfa.disable(r.password(), r.code());
    }

    @GetMapping("/mail-preview")
    public List<Map<String, Object>> preview(HttpServletRequest request) {
        return mail.preview(request.getRemoteAddr());
    }
}
