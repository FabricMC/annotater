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
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import net.fabricmc.mappingio.MappedElementKind;
import net.fabricmc.mappingio.MappingReader;
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

	private void runServer() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
		String client = clientUrl.endsWith("/") ? clientUrl.substring(0, clientUrl.length() - 1) : clientUrl;

		Map<String, Route> routes = new HashMap<>();
		routes.put("/config", this::getConfig);
		routes.put("/get-mapping", this::getMapping);
		routes.put("/update-mapping", this::updateMapping);

		server.createContext("/", exchange -> {
			Headers headers = exchange.getResponseHeaders();
			headers.set("Access-Control-Allow-Origin", client);
			headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
			headers.set("Access-Control-Allow-Headers", "Content-Type");

			String path = exchange.getRequestURI().getPath().toLowerCase(Locale.ROOT);

			if (!routes.containsKey(path)) {
				sendResponse(exchange, 404, new Details("Unknown route"));
				return;
			}

			String method = exchange.getRequestMethod();

			if ("OPTIONS".equalsIgnoreCase(method)) {
				exchange.sendResponseHeaders(204, -1);
				exchange.close();
				return;
			}

			try {
				routes.get(path).handle(exchange);
			} catch (HttpError e) {
				sendResponse(exchange, e.status, new Details(e.getMessage()));
			} catch (Exception e) {
				sendResponse(exchange, 500, new Details("Server error: " + e.getMessage()));
			}
		});

		server.setExecutor(null);
		server.start();
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

	private void requireMethod(HttpExchange exchange, String method) {
		if (!method.equalsIgnoreCase(exchange.getRequestMethod())) {
			throw new HttpError(404, "Unknown route");
		}
	}

	private <T extends Body> T parseBodyOrError(HttpExchange exchange, Class<T> clazz) throws IOException {
		try (InputStreamReader in = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
			T parsed = GSON.fromJson(in, clazz);

			if (parsed == null) {
				throw new HttpError(400, "Empty body");
			}

			return parsed;
		} catch (JsonSyntaxException e) {
			throw new HttpError(400, "Invalid body format:" + e.getMessage());
		}
	}

	private Path getMappingFile(String className) {
		Path baseDir = Path.of(mappingsDir).toAbsolutePath();
		Path file = baseDir.resolve(className.replace('.', '/') + ".mapping").normalize();

		if (!file.startsWith(baseDir)) {
			throw new HttpError(400, "Invalid class name");
		}

		return file;
	}

	private Path getMappingFileOrError(String className) {
		Path file = getMappingFile(className);

		if (!Files.isRegularFile(file)) {
			throw new HttpError(404, "Mapping file not found");
		}

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

	private interface Body {
	}

	private static class HttpError extends Error {
		private final int status;

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
