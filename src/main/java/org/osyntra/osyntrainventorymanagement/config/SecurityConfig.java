package org.osyntra.osyntrainventorymanagement.config;

import org.osyntra.osyntrainventorymanagement.models.UserStatus;
import org.osyntra.osyntrainventorymanagement.repositories.EmployeeRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(EmployeeRepository employeeRepository) {
        return email -> {
            var employee = employeeRepository.findByEmail(email)
                    .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
            return User.withUsername(employee.getEmail()).password(employee.getPasswordHash())
                    .roles(employee.isOperator() ? "OPERATOR" : employee.getRole().name())
                    .disabled(employee.getStatus() != UserStatus.ACTIVE).build();
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/error").permitAll()
                        .requestMatchers("/api/storefront/**").permitAll()
                        .requestMatchers("/api/auth/me").authenticated()
                        .requestMatchers("/api/platform/**").hasRole("OPERATOR")
                        .requestMatchers("/api/employees/**").hasAnyRole("OWNER", "MANAGER")
                        .requestMatchers("/api/reports/**", "/api/audit/**").hasAnyRole("OWNER", "MANAGER")
                        .requestMatchers("/api/counts/**").hasAnyRole("OWNER", "MANAGER", "CLERK")
                        .requestMatchers("/api/imports/**").hasAnyRole("OWNER", "MANAGER")
                        .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole("OWNER", "MANAGER", "CLERK", "CASHIER")
                        .requestMatchers("/api/products/**", "/api/inventory/**", "/api/categories/**", "/api/fitments/**")
                        .hasAnyRole("OWNER", "MANAGER")
                        .requestMatchers("/api/business/**", "/api/locations/**").hasAnyRole("OWNER", "MANAGER")
                        .requestMatchers("/api/orders/**").hasAnyRole("OWNER", "MANAGER", "CASHIER", "CLERK")
                        .anyRequest().denyAll())
                .build();
    }
}
