package com.codereviewer.review;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewRepository extends JpaRepository<Review, UUID> {

	/**
	 * The queued review that has waited longest and is due, locked until the transaction
	 * ends. A row that another transaction holds is skipped instead of waited for, so two
	 * workers can never take the same review. Must be called inside a transaction.
	 */
	@Query(value = """
			SELECT * FROM reviews
			WHERE status = 'QUEUED' AND run_after <= :now
			ORDER BY run_after, created_at
			LIMIT 1
			FOR UPDATE SKIP LOCKED""", nativeQuery = true)
	Optional<Review> findNextDue(@Param("now") Instant now);

	List<Review> findByStatus(ReviewStatus status);

}
