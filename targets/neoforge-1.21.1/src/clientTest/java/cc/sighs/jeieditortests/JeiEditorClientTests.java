package cc.sighs.jeieditortests;

import com.google.gson.JsonObject;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.common.Internal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Development-only client test mod for the JEI Editor target.
 *
 * <p>It is compiled as a second mod ({@code jeieditortests}) that only the
 * {@code clientTest} Gradle run loads; it is never part of {@code sourceSets.main}
 * and therefore never part of the published jar. The runner:
 *
 * <ol>
 *   <li>waits for the JEI runtime. JEI only creates it after a world join, so
 *       the runner creates a throwaway world once the title screen appears -
 *       the same technique the temporary category diagnostic uses;</li>
 *   <li>walks every registered recipe page on the render thread
 *       ({@link RecipePageClientTest});</li>
 *   <li>writes the JSON report to {@code jeieditortests.report}. The
 *       {@code scripts/jei-client-tests.ps1} harness waits for that file and
 *       stops the client, so the runner leaves the game running.</li>
 * </ol>
 */
@Mod(value = JeiEditorClientTests.MOD_ID, dist = Dist.CLIENT)
public final class JeiEditorClientTests {
    public static final String MOD_ID = "jeieditortests";

    private static final String REPORT_PROPERTY = "jeieditortests.report";
    private static final String WIRED_PAGES_PROPERTY = "jeieditortests.wiredPages";
    private static final String PROGRESS_PROPERTY = "jeieditortests.progress";
    private static final String DEFAULT_REPORT = "jei-client-tests-report.json";
    private static final String DEFAULT_WIRED_PAGES = "jei-client-tests-wired-pages.txt";
    private static final String DEFAULT_PROGRESS = "jei-client-tests-progress.txt";
    private static final int PROGRESS_INTERVAL_TICKS = 20;

    public JeiEditorClientTests() {
        NeoForge.EVENT_BUS.register(new Runner());
    }

    private static Path pathProperty(String key, String fallback) {
        String value = System.getProperty(key);
        return Paths.get(value == null || value.isEmpty() ? fallback : value);
    }

    private static Path reportPath() {
        return pathProperty(REPORT_PROPERTY, DEFAULT_REPORT);
    }

    private static Path wiredPagesPath() {
        return pathProperty(WIRED_PAGES_PROPERTY, DEFAULT_WIRED_PAGES);
    }

    private static Path progressPath() {
        return pathProperty(PROGRESS_PROPERTY, DEFAULT_PROGRESS);
    }

    /** Drives the test run from the client tick. */
    private static final class Runner {
        private boolean joinAttempted;
        private boolean finished;
        private int ticks;

        @SubscribeEvent
        public void onClientTick(ClientTickEvent.Post event) {
            // The operator shares this desktop with the run. Joining the throwaway world
            // makes the game grab and centre the cursor, which confines the operator's
            // mouse to the game window for as long as the run lives - so the release has
            // to happen every tick, including after the report is written (the harness
            // stops the client a little later), not only at the end. This is the same
            // "hand the cursor back" step the AutoJS harness runs after every drive call;
            // nothing in this runner is driven by the physical mouse, so releasing it
            // cannot affect the walk.
            releaseGrabbedMouse();
            if (finished) {
                return;
            }
            ticks++;
            Optional<IJeiRuntime> runtime = Internal.getOptionalJeiRuntime();
            if (runtime.isPresent()) {
                // JEI is up: run the walk and write the report. The game keeps
                // running; the PowerShell harness stops it once the report exists.
                runTests(runtime.get());
                finished = true;
                return;
            }
            if (ticks % PROGRESS_INTERVAL_TICKS == 0) {
                writeProgress("waiting for the JEI runtime");
            }
            startTestWorld();
        }

        private void runTests(IJeiRuntime runtime) {
            Path reportPath = reportPath();
            Path wiredPagesPath = wiredPagesPath();
            List<String> wiredPages = readLines(wiredPagesPath);
            System.out.println("[JEI-CLIENT-TESTS] mods loaded: " + modListSize()
                    + ", wired pages: " + wiredPages.size() + " (from " + wiredPagesPath + ")");
            writeProgress("running the recipe page walk");
            JsonObject report;
            try {
                Set<String> wired = new TreeSet<String>(wiredPages);
                report = ClientTestUtil.computeOnClient(minecraft ->
                        RecipePageClientTest.run(runtime, wired, wiredPagesPath.toString()));
            } catch (Throwable throwable) {
                report = RecipePageClientTest.errorReport(throwable);
            }
            try {
                ClientTestUtil.writeJson(reportPath, report);
                System.out.println("[JEI-CLIENT-TESTS] report written to " + reportPath.toAbsolutePath()
                        + " passed=" + report.get("passed"));
            } catch (Throwable throwable) {
                System.out.println("[JEI-CLIENT-TESTS] could not write " + reportPath.toAbsolutePath()
                        + ": " + throwable);
            }
            writeProgress("finished");
        }

        /**
         * JEI only creates its runtime after the client joins a world. An
         * existing save may ask for a backup confirmation, which breaks a
         * programmatic load, so the runner always creates a fresh throwaway
         * world inside the run's own game directory.
         */
        private void startTestWorld() {
            Minecraft minecraft = Minecraft.getInstance();
            if (joinAttempted) {
                return;
            }
            if (minecraft.screen instanceof AccessibilityOnboardingScreen) {
                // The run has its own game directory, so its first start shows
                // the accessibility onboarding screen and would wait for a
                // click forever. Skip it and create the world on the next tick.
                minecraft.setScreen(new TitleScreen());
                return;
            }
            if (!(minecraft.screen instanceof TitleScreen)) {
                return;
            }
            joinAttempted = true;
            try {
                String levelId = "jei-client-test-" + Long.toHexString(System.currentTimeMillis());
                LevelSettings settings = new LevelSettings(levelId, GameType.CREATIVE, false,
                        Difficulty.NORMAL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
                System.out.println("[JEI-CLIENT-TESTS] creating the test world " + levelId);
                minecraft.createWorldOpenFlows().createFreshLevel(levelId, settings,
                        WorldOptions.defaultWithRandomSeed(), WorldPresets::createNormalWorldDimensions,
                        minecraft.screen);
            } catch (Throwable throwable) {
                System.out.println("[JEI-CLIENT-TESTS] the test world could not be created: " + throwable);
            }
        }

        private void writeProgress(String state) {
            try {
                Minecraft minecraft = Minecraft.getInstance();
                String screen = minecraft.screen == null ? "none" : minecraft.screen.getClass().getSimpleName();
                ClientTestUtil.writeText(progressPath(), "ticks=" + ticks
                        + "\nruntime=" + Internal.getOptionalJeiRuntime().isPresent()
                        + "\njoin=" + joinAttempted
                        + "\nscreen=" + screen
                        + "\nmods=" + modListSize()
                        + "\nstate=" + state + "\n");
            } catch (Throwable ignored) {
                // Progress output is a convenience only.
            }
        }
    }

    private static List<String> readLines(Path path) {
        if (!Files.isRegularFile(path)) {
            System.out.println("[JEI-CLIENT-TESTS] wired page list missing: " + path.toAbsolutePath());
            return new ArrayList<String>();
        }
        try {
            List<String> lines = new ArrayList<String>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    lines.add(trimmed);
                }
            }
            return lines;
        } catch (IOException exception) {
            System.out.println("[JEI-CLIENT-TESTS] could not read " + path.toAbsolutePath()
                    + ": " + exception);
            return new ArrayList<String>();
        }
    }

    private static int modListSize() {
        ModList modList = ModList.get();
        return modList == null ? -1 : modList.getMods().size();
    }

    /**
     * Hands the cursor back to the operator when the game is holding it. Called every
     * tick, because the game re-grabs it while it sits in the throwaway world.
     */
    private static void releaseGrabbedMouse() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        MouseHandler mouseHandler = minecraft.mouseHandler;
        if (mouseHandler != null && mouseHandler.isMouseGrabbed()) {
            mouseHandler.releaseMouse();
        }
    }
}
