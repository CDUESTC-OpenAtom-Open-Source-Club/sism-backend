package com.sism.iam.infrastructure.persistence;

import com.sism.iam.domain.user.User;
import com.sism.iam.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class JpaUserRepository implements UserRepository {

    private final JpaUserRepositoryInternal jpaRepository;

    @Override
    public Optional<User> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public List<User> findAllByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return jpaRepository.findByIdIn(ids);
    }

    @Override
    public List<User> findAll() {
        return jpaRepository.findAll();
    }

    @Override
    public Page<User> findAll(Pageable pageable) {
        return jpaRepository.findAll(pageable);
    }

    @Override
    @Cacheable(cacheNames = "userByUsername", cacheManager = "applicationCacheManager", key = "#username", unless = "#result == null")
    public Optional<User> findByUsername(String username) {
        return jpaRepository.findByUsername(username);
    }

    @Override
    @Cacheable(cacheNames = "userByEmail", cacheManager = "applicationCacheManager", key = "#email", unless = "#result == null")
    public Optional<User> findByEmail(String email) {
        return jpaRepository.findByEmail(email);
    }

    @Override
    @Cacheable(cacheNames = "userByPhone", cacheManager = "applicationCacheManager", key = "#phone", unless = "#result == null")
    public Optional<User> findByPhone(String phone) {
        return jpaRepository.findByPhone(phone);
    }

    @Override
    public List<User> findByOrgId(Long orgId) {
        return jpaRepository.findByOrgId(orgId);
    }

    @Override
    public List<User> findByRoleId(Long roleId) {
        return jpaRepository.findByRoleId(roleId);
    }

    @Override
    public List<Long> findRoleIdsByUserId(Long userId) {
        return jpaRepository.findRoleIdsByUserId(userId);
    }

    @Override
    @Cacheable(cacheNames = "roleCodesByUserId", cacheManager = "applicationCacheManager", key = "#userId", unless = "#result == null")
    public List<String> findRoleCodesByUserId(Long userId) {
        return jpaRepository.findRoleCodesByUserId(userId);
    }

    @Override
    public List<String> findPermissionCodesByUserId(Long userId) {
        return jpaRepository.findPermissionCodesByUserId(userId);
    }

    @Override
    public Map<Long, Long> countUsersByRoleIds(Set<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, Long> counts = new LinkedHashMap<>();
        for (Object[] row : jpaRepository.countUsersByRoleIds(List.copyOf(roleIds))) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return counts;
    }

    @Override
    public List<User> findByIsActive(Boolean isActive) {
        return jpaRepository.findByIsActive(isActive);
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = "userByUsername", cacheManager = "applicationCacheManager", key = "#user.username", condition = "#user != null && #user.username != null"),
            @CacheEvict(cacheNames = "userByEmail", cacheManager = "applicationCacheManager", key = "#user.email", condition = "#user != null && #user.email != null"),
            @CacheEvict(cacheNames = "userByPhone", cacheManager = "applicationCacheManager", key = "#user.phone", condition = "#user != null && #user.phone != null"),
            @CacheEvict(cacheNames = "roleCodesByUserId", cacheManager = "applicationCacheManager", key = "#user.id", condition = "#user != null && #user.id != null")
    })
    public User save(User user) {
        return jpaRepository.save(user);
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = "userByUsername", cacheManager = "applicationCacheManager", key = "#user.username", condition = "#user != null && #user.username != null"),
            @CacheEvict(cacheNames = "userByEmail", cacheManager = "applicationCacheManager", key = "#user.email", condition = "#user != null && #user.email != null"),
            @CacheEvict(cacheNames = "userByPhone", cacheManager = "applicationCacheManager", key = "#user.phone", condition = "#user != null && #user.phone != null"),
            @CacheEvict(cacheNames = "roleCodesByUserId", cacheManager = "applicationCacheManager", key = "#user.id", condition = "#user != null && #user.id != null")
    })
    public void delete(User user) {
        jpaRepository.delete(user);
    }

    @Override
    public boolean existsById(Long id) {
        return jpaRepository.existsById(id);
    }

    @Override
    public boolean existsByUsername(String username) {
        return jpaRepository.existsByUsername(username);
    }

    @Override
    public boolean existsByEmail(String email) {
        return jpaRepository.existsByEmail(email);
    }

    @Override
    public boolean existsByPhone(String phone) {
        return jpaRepository.existsByPhone(phone);
    }
}
