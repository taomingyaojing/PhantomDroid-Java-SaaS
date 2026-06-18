package com.phantomdroid.repository;

import com.phantomdroid.entity.Device;
import com.phantomdroid.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Device persistence repository with multi-tenant query support.
 */
@Repository
public interface DeviceRepository extends JpaRepository<Device, Long> {

    /**
     * Find all devices owned by a specific user.
     */
    List<Device> findByOwner(User owner);

    /**
     * Find a single device by ADB port.
     */
    Optional<Device> findByAdbPort(int adbPort);

    /**
     * Find a device by ADB port and verify ownership.
     */
    Optional<Device> findByAdbPortAndOwner(int adbPort, User owner);

    /**
     * Check if a device belongs to a specific user by its ADB port.
     */
    boolean existsByAdbPortAndOwner(int adbPort, User owner);

    /**
     * Delete all devices owned by a specific user.
     */
    void deleteByOwner(User owner);
}
