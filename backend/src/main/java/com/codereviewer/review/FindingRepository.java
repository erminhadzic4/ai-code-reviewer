package com.codereviewer.review;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FindingRepository extends JpaRepository<Finding, Long> {

	/** The findings of an analysis in the order they were stored, which is the order of the files. */
	List<Finding> findByAnalysisIdOrderById(Long analysisId);

}
