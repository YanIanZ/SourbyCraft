package dev.iyanz.sourbyclip;

import dev.iyanz.sourbyclip.update.AutoUpdate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.leavesmc.leavesclip.logger.Logger;
import org.leavesmc.leavesclip.logger.SimpleLogger;
import org.leavesmc.leavesclip.mixin.*;
import org.leavesmc.leavesclip.mixin.plugins.condition.BuildInfoInjector;
import org.leavesmc.plugin.mixin.condition.condition.ConditionChecker;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.FabricUtil;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public final class Sourbyclip {
    private static final boolean ENABLE_LEAVES_PLUGIN = Boolean.getBoolean("leavesclip.enable.mixin") || Boolean.getBoolean("sourbyclip.enable.mixin");
    public static final String[] ALL_MAVEN_REPO_LINK_BASE = new String[]{
            "https://maven.aliyun.com/repository/central",
            "https://repo.papermc.io/repository/maven-public",
            "https://repo.menthamc.org/repository/maven-public",
            "https://repo.spongepowered.org/maven",
    };
    public static final Executor DOWNLOAD_EXECUTOR = Executors.newCachedThreadPool();

    public static final Logger logger = new SimpleLogger("Sourbyclip");

    public static void main(final String[] args) {
        if (Path.of("").toAbsolutePath().toString().contains("!")) {
            System.err.println("Sourbyclip may not run in a directory containing '!'. Please rename the affected folder.");
            System.exit(1);
        }

        if (!Boolean.getBoolean("sourbyclip.disable.auto-update")
                && !Boolean.getBoolean("leavesclip.disable.auto-update")) {
            AutoUpdate.init();
        }

        final URL[] setupClasspathUrls = setupClasspath();
        final String mainClassName = findMainClass();
        final ClassLoader classLoader = getClassLoaderForLaunch(setupClasspathUrls);


        logger.info("Calling main method in server main class: " + mainClassName);
        final Thread runThread = generateThread(args, mainClassName, classLoader);

        runThread.start();
    }

    private static @NotNull ClassLoader getClassLoaderForLaunch(URL[] setupClasspathUrls) {
        if (ENABLE_LEAVES_PLUGIN) {
            logger.info("Leaves plugin has been enabled. Bootstrapping with mixin environment.");

            BuildInfoInjector.inject();
            overrideAsmVersion();
            PluginResolver.extractMixins();
            MixinJarResolver.resolveMixinJars();

            System.setProperty("mixin.bootstrapService", MixinServiceKnotBootstrap.class.getName());
            System.setProperty("mixin.service", MixinServiceKnot.class.getName());

            final URL[] classpathUrls = Arrays.copyOf(setupClasspathUrls, setupClasspathUrls.length + MixinJarResolver.jarUrls.length);
            System.arraycopy(MixinJarResolver.jarUrls, 0, classpathUrls, setupClasspathUrls.length, MixinJarResolver.jarUrls.length);

            final ClassLoader parentClassLoader = Sourbyclip.class.getClassLoader();
            MixinServiceKnot.classLoader = Sourbyclip.class.getClassLoader();

            MixinBootstrap.init();
            MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.SERVER);

            var createdClassLoader = new MixinURLClassLoader(classpathUrls, parentClassLoader);

            ConditionChecker.setClassLoader(createdClassLoader);
            Mixins.addConfiguration("mixin-extras.init.mixins.json");
            MixinServiceKnot.classLoader = createdClassLoader;
            MixinJarResolver.mixinConfigs.forEach(Mixins::addConfiguration);
            Mixins.getConfigs().forEach(config -> {
                final String mixinConfigName = config.getName();
                final String pluginId = MixinJarResolver.getPluginId(mixinConfigName);
                if (pluginId == null) return;

                final IMixinConfig mixinConfig = config.getConfig();

                mixinConfig.decorate(FabricUtil.KEY_MOD_ID, pluginId);
                mixinConfig.decorate(FabricUtil.KEY_COMPATIBILITY, FabricUtil.COMPATIBILITY_LATEST);
            });

            logger.info("Loading accesswideners");
            AccessWidenerManager.initAccessWidener(createdClassLoader);

            return createdClassLoader;
        } else {
            return new URLClassLoader(setupClasspathUrls, Sourbyclip.class.getClassLoader().getParent());
        }
    }

    private static @NotNull Thread generateThread(Object args, String mainClassName, ClassLoader classLoader) {
        final Thread runThread = new Thread(() -> {
            try {
                final Class<?> mainClass = Class.forName(mainClassName, true, classLoader);
                final MethodHandle mainHandle = MethodHandles.lookup()
                        .findStatic(mainClass, "main", MethodType.methodType(void.class, String[].class))
                        .asFixedArity();
                mainHandle.invoke(args);
            } catch (final Throwable t) {
                throw Util.sneakyThrow(t);
            }
        }, "ServerMain");

        runThread.setContextClassLoader(classLoader);

        return runThread;
    }

    private static URL @NotNull [] setupClasspath() {
        final var repoDir = Path.of(System.getProperty("bundlerRepoDir", ""));
        final boolean onlyUseMojangSource = Boolean.getBoolean("sourbyclip.useMojangSource");

        final PatchEntry[] patches = findPatches();
        final Path baseFile;
        if (onlyUseMojangSource) {
            baseFile = getDownloadContextFromMojang(repoDir).getOutputFile(repoDir);
        } else {
            DownloadContext downloadContext = findDownloadContext(false);
            if (patches.length > 0 && downloadContext == null) {
                throw new IllegalArgumentException("patches.list file found without a corresponding original-url file");
            }

            if (downloadContext != null) {
                try {
                    downloadContext.download(repoDir);
                } catch (final IOException e) {
                    System.out.println("Failed to download jar with auto matched download context! Trying using default download context");
                    downloadContext = getDownloadContextFromMojang(repoDir);
                }
                baseFile = downloadContext.getOutputFile(repoDir);
            } else {
                baseFile = null;
            }
        }

        final Map<String, Map<String, URL>> classpathUrls = extractAndApplyPatches(baseFile, patches, repoDir);

        // Exit if user has set `paperclip.patchonly` or `sourbyclip.patchonly` system property to `true`
        if (Boolean.getBoolean("paperclip.patchonly")
                || Boolean.getBoolean("sourbyclip.patchonly")) {
            System.exit(0);
        }

        // Keep versions and libraries separate as the versions must come first
        // This is due to change we make to some library classes inside the versions jar
        final Collection<URL> versionUrls = classpathUrls.get("versions").values();
        final Collection<URL> libraryUrls = classpathUrls.get("libraries").values();

        final URL[] emptyArray = new URL[0];
        final URL[] urls = new URL[versionUrls.size() + libraryUrls.size()];
        System.arraycopy(versionUrls.toArray(emptyArray), 0, urls, 0, versionUrls.size());
        System.arraycopy(libraryUrls.toArray(emptyArray), 0, urls, versionUrls.size(), libraryUrls.size());
        return urls;
    }

    private static @NotNull DownloadContext getDownloadContextFromMojang(Path repoDir) {
        DownloadContext downloadContext;
        downloadContext = findDownloadContext(true);

        if (downloadContext == null) {
            throw new IllegalStateException("Default download context not found!");
        }

        try {
            downloadContext.download(repoDir);
        } catch (IOException ex2) {
            throw Util.fail("Failed to download original jar", ex2);
        }
        return downloadContext;
    }

    private static PatchEntry @NotNull [] findPatches() {
        final InputStream patchListStream = AutoUpdate.getResourceAsStreamFromTargetJar("/META-INF/patches.list");
        if (patchListStream == null) {
            return new PatchEntry[0];
        }

        try (patchListStream) {
            return PatchEntry.parse(new BufferedReader(new InputStreamReader(patchListStream)));
        } catch (final IOException e) {
            throw Util.fail("Failed to read patches.list file", e);
        }
    }

    private static void overrideAsmVersion() {
        try {
            Class<?> asmClass = Class.forName("org.spongepowered.asm.util.asm.ASM");
            Field minorVersionField = asmClass.getDeclaredField("implMinorVersion");
            minorVersionField.setAccessible(true);
            minorVersionField.setInt(null, 5);

        } catch (Exception e) {
            logger.error("Failed to override asm version", e);
        }
    }

    private static @NotNull String getDownloadContextFileName(boolean ignoreCountry) {
        final String base = "download-context";
        final String customized = System.getProperty("sourbyclip.downloadContext");

        // the retry attempt used this
        if (ignoreCountry) {
            return base;
        }

        if (customized != null) {
            // user requires use default download source
            if (customized.equals("null")) {
                return base;
            }

            return customized;
        }

        // fetch location and determine which to use
        final String country = IPUtil.getCountryByIp();
        if (country.equalsIgnoreCase("China")
                || country.equalsIgnoreCase("CN")
                || country.equalsIgnoreCase("PRC")) {
            return base + "-cn";
        }

        return base;
    }

    private static DownloadContext findDownloadContext(boolean ignoreCountry) {
        String line;
        try {
            line = Util.readResourceText("/META-INF/" + getDownloadContextFileName(ignoreCountry));
        } catch (final IOException e) {
            // direct throw if ignoreCountry is true
            if (ignoreCountry) {
                throw Util.fail("Failed to read download-context file", e);
            }
            // other download source does not found
            try {
                line = Util.readResourceText("/META-INF/" + getDownloadContextFileName(true));
            } catch (IOException e1) {
                throw Util.fail("Failed to read download-context file", e1);
            }
        }

        return DownloadContext.parseLine(line);
    }

    private static FileEntry[] findVersionEntries() {
        return findFileEntries("versions.list");
    }

    private static FileEntry[] findLibraryEntries() {
        return findFileEntries("libraries.list");
    }

    private static FileEntry @Nullable [] findFileEntries(final String fileName) {
        final InputStream libListStream = AutoUpdate.getResourceAsStreamFromTargetJar("/META-INF/" + fileName);
        if (libListStream == null) {
            return null;
        }

        try (libListStream) {
            return FileEntry.parse(new BufferedReader(new InputStreamReader(libListStream)));
        } catch (final IOException e) {
            throw Util.fail("Failed to read " + fileName + " file", e);
        }
    }

    private static String findMainClass() {
        final String mainClassName = System.getProperty("bundlerMainClass");
        if (mainClassName != null) {
            return mainClassName;
        }

        try {
            return Util.readResourceText("/META-INF/main-class");
        } catch (final IOException e) {
            throw Util.fail("Failed to read main-class file", e);
        }
    }

    private static @NotNull Map<String, Map<String, URL>> extractAndApplyPatches(final Path originalJar, final PatchEntry[] patches, final Path repoDir) {
        if (originalJar == null && patches.length > 0) {
            throw new IllegalArgumentException("Patch data found without patch target");
        }

        // First extract any non-patch files
        final Map<String, Map<String, URL>> urls = extractFiles(patches, originalJar, repoDir);

        // Next apply any patches that we have
        applyPatches(urls, patches, originalJar, repoDir);

        return urls;
    }

    private static @NotNull Map<String, Map<String, URL>> extractFiles(final PatchEntry[] patches, final Path originalJar, final Path repoDir) {
        final var urls = new HashMap<String, Map<String, URL>>();

        try {
            final FileSystem originalJarFs;
            if (originalJar == null) {
                originalJarFs = null;
            } else {
                originalJarFs = FileSystems.newFileSystem(originalJar);
            }

            try {
                final Path originalRootDir;
                if (originalJarFs == null) {
                    originalRootDir = null;
                } else {
                    originalRootDir = originalJarFs.getPath("/");
                }

                final var versionsMap = new HashMap<String, URL>();
                urls.putIfAbsent("versions", versionsMap);
                final FileEntry[] versionEntries = findVersionEntries();
                extractEntries(versionsMap, patches, originalRootDir, repoDir, versionEntries, "versions");

                final FileEntry[] libraryEntries = findLibraryEntries();
                final var librariesMap = new HashMap<String, URL>();
                urls.putIfAbsent("libraries", librariesMap);
                extractEntries(librariesMap, patches, originalRootDir, repoDir, libraryEntries, "libraries");
            } finally {
                if (originalJarFs != null) {
                    originalJarFs.close();
                }
            }
        } catch (final IOException e) {
            throw Util.fail("Failed to extract jar files", e);
        }

        return urls;
    }

    private static void extractEntries(
            final Map<String, URL> urls,
            final PatchEntry[] patches,
            final Path originalRootDir,
            final Path repoDir,
            final FileEntry[] entries,
            final String targetName
    ) throws IOException {
        if (entries == null) {
            return;
        }

        final String targetPath = "/META-INF/" + targetName;
        final Path targetDir = repoDir.resolve(targetName);

        CompletableFuture.allOf(Arrays.stream(entries).map(entry -> {
            try {
                return entry.downloadFromMvnRepo(urls, patches, targetName, originalRootDir, targetPath, targetDir);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).toArray(CompletableFuture[]::new)).join();
    }

    private static void applyPatches(
            final Map<String, Map<String, URL>> urls,
            final PatchEntry @NotNull [] patches,
            final Path originalJar,
            final Path repoDir
    ) {
        if (patches.length == 0) {
            return;
        }
        if (originalJar == null) {
            throw new IllegalStateException("Patches provided without patch target");
        }

        try (final FileSystem originalFs = FileSystems.newFileSystem(originalJar)) {
            final Path originalRootDir = originalFs.getPath("/");

            for (final PatchEntry patch : patches) {
                patch.applyPatch(urls, originalRootDir, repoDir);
            }
        } catch (final IOException e) {
            throw Util.fail("Failed to apply patches", e);
        }
    }
}
