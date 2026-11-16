package net.fabricmc.filament.task.http;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.Nullable;

import net.fabricmc.mappingio.MappedElementKind;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.MappingUtil;
import net.fabricmc.mappingio.MappingWriter;
import net.fabricmc.mappingio.adapter.EmptyElementFilter;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.mappingio.tree.VisitOrder;

public record AnnotaterServer(
		String mappingsDir,
		String minecraftVersion,
		String host,
		int port,
		String clientUrl,
		boolean openBrowser
) {
	static final String PROP_KEY = "annotater.props";
	static final Gson GSON = new Gson();

	static void main() throws IOException {
		AnnotaterServer server = GSON.fromJson(System.getProperty(PROP_KEY), AnnotaterServer.class);
		server.runServer();
	}

	private void ping(HttpExchange exchange) throws IOException {
		exchange.sendResponseHeaders(204, -1);
		exchange.close();
	}

	/// Get the config
	private void getConfig(HttpExchange exchange) throws IOException {
		requireMethod(exchange, "GET");
		sendResponse(exchange, 200, new Config(minecraftVersion));
	}

	/// Get Enigma formatted mapping of the target class.
	private void getMapping(HttpExchange exchange) throws IOException {
		requireMethod(exchange, "POST");
		GetMapping body = parseBodyOrError(exchange, GetMapping.class);
		Path file = getMappingFileOrError(body.className());

		exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
		exchange.sendResponseHeaders(200, Files.size(file));

		try (OutputStream out = exchange.getResponseBody()) {
			Files.copy(file, out);
		}
	}

	/// Update mapping for a select member of the target class.
	private void updateMapping(HttpExchange exchange) throws IOException {
		requireMethod(exchange, "POST");
		UpdateMapping body = parseBodyOrError(exchange, UpdateMapping.class);
		Path file = getMappingFile(body.className());

		MemoryMappingTree tree = new MemoryMappingTree();
		String className = body.className().replace('.', '/');

		if (Files.exists(file)) {
			MappingReader.read(file, MappingFormat.ENIGMA_FILE, tree);
		} else {
			tree.visitNamespaces(MappingUtil.NS_SOURCE_FALLBACK, List.of(MappingUtil.NS_TARGET_FALLBACK));
		}

		tree.visitClass(className);

		switch (body.kind()) {
		case CLASS -> {
			tree.visitComment(MappedElementKind.CLASS, body.comments());
		}
		case METHOD -> {
			tree.visitMethod(body.name(), body.desc());
			tree.visitComment(MappedElementKind.METHOD, body.comments());
		}
		case FIELD -> {
			tree.visitField(body.name(), body.desc());
			tree.visitComment(MappedElementKind.FIELD, body.comments());
		}
		}

		Files.createDirectories(file.getParent());

		try (MappingWriter writer = MappingWriter.create(file, MappingFormat.ENIGMA_FILE)) {
			tree.accept(new EmptyElementFilter(writer), VisitOrder.createByName());
		}

		sendResponse(exchange, 200, new Details("Mapping updated"));
	}

	HttpServer createServer() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
		String client = baseClientUrl();
		String clientOrigin = getOrigin(client);

		Map<String, Route> routes = new HashMap<>();
		routes.put("/ping", this::ping);
		routes.put("/config", this::getConfig);
		routes.put("/get-mapping", this::getMapping);
		routes.put("/update-mapping", this::updateMapping);

		server.createContext("/", exchange -> {
			try {
				Headers headers = exchange.getResponseHeaders();
				headers.set("Vary", "Origin");

				String requestOrigin = getOrigin(exchange.getRequestHeaders().getFirst("Origin"));
				require(clientOrigin.equalsIgnoreCase(requestOrigin), 403, "Invalid request origin");

				headers.set("Access-Control-Allow-Origin", client);
				headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
				headers.set("Access-Control-Allow-Headers", "Content-Type");
				headers.set("Access-Control-Allow-Private-Network", "true");

				String path = exchange.getRequestURI().getPath().toLowerCase(Locale.ROOT);
				require(routes.containsKey(path), 404, "Unknown route");

				String method = exchange.getRequestMethod();

				if ("OPTIONS".equalsIgnoreCase(method)) {
					exchange.sendResponseHeaders(204, -1);
					exchange.close();
					return;
				}

				routes.get(path).handle(exchange);
			} catch (HttpError e) {
				sendResponse(exchange, e.status, new Details(e.getMessage()));
			} catch (Exception e) {
				sendResponse(exchange, 500, new Details("Server error: " + e.getMessage()));
			}
		});

		server.setExecutor(null);
		return server;
	}

	private void runServer() throws IOException {
		HttpServer server = createServer();
		server.start();
		String client = baseClientUrl();
		String clientUrl = "%s/?annotater-server=http://%s:%s"
				.formatted(client, server.getAddress().getHostName(), server.getAddress().getPort());
		IO.println("""
				Run `gradlew help --task serve` to see available options
				Server started at http://%s:%s
				Connect on %s
				Ctrl+C to stop"""
				.formatted(server.getAddress().getHostName(), server.getAddress().getPort(), clientUrl));

		if (openBrowser) {
			try {
				if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
					Desktop.getDesktop().browse(URI.create(clientUrl));
					IO.println("Browser opened");
				} else {
					IO.println("Unable to open browser, is system headless?");
				}
			} catch (IOException ignored) {
				// no-op
			}
		}
	}

	private String baseClientUrl() {
		return clientUrl.endsWith("/") ? clientUrl.substring(0, clientUrl.length() - 1) : clientUrl;
	}

	@Contract("!null -> !null")
	@Nullable String getOrigin(@Nullable String url) {
		if (url == null) return null;

		URI uri;

		try {
			uri = URI.create(url);
		} catch (IllegalArgumentException e) {
			throw new HttpError(403, "Invalid request origin");
		}

		String scheme = uri.getScheme();
		String host = uri.getHost();

		if (scheme == null || host == null) {
			throw new HttpError(403, "Invalid request origin");
		}

		scheme = scheme.toLowerCase(Locale.ROOT);
		int port = uri.getPort();

		if (port == -1) {
			switch (scheme) {
			case "http" -> port = 80;
			case "https" -> port = 443;
			default -> throw new HttpError(403, "Invalid request origin");
			}
		}

		return scheme + "://" + host.toLowerCase(Locale.ROOT) + ":" + port;
	}

	private void require(boolean predicate, int errorCode, String details) {
		if (!predicate) throw new HttpError(errorCode, details);
	}

	private void requireMethod(HttpExchange exchange, String method) {
		require(method.equalsIgnoreCase(exchange.getRequestMethod()), 404, "Unknown route");
	}

	private <T extends Body> T parseBodyOrError(HttpExchange exchange, Class<T> clazz) throws IOException {
		try (InputStreamReader in = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
			T parsed = GSON.fromJson(in, clazz);
			require(parsed != null, 400, "Empty body");
			return parsed;
		} catch (JsonSyntaxException e) {
			throw new HttpError(400, "Invalid body format:" + e.getMessage());
		}
	}

	Path getMappingFile(String className) {
		String path = className.replace('.', '/');
		int dollar = path.indexOf('$');

		if (dollar != -1) {
			path = path.substring(0, dollar);
		}

		require(!path.isEmpty(), 404, "Invalid class name");
		Path baseDir = Path.of(mappingsDir).toAbsolutePath();
		Path file = baseDir.resolve(path + ".mapping").normalize();
		require(file.startsWith(baseDir), 400, "Invalid class name");
		return file;
	}

	private Path getMappingFileOrError(String className) {
		Path file = getMappingFile(className);
		require(Files.isRegularFile(file), 404, "Mapping file not found");
		return file;
	}

	private void sendResponse(HttpExchange exchange, int statusCode, Body body) throws IOException {
		byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(statusCode, bytes.length);

		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private interface Route {
		void handle(HttpExchange consumer) throws Exception;
	}

	interface Body {
	}

	static class HttpError extends Error {
		final int status;

		HttpError(int status, String message) {
			super(message);
			this.status = status;
		}
	}

	private record Config(String version) implements Body {
	}

	private record Details(String details) implements Body {
	}

	private record GetMapping(String className) implements Body {
	}

	private record UpdateMapping(
			String className, Kind kind, String name, String desc, String comments
	) implements Body {
	}

	private enum Kind {
		CLASS, METHOD, FIELD
	}
}
