package com.practice.aiplatform.studyplan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface QuizQuestionRepository extends JpaRepository<QuizQuestion, Long> {
    List<QuizQuestion> findByStudyPlanItemId(Long studyPlanItemId);

    List<QuizQuestion> findByStudyPlanItemIdIn(List<Long> studyPlanItemIds);

    @Modifying
    @Query("DELETE FROM QuizQuestion qq WHERE qq.studyPlanItem.id IN (SELECT spi.id FROM StudyPlanItem spi WHERE spi.studyPlan.student.id = :studentId)")
    void deleteByStudentId(@Param("studentId") Long studentId);
}
