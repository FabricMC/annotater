package net.fabricmc.filament.task.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
	@TempDir
	Path unpickDir;
	@TempDir
	Path runtimeLibrariesDir;

	private AnnotaterServer server;
	private HttpServer httpServer;
	private URI baseUri;

	@BeforeEach
	void setUp() throws IOException {
		server = new AnnotaterServer(
				mappingsDir.toString(),
				unpickDir.toString(),
				runtimeLibrariesDir.toString(),
				VERSION,
				"127.0.0.1",
				0,
				CLIENT,
				false
		);
		httpServer = server.createServer();
		httpServer.start();
		baseUri = URI.create("http://127.0.0.1:" + httpServer.getAddress().getPort());
	}

	@AfterEach
	void tearDown() {
		httpServer.stop(0);
	}

	@Test
	void originIsNormalizedBeforeComparison() throws Exception {
		assertEquals(200, request("GET", "/config", "https://mcsrc.dev", null).statusCode());
		assertEquals(200, request("GET", "/config", "HTTPS://MCSRC.DEV/some/path?query#fragment", null).statusCode());
	}

	@Test
	void invalidOriginsAreRejected() throws Exception {
		assertEquals(403, request("GET", "/config", "not a url", null).statusCode());
		assertEquals(403, request("GET", "/config", "ftp://mcsrc.dev", null).statusCode());
		assertEquals(403, request("GET", "/config", "https:///foo", null).statusCode());
	}

	@Test
	void getMappingUsesOutermostClassFile() throws Exception {
		Path file = mappingsDir.resolve("net/minecraft/Foo.mapping");
		Files.createDirectories(file.getParent());
		Files.writeString(file, "CLASS net/minecraft/Foo\n");

		for (String className : new String[] {"net.minecraft.Foo", "net.minecraft.Foo$Bar", "net.minecraft.Foo$Bar$Baz"}) {
			HttpResponse<String> response = request("GET", "/mapping?" + className, CLIENT, null);

			assertEquals(200, response.statusCode(), className);
			assertEquals("CLASS net/minecraft/Foo\n", response.body(), className);
		}
	}

	@Test
	void getMappingRejectsTraversal() throws Exception {
		assertEquals(400, request("GET", "/mapping?../../etc/passwd", CLIENT, null).statusCode());
	}

	@Test
	void getMappingRejectsEmptyClassName() throws Exception {
		assertEquals(400, request("GET", "/mapping?$Foo", CLIENT, null).statusCode());
	}

	@Test
	void updateMappingRejectsTraversal() throws Exception {
		HttpResponse<String> response = request("POST", "/mapping", CLIENT, """
				{
					"className": "../../etc/passwd",
					"kind": "CLASS",
					"comments": "nope"
				}""");

		assertEquals(400, response.statusCode(), response.body());
	}

	@Test
	void preflightFromAllowedOrigin() throws Exception {
		HttpResponse<String> response = request("OPTIONS", "/config", CLIENT, null);

		assertEquals(204, response.statusCode());
		assertEquals(CLIENT, response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
		assertEquals("true", response.headers().firstValue("Access-Control-Allow-Private-Network").orElseThrow());
		assertEquals("Origin", response.headers().firstValue("Vary").orElseThrow());
		assertTrue(response.headers().firstValue("Access-Control-Allow-Methods").orElseThrow().contains("GET"));
		assertTrue(response.headers().firstValue("Access-Control-Allow-Methods").orElseThrow().contains("DELETE"));
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
		Files.createDirectories(runtimeLibrariesDir.resolve("nested"));
		Files.writeString(runtimeLibrariesDir.resolve("nested/lib.jar"), "");
		Files.writeString(runtimeLibrariesDir.resolve("ignored.txt"), "");

		HttpResponse<String> response = request("GET", "/config", CLIENT, null);

		assertEquals(200, response.statusCode());
		assertEquals(
				"{\"version\":\"" + VERSION + "\",\"runtimeLibraries\":[\"nested/lib.jar\"]}",
				response.body()
		);
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
		assertEquals(404, request("POST", "/config", CLIENT, "{}").statusCode());
		assertEquals(404, request("POST", "/unpick/list", CLIENT, "{}").statusCode());
		assertEquals(404, request("POST", "/library", CLIENT, "{}").statusCode());
		assertEquals(404, request("DELETE", "/mapping", CLIENT, null).statusCode());
		assertEquals(404, request("DELETE", "/library", CLIENT, null).statusCode());
	}

	@Test
	void pingFromAllowedOrigin() throws Exception {
		assertEquals(204, request("GET", "/ping", CLIENT, null).statusCode());
	}

	@Test
	void errorIfNonNullableIsNull() throws Exception {
		HttpResponse<String> response = request("POST", "/mapping", CLIENT, "{}");

		assertEquals(400, response.statusCode(), response.body());
	}

	@Test
	void parsesUpdateMappingBody() throws Exception {
		HttpResponse<String> response = request("POST", "/mapping", CLIENT, """
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
		HttpResponse<String> response = request("POST", "/mapping", CLIENT, """
				{
					"className": "net/minecraft/Foo",
					"kind": "METHOD",
					"comments": "hello"
				}""");

		assertEquals(400, response.statusCode(), response.body());
	}

	@Test
	void createsMappingForNewClass() throws Exception {
		HttpResponse<String> response = request("POST", "/mapping", CLIENT, """
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
		HttpResponse<String> response = request("POST", "/mapping", CLIENT, """
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

		HttpResponse<String> response = request("POST", "/mapping", CLIENT, """
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

		HttpResponse<String> response = request("GET", "/mapping?net.minecraft.Foo$Bar", CLIENT, null);

		assertEquals(200, response.statusCode(), response.body());
		assertTrue(response.body().contains("net/minecraft/Foo$Bar"));
		assertEquals("text/plain; charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow());
	}

	@Test
	void getMappingForUnknownClassIs404() throws Exception {
		assertEquals(404, request("GET", "/mapping?net.minecraft.Missing", CLIENT, null).statusCode());
	}

	@Test
	void getMappingWithoutQueryIs400() throws Exception {
		assertEquals(400, request("GET", "/mapping", CLIENT, null).statusCode());
	}

	@Test
	void listUnpickFiles() throws Exception {
		Path file = unpickDir.resolve("sub/Foo.unpick");
		Files.createDirectories(file.getParent());
		Files.writeString(file, "unpick content");
		Files.writeString(unpickDir.resolve("ignored.txt"), "");

		HttpResponse<String> response = request("GET", "/unpick/list", CLIENT, null);

		assertEquals(200, response.statusCode(), response.body());
		assertTrue(response.body().contains("\"path\":\"sub/Foo.unpick\""));
		assertFalse(response.body().contains("ignored.txt"));
	}

	@Test
	void getUnpick() throws Exception {
		Path file = unpickDir.resolve("sub/Foo.unpick");
		Files.createDirectories(file.getParent());
		Files.writeString(file, "unpick content");

		HttpResponse<String> response = request("GET", "/unpick?sub/Foo.unpick", CLIENT, null);

		assertEquals(200, response.statusCode(), response.body());
		assertEquals("unpick content", response.body());
		assertEquals("text/plain; charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow());
	}

	@Test
	void getUnpickRejectsNonUnpickPath() throws Exception {
		assertEquals(400, request("GET", "/unpick?notes.txt", CLIENT, null).statusCode());
	}

	@Test
	void getUnpickRejectsTraversal() throws Exception {
		assertEquals(400, request("GET", "/unpick?../../evil.unpick", CLIENT, null).statusCode());
	}

	@Test
	void getUnpickUnknownIs404() throws Exception {
		assertEquals(404, request("GET", "/unpick?sub/Missing.unpick", CLIENT, null).statusCode());
	}

	@Test
	void updateUnpickWritesFile() throws Exception {
		HttpResponse<String> response = request("POST", "/unpick", CLIENT, """
				{
					"path": "Foo.unpick",
					"content": "unpick content"
				}""");

		assertEquals(200, response.statusCode(), response.body());
		assertEquals("unpick content", Files.readString(unpickDir.resolve("Foo.unpick")));
	}

	@Test
	void updateUnpickRejectsNonUnpickPath() throws Exception {
		HttpResponse<String> response = request("POST", "/unpick", CLIENT, """
				{
					"path": "notes.txt",
					"content": "nope"
				}""");

		assertEquals(400, response.statusCode(), response.body());
	}

	@Test
	void updateUnpickRejectsTraversal() throws Exception {
		HttpResponse<String> response = request("POST", "/unpick", CLIENT, """
				{
					"path": "../../evil.unpick",
					"content": "nope"
				}""");

		assertEquals(400, response.statusCode(), response.body());
	}

	@Test
	void deleteUnpickRemovesFile() throws Exception {
		Path file = unpickDir.resolve("Foo.unpick");
		Files.writeString(file, "unpick content");

		HttpResponse<String> response = request("DELETE", "/unpick?Foo.unpick", CLIENT, null);

		assertEquals(200, response.statusCode(), response.body());
		assertFalse(Files.exists(file));
	}

	@Test
	void deleteUnpickRejectsNonUnpickPath() throws Exception {
		assertEquals(400, request("DELETE", "/unpick?notes.txt", CLIENT, null).statusCode());
	}

	@Test
	void deleteUnpickRejectsTraversal() throws Exception {
		assertEquals(400, request("DELETE", "/unpick?../../evil.unpick", CLIENT, null).statusCode());
	}

	@Test
	void deleteUnpickWithoutQueryIs400() throws Exception {
		assertEquals(400, request("DELETE", "/unpick", CLIENT, null).statusCode());
	}

	@Test
	void deleteUnpickUnknownIs404() throws Exception {
		assertEquals(404, request("DELETE", "/unpick?Missing.unpick", CLIENT, null).statusCode());
	}

	@Test
	void getRuntimeLibrary() throws Exception {
		Files.writeString(runtimeLibrariesDir.resolve("lib.jar"), "jar content");

		HttpResponse<String> response = request("GET", "/library?lib.jar", CLIENT, null);

		assertEquals(200, response.statusCode(), response.body());
		assertEquals("jar content", response.body());
		assertEquals("application/java-archive", response.headers().firstValue("Content-Type").orElseThrow());
	}

	@Test
	void getRuntimeLibraryResolvesNestedPath() throws Exception {
		Path jar = runtimeLibrariesDir.resolve("nested/lib.jar");
		Files.createDirectories(jar.getParent());
		Files.writeString(jar, "jar content");

		HttpResponse<String> response = request("GET", "/library?nested/lib.jar", CLIENT, null);

		assertEquals(200, response.statusCode(), response.body());
		assertEquals("jar content", response.body());
	}

	@Test
	void getRuntimeLibraryRejectsNonJarPath() throws Exception {
		assertEquals(400, request("GET", "/library?lib.txt", CLIENT, null).statusCode());
	}

	@Test
	void getRuntimeLibraryRejectsTraversal() throws Exception {
		assertEquals(400, request("GET", "/library?../../evil.jar", CLIENT, null).statusCode());
	}

	@Test
	void getRuntimeLibraryUnknownIs404() throws Exception {
		assertEquals(404, request("GET", "/library?Missing.jar", CLIENT, null).statusCode());
	}

	@Test
	void getRuntimeLibraryWithoutQueryIs400() throws Exception {
		assertEquals(400, request("GET", "/library", CLIENT, null).statusCode());
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
}
