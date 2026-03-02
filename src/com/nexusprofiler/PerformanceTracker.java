package com.nexusprofiler;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;

/**
 * PerformanceTracker - Collects performance metrics on the game thread.
 *
 * Runs as an EveryFrameScript. Per-frame work is minimal (~5 float ops).
 * Every 0.5 seconds, builds an immutable Snapshot and publishes it via
 * volatile write for lock-free cross-thread reads.
 */
public class PerformanceTracker implements EveryFrameScript {

    // ========================================================================
    // Immutable data types (thread-safe by construction)
    // ========================================================================

    public static final class Snapshot {
        public final float currentFps;
        public final float avgFrameTime;
        public final float minFps;
        public final float maxFps;
        public final float[] fpsHistory;
        public final int fpsHistoryCount;
        public final long heapUsed;
        public final long heapMax;
        public final long[] memoryHistory;
        public final int memoryHistoryCount;
        public final int gcPauseCount;
        public final float worstFrameTime;
        public final String[] recentSpikes;
        public final int spikeCount;
        public final int fleetSize;
        public final int colonyCount;
        public final int factionCount;
        public final boolean inCombat;

        public Snapshot(float currentFps, float avgFrameTime, float minFps, float maxFps,
                        float[] fpsHistory, int fpsHistoryCount,
                        long heapUsed, long heapMax,
                        long[] memoryHistory, int memoryHistoryCount,
                        int gcPauseCount, float worstFrameTime,
                        String[] recentSpikes, int spikeCount,
                        int fleetSize, int colonyCount, int factionCount, boolean inCombat) {
            this.currentFps = currentFps;
            this.avgFrameTime = avgFrameTime;
            this.minFps = minFps;
            this.maxFps = maxFps;
            this.fpsHistory = fpsHistory;
            this.fpsHistoryCount = fpsHistoryCount;
            this.heapUsed = heapUsed;
            this.heapMax = heapMax;
            this.memoryHistory = memoryHistory;
            this.memoryHistoryCount = memoryHistoryCount;
            this.gcPauseCount = gcPauseCount;
            this.worstFrameTime = worstFrameTime;
            this.recentSpikes = recentSpikes;
            this.spikeCount = spikeCount;
            this.fleetSize = fleetSize;
            this.colonyCount = colonyCount;
            this.factionCount = factionCount;
            this.inCombat = inCombat;
        }
    }

    // ========================================================================
    // Per-frame accumulators (game thread only — no synchronization needed)
    // ========================================================================

    private float frameTimeSum = 0f;
    private int frameCount = 0;

    // Snapshot interval
    private float snapshotTimer = 0f;
    private static final float SNAPSHOT_INTERVAL = 0.5f;

    // History circular buffers (game thread only)
    private static final int HISTORY_SIZE = 240; // 240 * 0.5s = 2 minutes
    private final float[] fpsHistoryBuf = new float[HISTORY_SIZE];
    private final long[] memHistoryBuf = new long[HISTORY_SIZE];
    private int historyIndex = 0;
    private int historyCount = 0;

    // Spike detection
    private float rollingAvgFrameTime = 0.016f; // start at ~60fps
    private static final float SPIKE_MULTIPLIER = 3.0f;
    private static final int MAX_SPIKES = 10;
    private final String[] spikeLog = new String[MAX_SPIKES];
    private int spikeLogIndex = 0;
    private int spikeLogCount = 0;
    private int gcPauseCount = 0;
    private float worstFrameTimeEver = 0f;

    // Lifetime FPS tracking
    private float allTimeMinFps = Float.MAX_VALUE;
    private float allTimeMaxFps = 0f;

    // Thread-safe publication
    private volatile Snapshot snapshot;

    // ========================================================================
    // Public API
    // ========================================================================

    public PerformanceTracker() {
    }

    public Snapshot getSnapshot() {
        return snapshot;
    }

    // ========================================================================
    // EveryFrameScript
    // ========================================================================

    public boolean isDone() { return false; }
    public boolean runWhilePaused() { return true; }

    public void advance(float amount) {
        if (amount <= 0f) return;

        // --- Per-frame work (minimal: ~5 float ops + 1 comparison) ---
        frameTimeSum += amount;
        frameCount++;

        float instantFps = 1f / amount;
        if (instantFps < allTimeMinFps) allTimeMinFps = instantFps;
        if (instantFps > allTimeMaxFps) allTimeMaxFps = instantFps;

        // Spike detection: frame > 3x rolling average
        if (amount > rollingAvgFrameTime * SPIKE_MULTIPLIER && rollingAvgFrameTime > 0.001f) {
            gcPauseCount++;
            if (amount > worstFrameTimeEver) worstFrameTimeEver = amount;

            String context = getGameContext();
            String desc = String.format("%.0fms spike (%s)", amount * 1000f, context);
            spikeLog[spikeLogIndex % MAX_SPIKES] = desc;
            spikeLogIndex++;
            if (spikeLogCount < MAX_SPIKES) spikeLogCount++;
        }

        // Update rolling average (EMA, alpha=0.1)
        rollingAvgFrameTime = rollingAvgFrameTime * 0.9f + amount * 0.1f;

        // --- Snapshot building (every 0.5s) ---
        snapshotTimer += amount;
        if (snapshotTimer < SNAPSHOT_INTERVAL) return;
        snapshotTimer = 0f;

        buildSnapshot();
    }

    // ========================================================================
    // Snapshot building (runs on game thread, every 0.5s)
    // ========================================================================

    private void buildSnapshot() {
        // Compute averages
        float avgFt = frameCount > 0 ? frameTimeSum / frameCount : 0.016f;
        float avgFps = frameCount > 0 ? (float) frameCount / (frameTimeSum > 0 ? frameTimeSum : 1f) : 0f;

        // Sample memory
        Runtime rt = Runtime.getRuntime();
        long heapUsed = rt.totalMemory() - rt.freeMemory();
        long heapMax = rt.maxMemory();

        // Push to circular buffers
        fpsHistoryBuf[historyIndex % HISTORY_SIZE] = avgFps;
        memHistoryBuf[historyIndex % HISTORY_SIZE] = heapUsed;
        historyIndex++;
        if (historyCount < HISTORY_SIZE) historyCount++;

        // Copy history (oldest-first) for the immutable snapshot
        float[] fpsCopy = new float[historyCount];
        long[] memCopy = new long[historyCount];
        for (int i = 0; i < historyCount; i++) {
            int idx = (historyIndex - historyCount + i + HISTORY_SIZE * 2) % HISTORY_SIZE;
            fpsCopy[i] = fpsHistoryBuf[idx];
            memCopy[i] = memHistoryBuf[idx];
        }

        // Copy spike log (newest-first)
        String[] spikeCopy = new String[spikeLogCount];
        for (int i = 0; i < spikeLogCount; i++) {
            int idx = (spikeLogIndex - 1 - i + MAX_SPIKES * 2) % MAX_SPIKES;
            spikeCopy[i] = spikeLog[idx];
        }

        // Read game state
        int fleetSize = 0;
        int colonyCount = 0;
        int factionCount = 0;
        boolean inCombat = false;
        try {
            SectorAPI sector = Global.getSector();
            if (sector != null) {
                CampaignFleetAPI fleet = sector.getPlayerFleet();
                if (fleet != null) {
                    fleetSize = fleet.getFleetData().getNumMembers();
                }

                FactionAPI playerFaction = sector.getPlayerFaction();
                for (MarketAPI market : sector.getEconomy().getMarketsCopy()) {
                    if (market.getFaction() == playerFaction && !market.isHidden()) {
                        colonyCount++;
                    }
                }

                factionCount = sector.getAllFactions().size();
                // Campaign EveryFrameScripts only run during campaign.
                // Global.getCombatEngine() is unreliable (persists after combat).
                // Detect dialog/battle prep via CampaignUI instead.
                inCombat = sector.getCampaignUI() != null
                        && sector.getCampaignUI().isShowingDialog();
            }
        } catch (Exception e) {
            // Safe fallback — don't crash the game
        }

        // Publish immutable snapshot via volatile write
        snapshot = new Snapshot(
            avgFps, avgFt,
            allTimeMinFps == Float.MAX_VALUE ? 0f : allTimeMinFps,
            allTimeMaxFps,
            fpsCopy, historyCount,
            heapUsed, heapMax,
            memCopy, historyCount,
            gcPauseCount, worstFrameTimeEver,
            spikeCopy, spikeLogCount,
            fleetSize, colonyCount, factionCount, inCombat
        );

        // Reset per-interval accumulators
        frameTimeSum = 0f;
        frameCount = 0;
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private String getGameContext() {
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) return "loading";
            String mode = "campaign";
            if (sector.getCampaignUI() != null && sector.getCampaignUI().isShowingDialog()) {
                mode = "dialog";
            }
            CampaignFleetAPI fleet = sector.getPlayerFleet();
            int ships = fleet != null ? fleet.getFleetData().getNumMembers() : 0;
            return mode + ", " + ships + " ships";
        } catch (Exception e) {
            return "unknown";
        }
    }
}
