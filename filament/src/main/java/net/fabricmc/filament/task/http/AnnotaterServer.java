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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import net.fabricmc.filament.util.JSpecifyRecordTypeAdapterFactory;
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
		String unpickDir,
		String runtimeLibrariesDir,
		String minecraftVersion,
		String host,
		int port,
		String clientUrl,
		boolean openBrowser,
		boolean trace
) {
	static final String PROP_KEY = "annotater.props";
	static final Gson GSON = new GsonBuilder()
			.registerTypeAdapterFactory(new JSpecifyRecordTypeAdapterFactory())
			.create();

	static void main() throws IOException {
		AnnotaterServer server = GSON.fromJson(System.getProperty(PROP_KEY), AnnotaterServer.class);
		server.runServer();
	}

	// region Routes

	private @NonNull Map<Route, RouteHandler> getRoutes() {
		Map<Route, RouteHandler> routes = new HashMap<>();
		routes.put(new Route("GET", "/ping"), this::ping);
		routes.put(new Route("GET", "/config"), this::getConfig);
		routes.put(new Route("GET", "/mapping"), this::getMapping);
		routes.put(new Route("POST", "/mapping"), this::updateMapping);
		routes.put(new Route("GET", "/unpick/list"), this::listUnpick);
		routes.put(new Route("GET", "/unpick"), this::getUnpick);
		routes.put(new Route("POST", "/unpick"), this::updateUnpick);
		routes.put(new Route("DELETE", "/unpick"), this::deleteUnpick);
		routes.put(new Route("GET", "/library"), this::getRuntimeLibrary);
		return routes;
	}

	/// Indicate that the server still alive
	private void ping(HttpExchange exchange) throws IOException {
		exchange.sendResponseHeaders(204, -1);
		exchange.close();
	}

	/// Get the config
	private void getConfig(HttpExchange exchange) throws IOException {
		Path baseDir = Path.of(runtimeLibrariesDir).toAbsolutePath().normalize();
		List<String> runtimeLibraries;

		try (Stream<Path> stream = Files.walk(baseDir)) {
			runtimeLibraries = stream
					.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().endsWith(".jar"))
					.map(p -> relativePath(baseDir, p))
					.toList();
		}

		sendResponse(exchange, 200, new Config(minecraftVersion, runtimeLibraries));
	}

	/// Get Enigma formatted mapping of the target class.
	private void getMapping(HttpExchange exchange) throws IOException {
		String className = exchange.getRequestURI().getQuery();
		require(className != null && !className.isEmpty(), 400, "Missing class name query");
		Path file = getMappingFile(className);
		require(Files.isRegularFile(file), 404, "Mapping file not found");

		sendFileResponse(exchange, file, "text/plain; charset=utf-8");
	}

	/// Update mapping for a select member of the target class.
	private void updateMapping(HttpExchange exchange) throws IOException {
		UpdateMapping body = parseBodyOrError(exchange, UpdateMapping.class);
		Path file = getMappingFile(body.className());

		MemoryMappingTree tree = new MemoryMappingTree();
		String className = body.className().replace('.', '/');

		if (Files.exists(file)) {
			require(Files.isRegularFile(file), 400, "Tried to edit directory");
			MappingReader.read(file, MappingFormat.ENIGMA_FILE, tree);
		} else {
			tree.visitNamespaces(MappingUtil.NS_SOURCE_FALLBACK, List.of(MappingUtil.NS_TARGET_FALLBACK));
		}

		tree.visitClass(className);

		if (body.kind() == Kind.CLASS) {
			tree.visitComment(MappedElementKind.CLASS, body.comments());
		} else {
			require(body.name() != null, 400, "Missing mapping target name");
			require(body.desc() != null, 400, "Missing mapping target desc");

			switch (body.kind()) {
			case METHOD -> {
				tree.visitMethod(body.name(), body.desc());
				tree.visitComment(MappedElementKind.METHOD, body.comments());
			}
			case FIELD -> {
				tree.visitField(body.name(), body.desc());
				tree.visitComment(MappedElementKind.FIELD, body.comments());
			}
			}
		}

		Files.createDirectories(file.getParent());

		try (MappingWriter writer = MappingWriter.create(file, MappingFormat.ENIGMA_FILE)) {
			tree.accept(new EmptyElementFilter(writer), VisitOrder.createByName());
		}

		sendResponse(exchange, 200, new Details("Mapping updated"));
	}

	/// Get list of unpick file path and its modified time
	private void listUnpick(HttpExchange exchange) throws IOException {
		Path baseDir = Path.of(unpickDir).toAbsolutePath().normalize();
		List<Path> files;

		try (Stream<Path> stream = Files.walk(baseDir)) {
			files = stream
					.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().endsWith(".unpick"))
					.toList();
		}

		List<UnpickMetadata> metadata = new ArrayList<>(files.size());

		for (Path file : files) {
			metadata.add(new UnpickMetadata(
					relativePath(baseDir, file),
					Files.getLastModifiedTime(file).toMillis()
			));
		}

		this.sendResponse(exchange, 200, new ListBody<>(metadata));
	}

	/// Get the content of an unpick file from a file path
	private void getUnpick(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getQuery();
		require(path != null && !path.isEmpty(), 400, "Missing path query");
		require(path.endsWith(".unpick"), 400, "Invalid path");

		Path file = getFilePath(unpickDir, path);
		require(Files.isRegularFile(file), 404, "File not found");

		sendFileResponse(exchange, file, "text/plain; charset=utf-8");
	}

	/// Update or create an unpick file
	private void updateUnpick(HttpExchange exchange) throws IOException {
		UpdateUnpick body = parseBodyOrError(exchange, UpdateUnpick.class);
		require(body.path().endsWith(".unpick"), 400, "Invalid path");
		Path file = getFilePath(unpickDir, body.path());

		if (Files.exists(file)) {
			require(Files.isRegularFile(file), 400, "Tried to edit directory");
		}

		Files.writeString(file, body.content());
		sendResponse(exchange, 200, new Details("Unpick updated"));
	}

	/// Delete an unpick file
	private void deleteUnpick(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getQuery();
		require(path != null && !path.isEmpty(), 400, "Missing path query");
		require(path.endsWith(".unpick"), 400, "Invalid path");

		Path file = getFilePath(unpickDir, path);
		require(Files.isRegularFile(file), 404, "File not found");

		Files.delete(file);
		sendResponse(exchange, 200, new Details("Unpick deleted"));
	}

	/// Get runtime library jar
	private void getRuntimeLibrary(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getQuery();
		require(path != null && !path.isEmpty(), 400, "Missing path query");
		require(path.endsWith(".jar"), 400, "Invalid path");

		Path file = getFilePath(runtimeLibrariesDir, path);
		require(Files.isRegularFile(file), 404, "File not found");

		sendFileResponse(exchange, file, "application/java-archive");
	}

	// endregion
	// region Server Initialization

	HttpServer createServer() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
		String client = baseClientUrl();
		String clientOrigin = getOrigin(client);
		Map<Route, RouteHandler> routes = getRoutes();
		Set<String> paths = routes.keySet().stream().map(Route::path).collect(Collectors.toSet());

		server.createContext("/", exchange -> {
			String traceLine = "%s %s".formatted(exchange.getRequestMethod(), exchange.getRequestURI());
			if (trace) IO.println("-->     " + traceLine);

			try {
				Headers headers = exchange.getResponseHeaders();
				headers.set("Vary", "Origin");

				String requestOrigin = getOrigin(exchange.getRequestHeaders().getFirst("Origin"));
				require(clientOrigin.equalsIgnoreCase(requestOrigin), 403, "Invalid request origin");

				headers.set("Access-Control-Allow-Origin", client);
				headers.set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
				headers.set("Access-Control-Allow-Headers", "Content-Type");
				headers.set("Access-Control-Allow-Private-Network", "true");

				String path = exchange.getRequestURI().getPath().toLowerCase(Locale.ROOT);
				require(paths.contains(path), 404, "Unknown route");
				String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);

				if ("OPTIONS".equals(method)) {
					exchange.sendResponseHeaders(204, -1);
					exchange.close();
					return;
				}

				RouteHandler handler = routes.get(new Route(method, path));
				require(handler != null, 404, "Unknown route");
				handler.handle(exchange);
			} catch (HttpError e) {
				sendResponse(exchange, e.status, new Details(e.getMessage()));
			} catch (Exception e) {
				sendResponse(exchange, 500, new Details("Server error: " + e.getMessage()));
			} finally {
				if (trace) IO.println("<-- %d %s".formatted(exchange.getResponseCode(), traceLine));
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

	// endregion
	// region Utilities

	private String baseClientUrl() {
		return clientUrl.endsWith("/") ? clientUrl.substring(0, clientUrl.length() - 1) : clientUrl;
	}

	@Contract("!null -> !null")
	@Nullable
	private String getOrigin(@Nullable String url) {
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

	private <T extends Body> T parseBodyOrError(HttpExchange exchange, Class<T> clazz) throws IOException {
		try (InputStreamReader in = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
			T parsed = GSON.fromJson(in, clazz);
			require(parsed != null, 400, "Empty body");
			return parsed;
		} catch (JsonParseException e) {
			throw new HttpError(400, "Invalid body format: " + e.getMessage());
		}
	}

	private Path getFilePath(String parent, String path) {
		Path baseDir = Path.of(parent).toAbsolutePath().normalize();
		Path file = baseDir.resolve(path).normalize();
		require(file.startsWith(baseDir), 400, "Invalid path");
		return file;
	}

	private Path getMappingFile(String className) {
		String path = className.replace('.', '/');
		int dollar = path.indexOf('$');

		if (dollar != -1) {
			path = path.substring(0, dollar);
		}

		require(!path.isEmpty(), 400, "Invalid class name");
		return getFilePath(mappingsDir, path + ".mapping");
	}

	private String relativePath(Path baseDir, Path file) {
		return baseDir.relativize(file).toString().replace('\\', '/');
	}

	private void sendResponse(HttpExchange exchange, int statusCode, Body body) throws IOException {
		byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(statusCode, bytes.length);

		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private void sendFileResponse(HttpExchange exchange, Path file, String contentType) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.sendResponseHeaders(200, Files.size(file));

		try (OutputStream out = exchange.getResponseBody()) {
			Files.copy(file, out);
		}
	}

	private record Route(String method, String path) {
		Route {
			method = method.toUpperCase(Locale.ROOT);
			path = path.toLowerCase(Locale.ROOT);
		}
	}

	private interface RouteHandler {
		void handle(HttpExchange consumer) throws Exception;
	}

	// endregion
	// region Body

	private interface Body {
	}

	static class HttpError extends Error {
		final int status;

		HttpError(int status, String message) {
			super(message);
			this.status = status;
		}
	}

	private record ListBody<T extends Body>(List<T> entries) implements Body {
	}

	private record Config(String version, List<String> runtimeLibraries) implements Body {
	}

	private record Details(String details) implements Body {
	}

	private record UpdateMapping(
			String className,
			Kind kind,
			@Nullable String name,
			@Nullable String desc,
			String comments
	) implements Body {
	}

	private record UnpickMetadata(String path, long modifiedTime) implements Body {
	}

	private record UpdateUnpick(String path, String content) implements Body {
	}

	private enum Kind {
		CLASS, METHOD, FIELD
	}
}
