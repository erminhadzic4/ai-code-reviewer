package com.codereviewer.review;

import java.net.URI;
import java.util.UUID;

import com.codereviewer.auth.AuthenticatedUser;
import com.codereviewer.review.dto.PageResponse;
import com.codereviewer.review.dto.ReviewResultResponse;
import com.codereviewer.review.dto.ReviewStatusResponse;
import com.codereviewer.review.dto.ReviewSummaryResponse;
import com.codereviewer.review.dto.SubmitReviewRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Reviews")
public class ReviewController {

	private final ReviewService reviewService;

	public ReviewController(ReviewService reviewService) {
		this.reviewService = reviewService;
	}

	// 202 and not 201: the review is accepted, not done. The address to follow it is in Location.
	@PostMapping
	@Operation(summary = "Submit a public GitHub repository for review")
	@ApiResponse(responseCode = "202", description = "Queued; follow the address in Location")
	@ApiResponse(responseCode = "400", description = "Not a GitHub repository URL, or empty")
	@ApiResponse(responseCode = "401", description = "No valid token")
	ResponseEntity<ReviewStatusResponse> submit(@AuthenticationPrincipal AuthenticatedUser principal,
			@Valid @RequestBody SubmitReviewRequest request) {
		ReviewStatusResponse review = reviewService.submit(principal, request);
		return ResponseEntity.accepted().location(URI.create("/api/reviews/" + review.id())).body(review);
	}

	@GetMapping
	@Operation(summary = "The user's reviews, newest first",
			description = "page starts at 0; size is 20 by default and lowered to 50 when larger")
	@ApiResponse(responseCode = "200", description = "One page of reviews")
	@ApiResponse(responseCode = "400", description = "Page below 0 or size below 1")
	@ApiResponse(responseCode = "401", description = "No valid token")
	PageResponse<ReviewSummaryResponse> history(@AuthenticationPrincipal AuthenticatedUser principal,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return reviewService.history(principal, page, size);
	}

	@GetMapping("/{id}")
	@Operation(summary = "Status of one review, polled while it runs")
	@ApiResponse(responseCode = "200", description = "The status")
	@ApiResponse(responseCode = "400", description = "The id is not a UUID")
	@ApiResponse(responseCode = "401", description = "No valid token")
	@ApiResponse(responseCode = "404", description = "No such review for this user")
	ReviewStatusResponse status(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID id) {
		return reviewService.status(principal, id);
	}

	@GetMapping("/{id}/result")
	@Operation(summary = "Report of a completed review")
	@ApiResponse(responseCode = "200", description = "Scores, counts, stats and findings")
	@ApiResponse(responseCode = "400", description = "The id is not a UUID")
	@ApiResponse(responseCode = "401", description = "No valid token")
	@ApiResponse(responseCode = "404", description = "No such review for this user")
	@ApiResponse(responseCode = "409", description = "The review is not completed")
	ReviewResultResponse result(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID id) {
		return reviewService.result(principal, id);
	}

}
