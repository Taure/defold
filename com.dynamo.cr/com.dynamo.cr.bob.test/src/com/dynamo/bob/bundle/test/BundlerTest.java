package com.dynamo.bob.bundle.test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;


import javax.imageio.ImageIO;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.RegexFileFilter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.dynamo.bob.ClassLoaderScanner;
import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.MultipleCompileException;
import com.dynamo.bob.NullProgress;
import com.dynamo.bob.Platform;
import com.dynamo.bob.Project;
import com.dynamo.bob.TaskResult;
import com.dynamo.bob.archive.ArchiveBuilder;
import com.dynamo.bob.archive.ManifestBuilder;
import com.dynamo.bob.archive.publisher.NullPublisher;
import com.dynamo.bob.archive.publisher.PublisherSettings;
import com.dynamo.bob.bundle.BundleHelper;
import com.dynamo.bob.fs.DefaultFileSystem;
import com.dynamo.liveupdate.proto.Manifest.HashAlgorithm;

@RunWith(Parameterized.class)
public class BundlerTest {

    private String contentRoot;
    private String outputDir;
    private String contentRootUnused;
    private Platform platform;

    @Parameters
    public static Collection<Platform[]> data() {
        Platform[][] data = new Platform[][] {
            {Platform.X86Win32}, {Platform.X86_64Win32},
            {Platform.X86_64Darwin},
            {Platform.X86_64Linux},
            {Platform.Armv7Android},
            {Platform.JsWeb},
            {Platform.Armv7Darwin}, {Platform.Arm64Darwin}, {Platform.X86_64Ios},
        };
        return Arrays.asList(data);
    }

    private File getOutputDirFile(String outputDir, String projectName) {
        String folderName = projectName;
        switch (platform)
        {
            case X86_64Darwin:
            case Armv7Darwin:
            case Arm64Darwin:
            case X86_64Ios:
                    folderName = projectName + ".app";
            break;
        }
        return new File(outputDir, folderName);
    }

    private String getBundleAppFolder(String projectName) {
        switch (platform)
        {
            case Armv7Darwin:
            case Arm64Darwin:
            case X86_64Ios:
                return String.format("Payload/%s.app/", projectName);
        }
        return "";
    }

    // Used to check if the built and bundled test projects all contain the correct engine binaries.
    private void verifyEngineBinaries() throws IOException
    {
        String projectName = "unnamed";
        String exeName = BundleHelper.projectNameToBinaryName(projectName);
        File outputDirFile = getOutputDirFile(outputDir, projectName);
        assertTrue(outputDirFile.exists());
        switch (platform)
        {
            case X86Win32:
            case X86_64Win32:
            {
                File outputBinary = new File(outputDirFile, projectName + ".exe");
                assertTrue(outputBinary.exists());
            }
            break;
            case Armv7Android:
            {
                File outputApk = new File(outputDirFile, projectName + ".apk");
                assertTrue(outputApk.exists());
                FileSystem apkZip = FileSystems.newFileSystem(outputApk.toPath(), null);
                Path enginePathArmv7 = apkZip.getPath("lib/armeabi-v7a/lib" + exeName + ".so");
                assertTrue(Files.isReadable(enginePathArmv7));
                Path classesDexPath = apkZip.getPath("classes.dex");
                assertTrue(Files.isReadable(classesDexPath));
            }
            break;
            case Arm64Android:
            {
                File outputApk = new File(outputDirFile, projectName + ".apk");
                assertTrue(outputApk.exists());
                FileSystem apkZip = FileSystems.newFileSystem(outputApk.toPath(), null);
                Path enginePathArm64 = apkZip.getPath("lib/arm64-v8a/lib" + exeName + ".so");
                assertTrue(Files.isReadable(enginePathArm64));
                Path classesDexPath = apkZip.getPath("classes.dex");
                assertTrue(Files.isReadable(classesDexPath));
            }
            break;
            case JsWeb:
            {
                File asmjsFile = new File(outputDirFile, exeName + "_asmjs.js");
                assertTrue(asmjsFile.exists());
                File wasmjsFile = new File(outputDirFile, exeName + "_wasm.js");
                assertTrue(wasmjsFile.exists());
                File wasmFile = new File(outputDirFile, exeName + ".wasm");
                assertTrue(wasmFile.exists());
            }
            break;
            case Armv7Darwin:
            case Arm64Darwin:
            case X86_64Ios:
            {
                List<String> names = Arrays.asList(
                    exeName,
                    "Info.plist",
                    "Icon.png",
                    "Icon@2x.png",
                    "Icon-60@2x.png",
                    "Icon-60@3x.png",
                    "Icon-72.png",
                    "Icon-72@2x.png",
                    "Icon-76.png",
                    "Icon-76@2x.png",
                    "Icon-167.png"
                );
                for (String name : names) {
                    File file = new File(outputDirFile, name);
                    assertTrue(file.exists());
                }
            }
            break;
            case X86_64Darwin:
                List<String> names = Arrays.asList(
                    String.format("Contents/MacOS/%s", exeName),
                    "Contents/Info.plist"
                );
                for (String name : names) {
                    File file = new File(outputDirFile, name);
                    assertTrue(file.exists());
                }
                break;
            case X86_64Linux:
                break;
            default:
                throw new IOException("Verifying engine binaries not implemented for platform: " + platform.toString());
        }
    }

    private List<String> getZipFiles(File zipFile) throws IOException {
        List<String> files = new ArrayList<String>();
        InputStream inputStream = new FileInputStream(zipFile);
        try (ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            ZipEntry zipEntry = zipInputStream.getNextEntry();

            while (zipEntry != null) {
                if (!zipEntry.isDirectory()) {
                    files.add(zipEntry.getName());
                }

                zipInputStream.closeEntry();
                zipEntry = zipInputStream.getNextEntry();
            }
        }
        return files;
    }

    private List<String> getBundleFiles() throws IOException {
        String projectName = "unnamed";
        File outputDirFile = getOutputDirFile(outputDir, projectName);
        assertTrue(outputDirFile.exists());

        List<String> files = new ArrayList<String>();

        if (platform == Platform.Armv7Android || platform == Platform.Arm64Android)
        {
            File zip = new File(outputDirFile, projectName + ".apk");
            assertTrue(zip.exists());
            files = getZipFiles(zip);
        }
        else if (platform == Platform.Armv7Darwin || platform == Platform.Arm64Darwin || platform == Platform.X86_64Ios)
        {
            File zip = new File(outputDirFile.getParentFile(), projectName + ".ipa");
            assertTrue(zip.exists());
            files = getZipFiles(zip);
        }
        else {
            for (File file : FileUtils.listFiles(outputDirFile, new RegexFileFilter(".*"), DirectoryFileFilter.DIRECTORY)) {
                String relative = outputDirFile.toURI().relativize(file.toURI()).getPath();
                files.add(relative);
            }
        }

        return files;
    }

    public BundlerTest(Platform platform) {
        this.platform = platform;
    }

    @Before
    public void setUp() throws Exception {
        contentRoot = Files.createTempDirectory("defoldtest").toFile().getAbsolutePath();
        outputDir = Files.createTempDirectory("defoldtest").toFile().getAbsolutePath();
        createFile(contentRoot, "game.project", "[display]\nwidth=640\nheight=480\n");

        contentRootUnused = Files.createTempDirectory("defoldtest").toFile().getAbsolutePath();
        createFile(contentRootUnused, "game.project", "[display]\nwidth=640\nheight=480\n[bootstrap]\nmain_collection = /main.collectionc\n");

        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_4BYTE_ABGR);
        ImageIO.write(image, "png", new File(contentRoot, "test.png"));
    }

    @After
    public void tearDown() throws IOException {
        FileUtils.deleteDirectory(new File(contentRoot));
        FileUtils.deleteDirectory(new File(outputDir));
        FileUtils.deleteDirectory(new File(contentRootUnused));
    }

    void build() throws IOException, CompileExceptionError, MultipleCompileException {
        Project project = new Project(new DefaultFileSystem(), contentRoot, "build");
        project.setPublisher(new NullPublisher(new PublisherSettings()));

        ClassLoaderScanner scanner = new ClassLoaderScanner();
        project.scan(scanner, "com.dynamo.bob");
        project.scan(scanner, "com.dynamo.bob.pipeline");

        setProjectProperties(project);

        Set<String> skipDirs = new HashSet<String>(Arrays.asList(".git", project.getBuildDirectory(), ".internal"));

        project.findSources(contentRoot, skipDirs);
        List<TaskResult> result = project.build(new NullProgress(), "clean", "build", "bundle");
        for (TaskResult taskResult : result) {
            assertTrue(taskResult.toString(), taskResult.isOk());
        }

        verifyEngineBinaries();
    }

    @SuppressWarnings("unused")
    Set<byte[]> readDarcEntries(String root) throws IOException
    {
        // Read the path entries in the resulting archive
        RandomAccessFile archiveIndex = new RandomAccessFile(root + "/build/game.arci", "r");
        archiveIndex.readInt();  // Version
        archiveIndex.readInt();  // Pad
        archiveIndex.readLong(); // Userdata
        int entryCount  = archiveIndex.readInt();
        int entryOffset = archiveIndex.readInt();
        int hashOffset  = archiveIndex.readInt();
        int hashLength  = archiveIndex.readInt();

        long fileSize = archiveIndex.length();

        Set<byte[]> entries = new HashSet<byte[]>();
        for (int i = 0; i < entryCount; ++i) {
            int offset = hashOffset + (i * ArchiveBuilder.HASH_MAX_LENGTH);
            archiveIndex.seek(offset);
            byte[] buffer = new byte[ArchiveBuilder.HASH_MAX_LENGTH];

            for (int n = 0; n < buffer.length; ++n) {
                buffer[n] = archiveIndex.readByte();
            }

            entries.add(buffer);
        }

        archiveIndex.close();
        return entries;
    }

    // Returns the number of files that will be put into the DARC file
    // Note that the game.project isn't put in the archive either
    protected int createDefaultFiles(String outputContentRoot) throws IOException {
        if (outputContentRoot == null) {
            outputContentRoot = contentRoot;
        }

        int count = 0;
        createFile(outputContentRoot, "logic/main.collection", "name: \"default\"\nscale_along_z: 0\n");
        count++;
        createFile(outputContentRoot, "builtins/render/default.render", "script: \"/builtins/render/default.render_script\"\n");
        count++;
        createFile(outputContentRoot, "builtins/render/default.render_script", "");
        count++;
        createFile(outputContentRoot, "builtins/render/default.display_profiles", "");
        count++;
        createFile(outputContentRoot, "builtins/input/default.gamepads", "");
        count++;
        createFile(outputContentRoot, "input/game.input_binding", "");
        count++;

        // These aren't put in the DARC file, so we don't count up
        createFile(outputContentRoot, "builtins/graphics/default.texture_profiles", "");
        createFile(outputContentRoot, "builtins/manifests/osx/Info.plist", "");
        createFile(outputContentRoot, "builtins/manifests/ios/Info.plist", "");
        createFile(outputContentRoot, "builtins/manifests/android/AndroidManifest.xml", "<?xml version=\"1.0\" encoding=\"utf-8\"?><manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"com.example\"><application android:label=\"Minimal Android Application\"><activity android:name=\".MainActivity\" android:label=\"Hello World\"><intent-filter><action android:name=\"android.intent.action.MAIN\" /><category android:name=\"android.intent.category.DEFAULT\" /><category android:name=\"android.intent.category.LAUNCHER\" /></intent-filter></activity></application></manifest>");
        createFile(outputContentRoot, "builtins/manifests/web/engine_template.html", "{{{DEFOLD_CUSTOM_CSS_INLINE}}} {{DEFOLD_APP_TITLE}} {{DEFOLD_DISPLAY_WIDTH}} {{DEFOLD_DISPLAY_WIDTH}} {{DEFOLD_ARCHIVE_LOCATION_PREFIX}} {{#HAS_DEFOLD_ENGINE_ARGUMENTS}} {{DEFOLD_ENGINE_ARGUMENTS}} {{/HAS_DEFOLD_ENGINE_ARGUMENTS}} {{DEFOLD_SPLASH_IMAGE}} {{DEFOLD_HEAP_SIZE}} {{DEFOLD_BINARY_PREFIX}} {{DEFOLD_BINARY_PREFIX}} {{DEFOLD_BINARY_PREFIX}} {{DEFOLD_HAS_FACEBOOK_APP_ID}}");
        return count;
    }

    @Test
    public void testBundle() throws IOException, ConfigurationException, CompileExceptionError, MultipleCompileException {
        createDefaultFiles(contentRoot);
        createFile(contentRoot, "test.icns", "test_icon");
        build();
    }

    private String createFile(String root, String name, String content) throws IOException {
        File file = new File(root, name);
        file.deleteOnExit();
        FileUtils.copyInputStreamToFile(new ByteArrayInputStream(content.getBytes()), file);
        return file.getAbsolutePath();
    }

    private void setProjectProperties(Project project) {
        Platform buildPlatform = platform;
        if (platform == Platform.Armv7Android || platform == Platform.Arm64Android) {
            buildPlatform = Platform.Armv7Android;
        }
        else if (platform == Platform.Armv7Darwin || platform == Platform.Arm64Darwin || platform == Platform.X86_64Ios) {
            buildPlatform = Platform.Armv7Darwin;
        }

        project.setOption("platform", buildPlatform.getPair());
        project.setOption("architectures", platform.getPair());
        project.setOption("archive", "true");
        project.setOption("bundle-output", outputDir);
    }

    @Test
    public void testUnusedCollections() throws IOException, ConfigurationException, CompileExceptionError, MultipleCompileException {
        int builtins_count = createDefaultFiles(contentRootUnused);
        createFile(contentRootUnused, "main.collection", "name: \"default\"\nscale_along_z: 0\n");
        createFile(contentRootUnused, "unused.collection", "name: \"unused\"\nscale_along_z: 0\n");

        Project project = new Project(new DefaultFileSystem(), contentRootUnused, "build");
        project.setPublisher(new NullPublisher(new PublisherSettings()));

        ClassLoaderScanner scanner = new ClassLoaderScanner();
        project.scan(scanner, "com.dynamo.bob");
        project.scan(scanner, "com.dynamo.bob.pipeline");

        setProjectProperties(project);
        project.setOption("keep-unused", "true");

        project.findSources(contentRootUnused, new HashSet<String>());
        List<TaskResult> result = project.build(new NullProgress(), "clean", "build");
        for (TaskResult taskResult : result) {
            assertTrue(taskResult.toString(), taskResult.isOk());
        }

        Set<byte[]> entries = readDarcEntries(contentRootUnused);

        assertEquals(builtins_count + 2, entries.size());
    }

    @Test
    public void testCustomResourcesFile() throws IOException, ConfigurationException, CompileExceptionError, MultipleCompileException {
        int numBuiltins = createDefaultFiles(contentRoot);
        createFile(contentRoot, "game.project", "[project]\ncustom_resources=m.txt\n[display]\nwidth=640\nheight=480\n");
        createFile(contentRoot, "m.txt", "dummy");
        build();

        Set<byte[]> entries = readDarcEntries(contentRoot);
        assertEquals(1, entries.size() - numBuiltins);
    }

    @Test
    public void testCustomResourcesDirs() throws IOException, ConfigurationException, CompileExceptionError, MultipleCompileException {
        File cust = new File(contentRoot, "custom");
        cust.mkdir();
        File sub1 = new File(cust, "sub1");
        File sub2 = new File(cust, "sub2");
        sub1.mkdir();
        sub2.mkdir();
        int numBuiltins = createDefaultFiles(contentRoot);
        createFile(contentRoot, "m.txt", "dummy");
        createFile(sub1.getAbsolutePath(), "s1-1.txt", "dummy");
        createFile(sub1.getAbsolutePath(), "s1-2.txt", "dummy");
        createFile(sub2.getAbsolutePath(), "s2-1.txt", "dummy");
        createFile(sub2.getAbsolutePath(), "s2-2.txt", "dummy");

        createFile(contentRoot, "game.project", "[project]\ncustom_resources=custom,m.txt\n[display]\nwidth=640\nheight=480\n");
        build();
        Set<byte[]> entries = readDarcEntries(contentRoot);
        assertEquals(5, entries.size() - numBuiltins);

        createFile(contentRoot, "game.project", "[project]\ncustom_resources=custom/sub2\n[display]\nwidth=640\nheight=480\n");
        build();
        entries = readDarcEntries(contentRoot);
        assertEquals(2, entries.size() - numBuiltins);
    }

    // Historically it has been possible to include custom resources by both specifying project relative paths and absolute paths.
    // (The only difference being a leading slash.) To keep backwards compatibility we need to support both.
    @Test
    public void testAbsoluteCustomResourcePath() throws IOException, ConfigurationException, CompileExceptionError, MultipleCompileException, NoSuchAlgorithmException {
        final String expectedData = "dummy";
        final HashAlgorithm hashAlgo = HashAlgorithm.HASH_SHA1;
        final byte[] expectedHash = ManifestBuilder.CryptographicOperations.hash(expectedData.getBytes(), hashAlgo);
        final int hlen = ManifestBuilder.CryptographicOperations.getHashSize(hashAlgo);
        int numBuiltins = createDefaultFiles(contentRoot);
        createFile(contentRoot, "game.project", "[project]\ncustom_resources=/m.txt\n[display]\nwidth=640\nheight=480\n");
        createFile(contentRoot, "m.txt", expectedData);
        build();

        Set<byte[]> entries = readDarcEntries(contentRoot);
        assertEquals(1, entries.size() - numBuiltins);

        // Verify that the entry contained in the darc has the same hash as m.txt
        boolean found = false;
        for (byte[] b : entries) {
            boolean ok = true;
            for (int i = 0; i < hlen; ++i) {
                if (expectedHash[i] != b[i]) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                found = true;
                break;
            }
        }
        assertTrue(found);
    }

    static HashSet<String> getExpectedFilesForPlatform(Platform platform)
    {
        HashSet<String> expectedFiles = new HashSet<String>();
        switch (platform)
        {
            case X86Win32:
            case X86_64Win32:
                expectedFiles.add("unnamed.exe");
                expectedFiles.add("game.public.der");
                expectedFiles.add("game.dmanifest");
                expectedFiles.add("OpenAL32.dll");
                expectedFiles.add("game.arci");
                expectedFiles.add("wrap_oal.dll");
                expectedFiles.add("game.arcd");
                expectedFiles.add("game.projectc");
                break;
            case JsWeb:
                expectedFiles.add("dmloader.js");
                expectedFiles.add("index.html");
                expectedFiles.add("unnamed_wasm.js");
                expectedFiles.add("unnamed.wasm");
                expectedFiles.add("unnamed_asmjs.js");
                expectedFiles.add("defold_sound.swf");
                expectedFiles.add("archive/game.arcd0");
                expectedFiles.add("archive/game.arci0");
                expectedFiles.add("archive/game.dmanifest0");
                expectedFiles.add("archive/game.projectc0");
                expectedFiles.add("archive/game.public.der0");
                expectedFiles.add("archive/archive_files.json");
                break;
            case Armv7Android:
            case Arm64Android:
                expectedFiles.add("classes.dex");
                expectedFiles.add("lib/armeabi-v7a/libunnamed.so");
                expectedFiles.add("AndroidManifest.xml");
                expectedFiles.add("assets/game.arcd");
                expectedFiles.add("assets/game.arci");
                expectedFiles.add("assets/game.dmanifest");
                expectedFiles.add("assets/game.projectc");
                expectedFiles.add("assets/game.public.der");
                expectedFiles.add("META-INF/CERT.RSA");
                expectedFiles.add("META-INF/CERT.SF");
                expectedFiles.add("META-INF/MANIFEST.MF");
                expectedFiles.add("res/drawable-hdpi-v4/icon.png");
                expectedFiles.add("res/drawable-ldpi-v4/icon.png");
                expectedFiles.add("res/drawable-mdpi-v4/icon.png");
                expectedFiles.add("res/drawable-xhdpi-v4/icon.png");
                expectedFiles.add("res/drawable-xxhdpi-v4/icon.png");
                expectedFiles.add("res/drawable-xxxhdpi-v4/icon.png");
                expectedFiles.add("resources.arsc");
                break;
            case Armv7Darwin:
            case Arm64Darwin:
            case X86_64Ios:
                expectedFiles.add("Payload/unnamed.app/unnamed");
                expectedFiles.add("Payload/unnamed.app/Info.plist");
                expectedFiles.add("Payload/unnamed.app/game.arcd");
                expectedFiles.add("Payload/unnamed.app/game.arci");
                expectedFiles.add("Payload/unnamed.app/game.dmanifest");
                expectedFiles.add("Payload/unnamed.app/game.projectc");
                expectedFiles.add("Payload/unnamed.app/game.public.der");
                expectedFiles.add("Payload/unnamed.app/Icon-167.png");
                expectedFiles.add("Payload/unnamed.app/Icon-60@2x.png");
                expectedFiles.add("Payload/unnamed.app/Icon-60@3x.png");
                expectedFiles.add("Payload/unnamed.app/Icon-72.png");
                expectedFiles.add("Payload/unnamed.app/Icon-72@2x.png");
                expectedFiles.add("Payload/unnamed.app/Icon-76.png");
                expectedFiles.add("Payload/unnamed.app/Icon-76@2x.png");
                expectedFiles.add("Payload/unnamed.app/Icon.png");
                expectedFiles.add("Payload/unnamed.app/Icon@2x.png");
                break;
            case X86_64Darwin:
                expectedFiles.add("Contents/MacOS/unnamed");
                expectedFiles.add("Contents/Info.plist");
                expectedFiles.add("Contents/Resources/game.arcd");
                expectedFiles.add("Contents/Resources/game.arci");
                expectedFiles.add("Contents/Resources/game.dmanifest");
                expectedFiles.add("Contents/Resources/game.projectc");
                expectedFiles.add("Contents/Resources/game.public.der");
                break;
            case X86_64Linux:
                expectedFiles.add("unnamed.x86_64");
                expectedFiles.add("game.arcd");
                expectedFiles.add("game.arci");
                expectedFiles.add("game.dmanifest");
                expectedFiles.add("game.projectc");
                expectedFiles.add("game.public.der");
                break;
            default:
                System.err.println("Expected file set is not implemented for this platform.");
                break;
        }

        return expectedFiles;
    }

    @Test
    public void testBundleResourcesDirs() throws IOException, ConfigurationException, CompileExceptionError, MultipleCompileException {

        /*
         * Project structure:
         *
         *  /
         *  +-/m.txt
         *  +-<built-ins> (from createDefaultFiles)
         *  +-custom/
         *  | EMPTY!
         *  +-sub1/
         *    +-common/
         *      +-s1-1.txt
         *      +-s1-2.txt
         *  +-sub2/
         *    +-[current-platform-arch]/
         *      +-s2-1.txt
         *      +-s2-2.txt
         */
        File cust = new File(contentRoot, "custom");
        cust.mkdir();
        File sub1 = new File(contentRoot, "sub1");
        File sub2 = new File(contentRoot, "sub2");
        sub1.mkdir();
        sub2.mkdir();
        File sub_platform1 = new File(sub1, "common"); // common is a platform
        File sub_platform2 = new File(sub2, this.platform.getExtenderPair());
        sub_platform1.mkdir();
        sub_platform2.mkdir();
        createDefaultFiles(contentRoot);
        createFile(contentRoot, "m.txt", "dummy");
        createFile(sub_platform1.getAbsolutePath(), "s1-1.txt", "dummy");
        createFile(sub_platform1.getAbsolutePath(), "s1-2.txt", "dummy");
        createFile(sub_platform2.getAbsolutePath(), "s2-1.txt", "dummy");
        createFile(sub_platform2.getAbsolutePath(), "s2-2.txt", "dummy");

        createFile(contentRoot, "game.project", "[project]\nbundle_resources=\n[display]\nwidth=640\nheight=480\n");
        build();
        HashSet<String> expectedFiles = getExpectedFilesForPlatform(platform);
        HashSet<String> actualFiles = new HashSet<String>();
        List<String> files = getBundleFiles();
        for (String file : files) {
            System.out.println(file);
            actualFiles.add(file);
        }
        assertEquals(expectedFiles.size(), files.size());
        assertEquals(expectedFiles, actualFiles);

        String appFolder = getBundleAppFolder("unnamed");

        createFile(contentRoot, "game.project", "[project]\nbundle_resources=/sub1\n[display]\nwidth=640\nheight=480\n");
        build();
        files = getBundleFiles();
        expectedFiles = getExpectedFilesForPlatform(platform);
        actualFiles = new HashSet<String>();
        for (String file : files) {
            System.out.println(file);
            actualFiles.add(file);
        }
        expectedFiles.add(appFolder + "s1-1.txt");
        expectedFiles.add(appFolder + "s1-2.txt");
        assertEquals(expectedFiles.size(), files.size());
        assertEquals(expectedFiles, actualFiles);


        createFile(contentRoot, "game.project", "[project]\nbundle_resources=/sub1,/sub2\n[display]\nwidth=640\nheight=480\n");
        build();
        files = getBundleFiles();
        expectedFiles = getExpectedFilesForPlatform(platform);
        actualFiles = new HashSet<String>();
        for (String file : files) {
            System.out.println(file);
            actualFiles.add(file);
        }
        expectedFiles.add(appFolder + "s1-1.txt");
        expectedFiles.add(appFolder + "s1-2.txt");
        expectedFiles.add(appFolder + "s2-1.txt");
        expectedFiles.add(appFolder + "s2-2.txt");

        assertEquals(expectedFiles.size(), files.size());
        assertEquals(expectedFiles, actualFiles);
    }
}
