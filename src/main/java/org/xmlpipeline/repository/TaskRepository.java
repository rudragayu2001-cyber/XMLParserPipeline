package org.xmlpipeline.repository;

import org.xmlpipeline.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface TaskRepository extends JpaRepository<Task, UUID> {

    List<Task> findByJobId(UUID jobId);

    List<Task> findByJobIdAndStatus(UUID jobId, String status);

    /**
     * Returns rows of [status, count] for a given job.
     * Used to compute job-level counters dynamically (avoids stale atomic counters).
     */
    @Query("SELECT t.status, COUNT(t) FROM Task t WHERE t.job.id = :jobId GROUP BY t.status")
    List<Object[]> countByJobIdGroupByStatus(@Param("jobId") UUID jobId);

    @Modifying
    @Query("UPDATE Task t SET t.status = :status, t.startedAt = :startedAt WHERE t.id = :id")
    void markInProgress(@Param("id") UUID id,
                        @Param("status") String status,
                        @Param("startedAt") LocalDateTime startedAt);

    @Modifying
    @Query("UPDATE Task t SET t.status = :status, t.recordsExtracted = :count, t.completedAt = :completedAt WHERE t.id = :id")
    void markCompleted(@Param("id") UUID id,
                       @Param("status") String status,
                       @Param("count") int count,
                       @Param("completedAt") LocalDateTime completedAt);

    @Modifying
    @Query("UPDATE Task t SET t.status = 'failed', t.error = :error, t.completedAt = :completedAt WHERE t.id = :id")
    void markFailed(@Param("id") UUID id,
                    @Param("error") String error,
                    @Param("completedAt") LocalDateTime completedAt);

    // Used by job finaliser to count terminal states
    long countByJobIdAndStatus(UUID jobId, String status);
}
