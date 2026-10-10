package com.codereviewer.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();

	@Test
	void invalidFieldsAreReportedOneByOne() throws Exception {
		mockMvc.perform(post("/test/items").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\",\"code\":\"toolong\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.title").value("Bad Request"))
				.andExpect(jsonPath("$.detail").value("Validation failed"))
				.andExpect(jsonPath("$.errors.length()").value(2))
				.andExpect(jsonPath("$.errors[0].field").value("code"))
				.andExpect(jsonPath("$.errors[0].message").value("size must be between 0 and 3"))
				.andExpect(jsonPath("$.errors[1].field").value("name"))
				.andExpect(jsonPath("$.errors[1].message").value("must not be blank"));
	}

	@Test
	void missingFieldIsReportedAsAValidationError() throws Exception {
		mockMvc.perform(post("/test/items").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.length()").value(1))
				.andExpect(jsonPath("$.errors[0].field").value("name"));
	}

	@Test
	void malformedJsonIsABadRequest() throws Exception {
		mockMvc.perform(post("/test/items").contentType(MediaType.APPLICATION_JSON).content("{not json"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.detail").value("Malformed request body"))
				.andExpect(jsonPath("$.errors").doesNotExist());
	}

	@Test
	void apiExceptionKeepsItsStatusAndMessage() throws Exception {
		mockMvc.perform(get("/test/conflict"))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.title").value("Conflict"))
				.andExpect(jsonPath("$.detail").value("Already there"));
	}

	@Test
	void unexpectedExceptionIsLoggedButItsMessageIsNotReturned(CapturedOutput output) throws Exception {
		mockMvc.perform(get("/test/failure"))
				.andExpect(status().isInternalServerError())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
				.andExpect(content().string(not(containsString("secret internal detail"))));

		assertThat(output).contains("Unhandled exception")
				.contains("java.lang.IllegalStateException: secret internal detail");
	}

	@Test
	void frameworkErrorsAreProblemDetailsToo() throws Exception {
		mockMvc.perform(get("/test/items"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(405));
	}

	record Item(@NotBlank String name, @Size(max = 3) String code) {
	}

	static class TestConflictException extends ApiException {

		TestConflictException() {
			super(HttpStatus.CONFLICT, "Already there");
		}

	}

	@RestController
	static class TestController {

		@PostMapping("/test/items")
		Item create(@Valid @RequestBody Item item) {
			return item;
		}

		@GetMapping("/test/conflict")
		void conflict() {
			throw new TestConflictException();
		}

		@GetMapping("/test/failure")
		void failure() {
			throw new IllegalStateException("secret internal detail");
		}

	}

}
