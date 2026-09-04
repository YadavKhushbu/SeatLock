package com.seatlock.service;

import com.seatlock.domain.Role;
import com.seatlock.domain.User;
import com.seatlock.dto.Dtos;
import com.seatlock.exception.Exceptions;
import com.seatlock.repo.UserRepository;
import com.seatlock.security.AuthUser;
import com.seatlock.security.JwtService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public Dtos.AuthResponse register(Dtos.RegisterRequest request) {
        String email = request.email().trim().toLowerCase();

        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName().trim())
                .role(Role.ROLE_USER)
                .build();
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Checking existsByEmail first would still leave a race between the
            // check and the insert. The unique constraint is the only thing that
            // actually decides, so let it decide and translate the result.
            throw new Exceptions.EmailTaken(email);
        }
        return issueFor(user);
    }

    @Transactional(readOnly = true)
    public Dtos.AuthResponse login(Dtos.LoginRequest request) {
        User user = users.findByEmail(request.email().trim().toLowerCase())
                .orElse(null);

        // Verify a hash even when the account does not exist, so the response
        // time does not reveal which emails are registered.
        if (user == null) {
            passwordEncoder.matches(request.password(), "$2a$10$invalidinvalidinvalidinvalidinvalidinvalidinvalidinvalidi");
            throw new Exceptions.BadCredentials();
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new Exceptions.BadCredentials();
        }
        return issueFor(user);
    }

    private Dtos.AuthResponse issueFor(User user) {
        AuthUser principal = AuthUser.from(user);
        return Dtos.AuthResponse.bearer(
                jwtService.issue(principal),
                jwtService.ttlSeconds(),
                new Dtos.UserSummary(user.getId(), user.getEmail(), user.getFullName()));
    }
}
