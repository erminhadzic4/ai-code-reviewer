package com.codereviewer.review;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnalysisRepository extends JpaRepository<Analysis, Long> {

	/** The analysis of a commit, if one exists for this provider, model and prompt version. */
	@Query("""
			SELECT a FROM Analysis a
			WHERE a.repoOwner = :owner AND a.repoName = :name AND a.commitSha = :commitSha
				AND a.provider = :provider AND a.model = :model AND a.promptVersion = :promptVersion""")
	Optional<Analysis> findByCacheKey(@Param("owner") String owner, @Param("name") String name,
			@Param("commitSha") String commitSha, @Param("provider") String provider, @Param("model") String model,
			@Param("promptVersion") int promptVersion);

}
