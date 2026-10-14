package com.codereviewer.review;

import java.net.URI;
import java.util.UUID;

import com.codereviewer.auth.AuthenticatedUser;
import com.codereviewer.review.dto.PageResponse;
import com.codereviewer.review.dto.ReviewResultResponse;
import com.codereviewer.review.dto.ReviewStatusResponse;
import com.codereviewer.review.dto.ReviewSummaryResponse;
import com.codereviewer.review.dto.SubmitReviewRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

	private final ReviewService reviewService;

	public ReviewController(ReviewService reviewService) {
		this.reviewService = reviewService;
	}

	// 202 and not 201: the review is accepted, not done. The address to follow it is in Location.
	@PostMapping
	ResponseEntity<ReviewStatusResponse> submit(@AuthenticationPrincipal AuthenticatedUser principal,
			@Valid @RequestBody SubmitReviewRequest request) {
		ReviewStatusResponse review = reviewService.submit(principal, request);
		return ResponseEntity.accepted().location(URI.create("/api/reviews/" + review.id())).body(review);
	}

	@GetMapping
	PageResponse<ReviewSummaryResponse> history(@AuthenticationPrincipal AuthenticatedUser principal,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return reviewService.history(principal, page, size);
	}

	@GetMapping("/{id}")
	ReviewStatusResponse status(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID id) {
		return reviewService.status(principal, id);
	}

	@GetMapping("/{id}/result")
	ReviewResultResponse result(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID id) {
		return reviewService.result(principal, id);
	}

}
