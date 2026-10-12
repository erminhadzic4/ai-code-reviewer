package com.codereviewer.provider;

/** The providers that can be selected with {@code REVIEW_PROVIDER}. */
public enum ProviderType {

	/** Fixed findings, no network and no API key. */
	FAKE,

	/** A Gemini model; needs {@code GEMINI_API_KEY}. */
	GEMINI

}
