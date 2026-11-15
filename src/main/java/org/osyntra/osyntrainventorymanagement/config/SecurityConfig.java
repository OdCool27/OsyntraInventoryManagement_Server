package org.osyntra.osyntrainventorymanagement.config;

import org.osyntra.osyntrainventorymanagement.controllers.ApiProblems;
import org.osyntra.osyntrainventorymanagement.identity.*;
import org.osyntra.osyntrainventorymanagement.models.UserStatus;
import org.osyntra.osyntrainventorymanagement.repositories.EmployeeRepository;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // Suppresses Spring's fallback generated user; HTTP authentication uses SessionService only.
    @Bean
    public UserDetailsService userDetailsService(EmployeeRepository employees) {
        return email -> {
            var user = employees.findByEmail(email).orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
            return User.withUsername(user.getEmail()).password(user.getPasswordHash()).roles("IDENTITY")
                    .disabled(user.getStatus() != UserStatus.ACTIVE).build();
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ApiProblems problems, SessionService sessions) throws Exception {
        AuthenticationEntryPoint unauthenticated = (request, response, exception) -> {
            response.setHeader("WWW-Authenticate", "Bearer realm=\"Orvexa\"");
            problems.write(HttpStatus.UNAUTHORIZED, "A valid Bearer session is required", request, response);
        };
        return http.csrf(csrf -> csrf.disable()).cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable()).formLogin(form -> form.disable()).logout(logout -> logout.disable())
                .addFilterBefore(new SessionAuthenticationFilter(sessions, problems), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler((request, response, exception) -> problems.write(HttpStatus.FORBIDDEN, "Action is not permitted", request, response)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .requestMatchers("/api/storefront/**").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/register", "/api/auth/login", "/api/auth/email-verification", "/api/auth/email-verification/confirm", "/api/auth/password-reset", "/api/auth/password-reset/confirm",
                                "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/email-verification", "/api/v1/auth/email-verification/confirm", "/api/v1/auth/password-reset", "/api/v1/auth/password-reset/confirm",
                                "/api/v1/invitations/accept").permitAll()
                        .requestMatchers("/api/auth/me", "/api/auth/logout", "/api/auth/mfa/**", "/api/auth/mail-preview",
                                "/api/v1/auth/me", "/api/v1/auth/logout", "/api/v1/auth/mfa/**", "/api/v1/auth/mail-preview").authenticated()
                        .requestMatchers("/api/auth/**", "/api/v1/auth/**", "/api/v1/memberships", "/api/v1/businesses").hasRole("IDENTITY")
                        .requestMatchers("/api/platform/**").hasRole("OPERATOR")
                        .requestMatchers("/api/employees/**", "/api/v1/business/memberships/**", "/api/v1/invitations/**").hasAnyRole("OWNER", "ADMINISTRATOR", "MANAGER")
                        .requestMatchers("/api/reports/**", "/api/audit/**").hasAnyRole("OWNER", "ADMINISTRATOR", "MANAGER")
                        .requestMatchers("/api/counts/**").hasAnyRole("OWNER", "ADMINISTRATOR", "MANAGER", "CLERK", "INVENTORY_CLERK")
                        .requestMatchers("/api/imports/**").hasAnyRole("OWNER", "ADMINISTRATOR", "MANAGER")
                        .requestMatchers("/api/v1/checkout/payments/*/reconcile", "/api/v1/checkout/reversals/*/review", "/api/v1/checkout/reversals/*/complete", "/api/v1/checkout/prices", "/api/v1/checkout/promotions", "/api/v1/checkout/promotions/*/revoke").hasAnyRole("OWNER", "ADMINISTRATOR", "MANAGER")
                        .requestMatchers("/api/v1/checkout/**").hasAnyRole("OWNER", "ADMINISTRATOR", "MANAGER", "CASHIER")
                        .requestMatchers("/api/v1/purchasing/**", "/api/v1/inventory/adjustments", "/api/v1/inventory/products/*/tracking", "/api/v1/inventory/transfers/**", "/api/v1/inventory/batches/*/count").hasAnyRole("OWNER", "ADMINISTRATOR", "MANAGER")
                        .requestMatchers("/api/v1/catalogue/imports/**").hasAnyRole("OWNER", "ADMINISTRATOR", "MANAGER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/catalogue/products/*/convert").hasAnyRole("OWNER", "ADMINISTRATOR", "MANAGER", "CASHIER", "INVENTORY_CLERK", "CLERK")
                        .requestMatchers(HttpMethod.POST, "/api/v1/catalogue/**").hasAnyRole("OWNER", "ADMINISTRATOR", "MANAGER")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/settings", "/api/v1/branches/*/settings", "/api/v1/onboarding/**").hasAnyRole("OWNER", "ADMINISTRATOR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/tax/**", "/api/v1/products/*/tax").hasAnyRole("OWNER", "ADMINISTRATOR")
                        .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole("OWNER", "ADMINISTRATOR", "MANAGER", "CLERK", "INVENTORY_CLERK", "CASHIER")
                        .requestMatchers("/api/v1/branches/**").hasAnyRole("OWNER", "ADMINISTRATOR")
                        .requestMatchers("/api/products/**", "/api/inventory/**", "/api/categories/**", "/api/fitments/**").hasAnyRole("OWNER", "ADMINISTRATOR", "MANAGER")
                        .requestMatchers("/api/business/**", "/api/locations/**").hasAnyRole("OWNER", "ADMINISTRATOR")
                        .requestMatchers("/api/orders/**").hasAnyRole("OWNER", "ADMINISTRATOR", "MANAGER", "CASHIER", "CLERK", "INVENTORY_CLERK")
                        .anyRequest().denyAll()).build();
    }
}
