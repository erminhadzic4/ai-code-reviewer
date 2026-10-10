package com.codereviewer.auth;

/**
 * The principal of a request that carried a valid access token. Holds only what the token
 * holds; anything else about the user is loaded from the database when needed.
 */
public record AuthenticatedUser(long id) {
}
