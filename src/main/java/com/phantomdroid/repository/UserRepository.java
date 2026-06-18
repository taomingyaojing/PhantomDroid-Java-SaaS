package com.phantomdroid.repository;

import com.phantomdroid.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * User persistence repository.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Find user by unique username.
     */
    Optional<User> findByUsername(String username);

    /**
     * Check if username already exists.
     */
    boolean existsByUsername(String username);
}
