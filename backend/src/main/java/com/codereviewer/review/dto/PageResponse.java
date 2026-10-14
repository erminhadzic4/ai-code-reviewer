package com.codereviewer.review.dto;

import java.util.List;

/**
 * One page of a list. Spring's own {@code Page} is not returned: its JSON shape is not
 * meant to be stable.
 *
 * @param page the number of this page, starting at 0
 * @param size the largest number of items a page holds
 * @param totalItems the number of items on all pages together
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems) {

	public PageResponse {
		items = List.copyOf(items);
	}

}
