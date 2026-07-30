package com.practice.aiplatform.practice;

// --- ADD THESE IMPORTS ---
import com.practice.aiplatform.user.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Pageable; // 👈 Add this
import java.util.List;
// -------------------------

@Repository
public interface AnswerRepository extends JpaRepository<Answer, Long> {

        List<Answer> findAllByStudentOrderBySubmittedAtDesc(Student student, Pageable pageable);

        List<Answer> findAllByStudentAndEvaluationStatusInOrderBySubmittedAtAsc(Student student, List<String> statuses);

        List<Answer> findTop20ByStudentOrderBySubmittedAtDesc(Student student);

        // --- JOIN FETCH variants (N+1 safe) for RecommendationService ---
        @Query("select a from Answer a join fetch a.question where a.student = :student order by a.submittedAt desc")
        List<Answer> findAllWithQuestionByStudentOrderBySubmittedAtDesc(@Param("student") Student student, Pageable pageable);

        @Query("select a from Answer a join fetch a.question where a.student = :student order by a.submittedAt desc")
        List<Answer> findTop20WithQuestionByStudent(@Param("student") Student student, Pageable pageable);

        @Query("select a from Answer a join fetch a.question q where a.student.id = :studentId order by a.submittedAt desc")
        List<Answer> findAllWithQuestionByStudentIdOrderBySubmittedAtDesc(@Param("studentId") Long studentId,
                        Pageable pageable);

        @Query("""
                        select a from Answer a
                        join fetch a.question q
                        where a.student.id = :studentId and a.evaluationStatus in :statuses
                        order by a.submittedAt asc
                        """)
        List<Answer> findAllWithQuestionByStudentIdAndEvaluationStatusInOrderBySubmittedAtAsc(
                        @Param("studentId") Long studentId,
                        @Param("statuses") List<String> statuses);

        long deleteByStudentId(Long studentId);

        @org.springframework.data.jpa.repository.Modifying
        @Query("DELETE FROM Answer a WHERE a.question.id IN (SELECT q.id FROM Question q WHERE q.student.id = :studentId)")
        void deleteByQuestionStudentId(@Param("studentId") Long studentId);

}
