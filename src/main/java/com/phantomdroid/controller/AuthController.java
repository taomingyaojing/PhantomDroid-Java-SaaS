package com.phantomdroid.controller;

import com.phantomdroid.dto.ApiResponse;
import com.phantomdroid.entity.User;
import com.phantomdroid.repository.UserRepository;
import com.phantomdroid.util.JwtUtil;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Authentication controller — lightweight register/login.
 * No Spring Security; raw BCrypt + JWT.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder;

    public AuthController(UserRepository userRepository, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    /**
     * User registration.
     * POST /api/auth/register
     * Body: { "username": "...", "password": "..." }
     */
    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@RequestBody RegisterRequest body) {
        String username = body.getUsername().trim();
        String password = body.getPassword();

        if (username.length() < 3 || username.length() > 32) {
            return ApiResponse.error(400, "Username must be 3-32 characters");
        }
        if (password.length() < 6 || password.length() > 128) {
            return ApiResponse.error(400, "Password must be 6-128 characters");
        }

        if (userRepository.existsByUsername(username)) {
            return ApiResponse.error(409, "Username already exists");
        }

        // BCrypt hash the password — never store plaintext
        String hashedPassword = passwordEncoder.encode(password);
        User user = new User(username, hashedPassword, User.Role.USER);

        // First user ever created gets ADMIN role automatically
        if (userRepository.count() == 0) {
            user.setRole(User.Role.ADMIN);
            log.info("First user '{}' promoted to ADMIN", username);
        }

        user = userRepository.save(user);

        String token = jwtUtil.generateToken(user.getId(), user.getUsername());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", user.getId());
        data.put("username", user.getUsername());
        data.put("role", user.getRole().name());
        data.put("token", token);
        data.put("tokenExpiryMs", jwtUtil.getExpirationMs());

        log.info("User registered: {} (role={})", username, user.getRole());
        return ApiResponse.success(data);
    }

    /**
     * User login.
     * POST /api/auth/login
     * Body: { "username": "...", "password": "..." }
     */
    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody LoginRequest body) {
        String username = body.getUsername().trim();
        String password = body.getPassword();

        Optional<User> optUser = userRepository.findByUsername(username);
        if (optUser.isEmpty()) {
            return ApiResponse.error(401, "Invalid username or password");
        }

        User user = optUser.get();

        // Verify BCrypt hash
        if (!passwordEncoder.matches(password, user.getPassword())) {
            return ApiResponse.error(401, "Invalid username or password");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUsername());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", user.getId());
        data.put("username", user.getUsername());
        data.put("role", user.getRole().name());
        data.put("token", token);
        data.put("tokenExpiryMs", jwtUtil.getExpirationMs());

        log.info("User logged in: {}", username);
        return ApiResponse.success(data);
    }

    // --- Request DTOs ---

    public static class RegisterRequest {
        @NotBlank
        private String username;
        @NotBlank
        private String password;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class LoginRequest {
        @NotBlank
        private String username;
        @NotBlank
        private String password;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
}
