package com.example.backend.skill;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface UserSkillRepository extends JpaRepository<UserSkillEntity, Long> {

    List<UserSkillEntity> findByUserId(String userId);

    boolean existsByUserIdAndSkillName(String userId, String skillName);

    @Transactional
    void deleteByUserIdAndSkillName(String userId, String skillName);

    @Transactional
    void deleteBySkillName(String skillName);
}
