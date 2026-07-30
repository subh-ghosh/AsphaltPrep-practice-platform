package com.practice.aiplatform.gamification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DailyXpHistoryRepository extends JpaRepository<DailyXpHistory, Long> {
    Optional<DailyXpHistory> findByStudentIdAndDate(Long studentId, LocalDate date);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE DailyXpHistory d SET d.xpEarned = d.xpEarned + :amount WHERE d.student.id = :studentId AND d.date = :date")
    int incrementXp(@org.springframework.data.repository.query.Param("studentId") Long studentId, @org.springframework.data.repository.query.Param("date") LocalDate date, @org.springframework.data.repository.query.Param("amount") int amount);

    List<DailyXpHistory> findByStudentIdAndDateBetweenOrderByDateAsc(Long studentId, LocalDate startDate,
            LocalDate endDate);

    long deleteByStudentId(Long studentId);
}
