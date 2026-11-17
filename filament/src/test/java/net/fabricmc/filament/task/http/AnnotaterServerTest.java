package net.fabricmc.filament.task.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

class AnnotaterServerTest {
	private static final String CLIENT = "https://mcsrc.dev";
	private static final String VERSION = "1.21.5";
	private static final HttpClient HTTP = HttpClient.newHttpClient();

	@TempDir
	Path mappingsDir;

	private AnnotaterServer server;
	private HttpServer httpServer;
	private URI baseUri;

	@BeforeEach
	void setUp() throws IOException {
		server = new AnnotaterServer(mappingsDir.toString(), VERSION, "127.0.0.1", 0, CLIENT, false);
		httpServer = server.createServer();
		httpServer.start();
		baseUri = URI.create("http://127.0.0.1:" + httpServer.getAddress().getPort());
	}

	@AfterEach
	void tearDown() {
		httpServer.stop(0);
	}

	@Test
	void getOriginAddsDefaultPorts() {
		assertEquals("https://mcsrc.dev:443", server.getOrigin("https://mcsrc.dev"));
		assertEquals("https://mcsrc.dev:443", server.getOrigin("https://mcsrc.dev:443"));
		assertEquals("http://localhost:80", server.getOrigin("http://localhost"));
		assertEquals("http://localhost:8000", server.getOrigin("http://localhost:8000"));
	}

	@Test
	void getOriginDropsPathAndNormalizesCase() {
		assertEquals("https://mcsrc.dev:443", server.getOrigin("HTTPS://MCSRC.DEV/some/path?query#fragment"));
	}

	@Test
	void getOriginReturnsNullForNull() {
		assertNull(server.getOrigin(null));
	}

	@Test
	void getOriginRejectsInvalidUrls() {
		assertHttpError(403, () -> server.getOrigin("null"));
		assertHttpError(403, () -> server.getOrigin("not a url"));
		assertHttpError(403, () -> server.getOrigin("ftp://mcsrc.dev"));
		assertHttpError(403, () -> server.getOrigin("https:///foo"));
	}

	@Test
	void getMappingFileUsesOutermostClass() {
		Path expected = mappingsDir.resolve("net/minecraft/Foo.mapping");

		assertEquals(expected, server.getMappingFile("net.minecraft.Foo"));
		assertEquals(expected, server.getMappingFile("net.minecraft.Foo$Bar"));
		assertEquals(expected, server.getMappingFile("net.minecraft.Foo$Bar$Baz"));
	}

	@Test
	void getMappingFileRejectsTraversal() {
		assertHttpError(400, () -> server.getMappingFile("../../etc/passwd"));
	}

	@Test
	void getMappingFileRejectsEmptyClassName() {
		assertHttpError(404, () -> server.getMappingFile("$Foo"));
	}

	@Test
	void preflightFromAllowedOrigin() throws Exception {
		HttpResponse<String> response = request("OPTIONS", "/config", CLIENT, null);

		assertEquals(204, response.statusCode());
		assertEquals(CLIENT, response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
		assertEquals("true", response.headers().firstValue("Access-Control-Allow-Private-Network").orElseThrow());
		assertEquals("Origin", response.headers().firstValue("Vary").orElseThrow());
		assertTrue(response.headers().firstValue("Access-Control-Allow-Methods").orElseThrow().contains("GET"));
		assertTrue(response.headers().firstValue("Access-Control-Allow-Headers").orElseThrow().contains("Content-Type"));
	}

	@Test
	void preflightFromDisallowedOrigin() throws Exception {
		HttpResponse<String> response = request("OPTIONS", "/config", "https://evil.example", null);

		assertEquals(403, response.statusCode());
		assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
	}

	@Test
	void configFromAllowedOrigin() throws Exception {
		HttpResponse<String> response = request("GET", "/config", CLIENT, null);

		assertEquals(200, response.statusCode());
		assertEquals("{\"version\":\"" + VERSION + "\"}", response.body());
		assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
	}

	@Test
	void configWithoutOriginIsRejected() throws Exception {
		assertEquals(403, request("GET", "/config", null, null).statusCode());
	}

	@Test
	void configWithNullOriginIsRejected() throws Exception {
		assertEquals(403, request("GET", "/config", "null", null).statusCode());
	}

	@Test
	void configWithExplicitDefaultPortIsAllowed() throws Exception {
		assertEquals(200, request("GET", "/config", "https://mcsrc.dev:443", null).statusCode());
	}

	@Test
	void configFromDifferentPortIsRejected() throws Exception {
		assertEquals(403, request("GET", "/config", "https://mcsrc.dev:444", null).statusCode());
	}

	@Test
	void unknownRouteIs404() throws Exception {
		HttpResponse<String> response = request("GET", "/unknown", CLIENT, null);

		assertEquals(404, response.statusCode());
	}

	@Test
	void wrongMethodIs404() throws Exception {
		HttpResponse<String> response = request("POST", "/config", CLIENT, "{}");

		assertEquals(404, response.statusCode());
	}

	@Test
	void pingFromAllowedOrigin() throws Exception {
		assertEquals(204, request("GET", "/ping", CLIENT, null).statusCode());
	}

	@Test
	void errorIfNonNullableIsNull() throws Exception {
		HttpResponse<String> response = request("POST", "/get-mapping", CLIENT, "{}");

		assertEquals(400, response.statusCode(), response.body());
	}

	@Test
	void parsesUpdateMappingBody() throws Exception {
		HttpResponse<String> response = request("POST", "/update-mapping", CLIENT, """
				{
					"className": "net.minecraft.Foo",
					"kind": "METHOD",
					"name": "bar",
					"desc": "()V",
					"comments": "hello"
				}""");

		assertEquals(200, response.statusCode(), response.body());

		MemoryMappingTree written = new MemoryMappingTree();
		MappingReader.read(mappingsDir.resolve("net/minecraft/Foo.mapping"), MappingFormat.ENIGMA_FILE, written);

		var cls = written.getClass("net/minecraft/Foo");
		assertNotNull(cls);

		var method = cls.getMethod("bar", "()V");
		assertNotNull(method);
		assertEquals("hello", method.getComment());
	}

	@Test
	void updateMethodMappingErrorOnMissingName() throws Exception {
		HttpResponse<String> response = request("POST", "/update-mapping", CLIENT, """
				{
					"className": "net/minecraft/Foo",
					"kind": "METHOD",
					"comments": "hello"
				}""");

		assertEquals(400, response.statusCode(), response.body());
	}

	@Test
	void createsMappingForNewClass() throws Exception {
		HttpResponse<String> response = request("POST", "/update-mapping", CLIENT, """
				{
					"className": "net.minecraft.Foo",
					"kind": "CLASS",
					"comments": "new class"
				}""");

		assertEquals(200, response.statusCode(), response.body());

		Path file = mappingsDir.resolve("net/minecraft/Foo.mapping");
		assertTrue(Files.isRegularFile(file));

		MemoryMappingTree written = new MemoryMappingTree();
		MappingReader.read(file, MappingFormat.ENIGMA_FILE, written);

		var cls = written.getClass("net/minecraft/Foo");
		assertNotNull(cls);
		assertEquals("new class", cls.getComment());
	}

	@Test
	void updateMappingForInnerClassWritesOutermostFile() throws Exception {
		HttpResponse<String> response = request("POST", "/update-mapping", CLIENT, """
				{
					"className": "net.minecraft.Foo$Bar",
					"kind": "CLASS",
					"comments": "inner class"
				}""");

		assertEquals(200, response.statusCode(), response.body());

		Path outer = mappingsDir.resolve("net/minecraft/Foo.mapping");
		assertTrue(Files.isRegularFile(outer));
		assertFalse(Files.exists(mappingsDir.resolve("net/minecraft/Foo$Bar.mapping")));

		MemoryMappingTree written = new MemoryMappingTree();
		MappingReader.read(outer, MappingFormat.ENIGMA_FILE, written);

		var inner = written.getClass("net/minecraft/Foo$Bar");
		assertNotNull(inner);
		assertEquals("inner class", inner.getComment());
	}

	@Test
	void updateMappingPreservesExistingContent() throws Exception {
		Path file = mappingsDir.resolve("net/minecraft/Foo.mapping");
		Files.createDirectories(file.getParent());
		Files.writeString(file, """
				CLASS net/minecraft/Foo
				\tFIELD bar I
				\t\tCOMMENT existing
				""");

		HttpResponse<String> response = request("POST", "/update-mapping", CLIENT, """
				{
					"className": "net.minecraft.Foo",
					"kind": "CLASS",
					"comments": "new comment"
				}""");

		assertEquals(200, response.statusCode(), response.body());

		MemoryMappingTree written = new MemoryMappingTree();
		MappingReader.read(file, MappingFormat.ENIGMA_FILE, written);

		var cls = written.getClass("net/minecraft/Foo");
		assertNotNull(cls);
		assertEquals("new comment", cls.getComment());
		assertNotNull(cls.getField("bar", "I"));
	}

	@Test
	void getMappingForInnerClassReadsOutermostFile() throws Exception {
		Path outer = mappingsDir.resolve("net/minecraft/Foo.mapping");
		Files.createDirectories(outer.getParent());
		Files.writeString(outer, """
				CLASS net/minecraft/Foo$Bar
				\tCOMMENT inner
				""");

		HttpResponse<String> response = request("POST", "/get-mapping", CLIENT, """
				{
					"className": "net.minecraft.Foo$Bar"
				}""");

		assertEquals(200, response.statusCode(), response.body());
		assertTrue(response.body().contains("net/minecraft/Foo$Bar"));
	}

	@Test
	void getMappingForUnknownClassIs404() throws Exception {
		HttpResponse<String> response = request("POST", "/get-mapping", CLIENT, """
				{
					"className": "net.minecraft.Missing"
				}""");

		assertEquals(404, response.statusCode());
	}

	private HttpResponse<String> request(
			String method, String path, String origin, String body
	) throws IOException, InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path)).method(method, body == null
				? BodyPublishers.noBody()
				: BodyPublishers.ofString(body));

		if (origin != null) {
			builder.header("Origin", origin);
		}

		if (body != null) {
			builder.header("Content-Type", "application/json");
		}

		return HTTP.send(builder.build(), BodyHandlers.ofString());
	}

	private static void assertHttpError(int status, Executable executable) {
		AnnotaterServer.HttpError error = assertThrows(AnnotaterServer.HttpError.class, executable);
		assertEquals(status, error.status);
	}
}
