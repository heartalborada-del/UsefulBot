package me.heartalborada.loader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class DependencyLoader {
    private static final String DEPENDENCY_RESOURCE = "/META-INF/usefulbot-dependencies.tsv";
    private static final String DEFAULT_REPOSITORY = "https://repo.maven.apache.org/maven2/";
    private static final String ALIYUN_REPOSITORY = "https://maven.aliyun.com/repository/central/";
    private static final String IP_REGION_ENDPOINT = "https://www.cloudflare.com/cdn-cgi/trace";
    private static final String MAIN_CLASS = "MainKt";
    private static final String RESOLVE_ONLY_ARGUMENT = "--resolve-dependencies-only";

    private DependencyLoader() {
    }

    public static void main(String[] args) throws Exception {
        Path cacheDirectory = dependencyCacheDirectory();
        List<Dependency> dependencies = readDependencies();
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        List<String> repositories = repositoryBaseUrls(client);

        List<URL> classpath = new ArrayList<>(dependencies.size() + 1);
        classpath.add(DependencyLoader.class.getProtectionDomain().getCodeSource().getLocation());
        for (Dependency dependency : dependencies) {
            classpath.add(resolve(client, repositories, cacheDirectory, dependency).toUri().toURL());
        }

        if (args.length == 1 && RESOLVE_ONLY_ARGUMENT.equals(args[0])) {
            System.out.printf("Resolved %d dependencies into %s%n", dependencies.size(), cacheDirectory);
            return;
        }

        try (URLClassLoader applicationLoader = new URLClassLoader(
            classpath.toArray(URL[]::new),
            ClassLoader.getPlatformClassLoader()
        )) {
            Thread.currentThread().setContextClassLoader(applicationLoader);
            invokeApplicationMain(applicationLoader, args);
        }
    }

    private static List<Dependency> readDependencies() throws IOException {
        InputStream stream = DependencyLoader.class.getResourceAsStream(DEPENDENCY_RESOURCE);
        if (stream == null) {
            throw new IOException("Missing dependency manifest " + DEPENDENCY_RESOURCE);
        }

        List<Dependency> dependencies = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] fields = line.split("\\t", -1);
                if (fields.length != 5) {
                    throw new IOException("Invalid dependency entry: " + line);
                }
                dependencies.add(new Dependency(fields[0], fields[1], fields[2], fields[3], fields[4]));
            }
        }
        return dependencies;
    }

    private static Path resolve(
        HttpClient client,
        List<String> repositories,
        Path cacheDirectory,
        Dependency dependency
    ) throws IOException, InterruptedException, NoSuchAlgorithmException {
        Path relativePath = Path.of(
            dependency.group().replace('.', '/'),
            dependency.module(),
            dependency.version(),
            dependency.fileName()
        );
        Path target = cacheDirectory.resolve(relativePath);
        Files.createDirectories(target.getParent());

        if (isValid(target, dependency.sha256())) return target;

        Path lockPath = target.resolveSibling(target.getFileName() + ".lock");
        try (FileChannel channel = FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE
        ); FileLock ignored = channel.lock()) {
            if (isValid(target, dependency.sha256())) return target;

            String relativeUrl = relativePath.toString().replace('\\', '/');
            Path temporary = target.resolveSibling(
                target.getFileName() + ".part-" + ProcessHandle.current().pid()
            );
            IOException lastFailure = null;
            for (String repository : repositories) {
                URI uri = URI.create(repository + relativeUrl);
                Files.deleteIfExists(temporary);
                System.out.printf(
                    "Downloading %s:%s:%s from %s%n",
                    dependency.group(),
                    dependency.module(),
                    dependency.version(),
                    uri.getHost()
                );

                try {
                    HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofMinutes(5))
                        .header("User-Agent", "UsefulBot-DependencyLoader/1.0")
                        .GET()
                        .build();
                    HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(temporary));
                    if (response.statusCode() != 200) {
                        throw new IOException("Maven download failed with HTTP " + response.statusCode() + ": " + uri);
                    }
                    if (!isValid(temporary, dependency.sha256())) {
                        throw new IOException("SHA-256 verification failed for " + uri);
                    }
                    moveAtomically(temporary, target);
                    return target;
                } catch (IOException exception) {
                    lastFailure = exception;
                    System.err.println(exception.getMessage());
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            throw lastFailure == null
                ? new IOException("No Maven repository was configured")
                : lastFailure;
        }
    }

    private static boolean isValid(Path file, String expectedSha256)
        throws IOException, NoSuchAlgorithmException {
        return Files.isRegularFile(file) && expectedSha256.equalsIgnoreCase(sha256(file));
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void invokeApplicationMain(ClassLoader classLoader, String[] args) throws Exception {
        Class<?> mainClass = Class.forName(MAIN_CLASS, true, classLoader);
        Method main = mainClass.getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception nested) throw nested;
            if (cause instanceof Error error) throw error;
            throw exception;
        }
    }

    private static List<String> repositoryBaseUrls(HttpClient client) {
        String configured = firstNonBlankOrNull(
            System.getProperty("usefulbot.mavenRepository"),
            System.getenv("USEFULBOT_MAVEN_REPOSITORY")
        );
        if (configured != null) {
            String repository = withTrailingSlash(configured);
            System.out.println("Using configured Maven repository: " + repository);
            return List.of(repository);
        }

        String region = firstNonBlankOrNull(
            System.getProperty("usefulbot.mavenRegion"),
            System.getenv("USEFULBOT_MAVEN_REGION")
        );
        if (region == null) region = detectCountryCode(client);
        if ("CN".equalsIgnoreCase(region)) {
            System.out.println("China network detected; using Aliyun Maven mirror with Maven Central fallback.");
            return List.of(ALIYUN_REPOSITORY, DEFAULT_REPOSITORY);
        }
        return List.of(DEFAULT_REPOSITORY);
    }

    private static String detectCountryCode(HttpClient client) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(IP_REGION_ENDPOINT))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "UsefulBot-DependencyLoader/1.0")
                .GET()
                .build();
            HttpResponse<String> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() != 200) return null;
            for (String line : response.body().split("\\R")) {
                if (line.startsWith("loc=")) return line.substring("loc=".length()).trim();
            }
        } catch (IOException exception) {
            System.err.println("Unable to detect network region: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private static String withTrailingSlash(String repository) {
        return repository.endsWith("/") ? repository : repository + "/";
    }

    private static Path dependencyCacheDirectory() {
        String configured = firstNonBlank(
            System.getProperty("usefulbot.dependencyCache"),
            System.getenv("USEFULBOT_DEPENDENCY_CACHE"),
            defaultDependencyCacheDirectory().toString()
        );
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private static Path defaultDependencyCacheDirectory() {
        try {
            Path location = Path.of(
                DependencyLoader.class.getProtectionDomain().getCodeSource().getLocation().toURI()
            ).toAbsolutePath().normalize();
            Path directory = Files.isRegularFile(location) ? location.getParent() : location;
            return directory.resolve("deps");
        } catch (Exception exception) {
            return Path.of("deps").toAbsolutePath().normalize();
        }
    }

    private static String firstNonBlank(String... candidates) {
        String result = firstNonBlankOrNull(candidates);
        if (result != null) return result;
        throw new IllegalStateException("No non-blank configuration value was provided");
    }

    private static String firstNonBlankOrNull(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) return candidate;
        }
        return null;
    }

    private record Dependency(String group, String module, String version, String fileName, String sha256) {
    }
}
