package com.codereviewer.github;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A stand-in for GitHub: a real HTTP server on the loopback interface, built on the JDK's
 * own server. Tests program the answer for each path and inspect what was received, so the
 * client's real HTTP code runs (redirects, timeouts, streaming) without touching the network.
 */
final class FakeGitHub implements AutoCloseable {

	private final HttpServer server;
	private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
		Thread thread = new Thread(runnable, "fake-github");
		thread.setDaemon(true);
		return thread;
	});
	private final Map<String, Response> responses = new ConcurrentHashMap<>();
	private final List<Request> requests = new CopyOnWriteArrayList<>();

	FakeGitHub() {
		try {
			server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		server.createContext("/", this::handle);
		server.setExecutor(executor);
		server.start();
	}

	URI origin() {
		return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
	}

	/** Programs the answer for a path, given exactly as it appears on the wire. */
	FakeGitHub on(String rawPath, Response response) {
		responses.put(rawPath, response);
		return this;
	}

	List<Request> requests() {
		return List.copyOf(requests);
	}

	List<String> requestedPaths() {
		return requests.stream().map(Request::rawPath).toList();
	}

	@Override
	public void close() {
		server.stop(0);
		executor.shutdownNow();
	}

	private void handle(HttpExchange exchange) throws IOException {
		URI uri = exchange.getRequestURI();
		Map<String, String> headers = new LinkedHashMap<>();
		exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values.get(0)));
		requests.add(new Request(uri.getRawPath(), uri.getRawQuery(), headers));

		Response response = responses.getOrDefault(uri.getRawPath(), Response.json(404, "{\"message\":\"Not Found\"}"));
		try {
			Thread.sleep(response.delay());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			exchange.close();
			return;
		}
		response.headers().forEach(exchange.getResponseHeaders()::set);
		// A length of 0 means "unknown" to this server, -1 means "no body".
		exchange.sendResponseHeaders(response.status(), response.body().length == 0 ? -1 : response.body().length);
		try (OutputStream body = exchange.getResponseBody()) {
			body.write(response.body());
		}
		catch (IOException ex) {
			// The client gave up, which some tests do on purpose.
		}
	}

	/** One received request. Header names are lower-case. */
	record Request(String rawPath, String rawQuery, Map<String, String> headers) {

		String header(String name) {
			return headers.get(name.toLowerCase());
		}

	}

	record Response(int status, Map<String, String> headers, byte[] body, Duration delay) {

		static Response json(int status, String body) {
			return new Response(status, Map.of("Content-Type", "application/json; charset=utf-8"),
					body.getBytes(StandardCharsets.UTF_8), Duration.ZERO);
		}

		static Response text(int status, String body) {
			return new Response(status, Map.of("Content-Type", "text/plain; charset=utf-8"),
					body.getBytes(StandardCharsets.UTF_8), Duration.ZERO);
		}

		static Response bytes(int status, String contentType, byte[] body) {
			return new Response(status, Map.of("Content-Type", contentType), body, Duration.ZERO);
		}

		static Response status(int status) {
			return new Response(status, Map.of(), new byte[0], Duration.ZERO);
		}

		static Response redirect(int status, String location) {
			return status(status).header("Location", location);
		}

		Response header(String name, String value) {
			Map<String, String> all = new LinkedHashMap<>(headers);
			all.put(name, value);
			return new Response(status, all, body, delay);
		}

		Response after(Duration delay) {
			return new Response(status, headers, body, delay);
		}

	}

}
