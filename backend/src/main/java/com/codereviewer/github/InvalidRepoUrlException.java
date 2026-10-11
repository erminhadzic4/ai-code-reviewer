package com.codereviewer.github;

import com.codereviewer.common.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidRepoUrlException extends ApiException {

	public InvalidRepoUrlException() {
		super(HttpStatus.BAD_REQUEST,
				"Enter a GitHub repository URL in the form https://github.com/owner/repository");
	}

}
