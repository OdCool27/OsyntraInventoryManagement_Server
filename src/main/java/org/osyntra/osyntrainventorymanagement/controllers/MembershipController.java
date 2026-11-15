package org.osyntra.osyntrainventorymanagement.controllers;

import jakarta.validation.Valid;
import org.osyntra.osyntrainventorymanagement.identity.*;
import org.osyntra.osyntrainventorymanagement.dtos.TenancyDtos;
import org.osyntra.osyntrainventorymanagement.services.TenancyService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

import java.util.*;

@RestController
@RequestMapping("/api/v1")
@org.springframework.validation.annotation.Validated
public class MembershipController {
    private final MembershipService memberships;
    private final InvitationService invitations;
    private final TenancyService tenancy;
    private final AuthRateLimiter limits;

    public MembershipController(MembershipService memberships, InvitationService invitations, TenancyService tenancy, AuthRateLimiter limits) {
        this.memberships = memberships;
        this.invitations = invitations;
        this.tenancy = tenancy;
        this.limits = limits;
    }

    @GetMapping("/memberships")
    public List<Map<String, Object>> mine() {
        return memberships.list();
    }

    @GetMapping("/business/memberships")
    public List<Map<String, Object>> members() {
        return memberships.businessMembers();
    }

    @GetMapping("/business/security-audit")
    public List<Map<String, Object>> events(
            @RequestParam(defaultValue = "50") @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(200) int limit,
            @RequestParam(defaultValue = "0") @jakarta.validation.constraints.Min(0) int offset) {
        return memberships.securityEvents(limit, offset);
    }

    @PutMapping("/business/memberships/{id}")
    public Map<String, Object> edit(@PathVariable int id, @Valid @RequestBody IdentityDtos.MembershipUpdate r) {
        return memberships.update(id, r);
    }

    @PostMapping("/businesses")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody IdentityDtos.BusinessCreate r) {
        limits.check("business-create:" + SessionService.current().userId(), 10, 3600000);
        return tenancy.createOwned(r);
    }

    @GetMapping("/branches")
    public List<Map<String, Object>> branches() {
        return tenancy.locations();
    }

    @PostMapping("/branches")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> branch(@Valid @RequestBody TenancyDtos.Location r) {
        return tenancy.location(r);
    }

    @PutMapping("/branches/{id}")
    public Map<String, Object> branch(@PathVariable int id, @Valid @RequestBody IdentityDtos.BranchUpdate r) {
        return tenancy.updateBranch(id, r);
    }

    @GetMapping("/invitations")
    public List<Map<String, Object>> invitations() {
        return invitations.list();
    }

    @PostMapping("/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> invite(@Valid @RequestBody IdentityDtos.Invite r) {
        limits.check("invite:" + SessionService.current().userId(), 20, 3600000);
        return invitations.issue(r);
    }

    @DeleteMapping("/invitations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id) {
        invitations.revoke(id.toString());
    }

    @PostMapping("/invitations/accept")
    public Map<String, Object> accept(@Valid @RequestBody IdentityDtos.Accept r, jakarta.servlet.http.HttpServletRequest request) {
        limits.check("invite-accept:" + request.getRemoteAddr(), 30, 900000);
        return invitations.accept(r);
    }
}
