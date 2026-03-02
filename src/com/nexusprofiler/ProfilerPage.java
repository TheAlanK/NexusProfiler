package com.nexusprofiler;

import com.nexusui.api.NexusPage;
import com.nexusui.overlay.NexusFrame;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * ProfilerPage - Real-time performance profiler built on NexusUI.
 *
 * Displays FPS graph, memory usage, installed mods, game state metrics,
 * and heuristic diagnostics to identify performance bottlenecks.
 */
public class ProfilerPage implements NexusPage {

    private final PerformanceTracker tracker;
    private ProfilerPanel panel;

    private volatile PerformanceTracker.Snapshot data;

    public ProfilerPage(PerformanceTracker tracker) {
        this.tracker = tracker;
    }

    public String getId() { return "nexus_profiler"; }
    public String getTitle() { return "Profiler"; }

    public JPanel createPanel(int port) {
        panel = new ProfilerPanel();
        return panel;
    }

    public void refresh() {
        data = tracker.getSnapshot();
    }

    // ========================================================================
    // ProfilerPanel - Custom painted performance dashboard
    // ========================================================================
    private class ProfilerPanel extends JPanel {

        ProfilerPanel() {
            setBackground(NexusFrame.BG_PRIMARY);
        }

        public Dimension getPreferredSize() {
            return new Dimension(940, 950);
        }

        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

            PerformanceTracker.Snapshot snap = data;
            if (snap == null) {
                g2.setFont(NexusFrame.FONT_BODY);
                g2.setColor(NexusFrame.TEXT_MUTED);
                g2.drawString("Collecting performance data...", 20, 40);
                g2.dispose();
                return;
            }

            int padding = 16;
            int cardGap = 12;
            int totalW = getWidth() - padding * 2;
            int halfW = (totalW - cardGap) / 2;
            int y = padding;

            // Header bar
            y = drawHeaderBar(g2, padding, y, totalW, snap);
            y += cardGap;

            // Row 1: Frame Rate | Memory
            int row1Y = y;
            int h1 = drawFpsCard(g2, padding, row1Y, halfW, snap);
            int h2 = drawMemoryCard(g2, padding + halfW + cardGap, row1Y, halfW, snap);
            y += Math.max(h1, h2) + cardGap;

            // Row 2: Game State (full width)
            int h3 = drawGameStateCard(g2, padding, y, totalW, snap);
            y += h3 + cardGap;

            // Row 3: Diagnostics (full width)
            int h5 = drawDiagnosticsCard(g2, padding, y, totalW, snap);
            y += h5 + padding;

            // Update preferred size for scrolling
            Dimension pref = getPreferredSize();
            if (y > pref.height) {
                setPreferredSize(new Dimension(pref.width, y));
                revalidate();
            }

            g2.dispose();
        }

        // ====================================================================
        // Header Bar
        // ====================================================================
        private int drawHeaderBar(Graphics2D g2, int x, int y, int w,
                                   PerformanceTracker.Snapshot snap) {
            int h = 50;
            NexusFrame.drawCardBg(g2, x, y, w, h);

            // Title
            g2.setFont(NexusFrame.FONT_TITLE);
            g2.setColor(NexusFrame.TEXT_PRIMARY);
            String t1 = "PERFORMANCE";
            g2.drawString(t1, x + 16, y + 22);
            g2.setColor(NexusFrame.CYAN);
            int tw = g2.getFontMetrics().stringWidth(t1);
            g2.drawString(" PROFILER", x + 16 + tw, y + 22);

            // Right-side stats
            g2.setFont(NexusFrame.FONT_MONO);
            int rx = x + w - 16;

            // FPS (color-coded)
            Color fpsColor = snap.currentFps >= 50 ? NexusFrame.GREEN :
                             snap.currentFps >= 30 ? NexusFrame.ORANGE :
                             NexusFrame.RED;
            String fpsStr = String.format("FPS: %.1f", snap.currentFps);
            g2.setColor(fpsColor);
            int fpsW = g2.getFontMetrics().stringWidth(fpsStr);
            g2.drawString(fpsStr, rx - fpsW, y + 22);

            // Frame time
            String ftStr = String.format("Frame: %.1fms", snap.avgFrameTime * 1000f);
            g2.setColor(NexusFrame.TEXT_SECONDARY);
            int ftW = g2.getFontMetrics().stringWidth(ftStr);
            g2.drawString(ftStr, rx - fpsW - ftW - 24, y + 22);

            // Status
            g2.setFont(NexusFrame.FONT_SMALL);
            g2.setColor(NexusFrame.TEXT_MUTED);
            String status = snap.inCombat ? "Mode: DIALOG" : "Mode: CAMPAIGN";
            g2.drawString(status, x + 16, y + 38);

            // Memory summary
            String memStr = formatBytes(snap.heapUsed) + " / " + formatBytes(snap.heapMax);
            g2.setColor(NexusFrame.TEXT_MUTED);
            int memW = g2.getFontMetrics().stringWidth(memStr);
            g2.drawString(memStr, rx - memW, y + 38);

            return h;
        }

        // ====================================================================
        // FPS Card with Line Graph
        // ====================================================================
        private int drawFpsCard(Graphics2D g2, int x, int y, int w,
                                 PerformanceTracker.Snapshot snap) {
            int headerH = 32;
            int graphH = 150;
            int statsH = 30;
            int bodyPad = 12;
            int cardH = headerH + bodyPad + graphH + statsH + bodyPad;

            NexusFrame.drawCardBg(g2, x, y, w, cardH);
            NexusFrame.drawCardHeader(g2, x, y, w, "FRAME RATE", "2 min history");

            int gx = x + bodyPad;
            int gy = y + headerH + bodyPad;
            int gw = w - bodyPad * 2;

            drawLineGraph(g2, gx, gy, gw, graphH,
                          snap.fpsHistory, snap.fpsHistoryCount,
                          0f, 80f,
                          NexusFrame.CYAN,
                          new float[]{30f, 60f},
                          new String[]{"30", "60"});

            // Stats row
            int sy = gy + graphH + 6;
            g2.setFont(NexusFrame.FONT_SMALL);

            g2.setColor(NexusFrame.TEXT_SECONDARY);
            g2.drawString("Avg:", gx, sy + 12);
            g2.setColor(NexusFrame.CYAN);
            g2.drawString(String.format("%.1f", snap.currentFps), gx + 28, sy + 12);

            g2.setColor(NexusFrame.TEXT_SECONDARY);
            g2.drawString("Min:", gx + 100, sy + 12);
            g2.setColor(snap.minFps < 30 ? NexusFrame.RED : NexusFrame.TEXT_PRIMARY);
            g2.drawString(String.format("%.0f", snap.minFps), gx + 128, sy + 12);

            g2.setColor(NexusFrame.TEXT_SECONDARY);
            g2.drawString("Max:", gx + 190, sy + 12);
            g2.setColor(NexusFrame.TEXT_PRIMARY);
            g2.drawString(String.format("%.0f", snap.maxFps), gx + 218, sy + 12);

            // GC spike count
            g2.setColor(NexusFrame.TEXT_SECONDARY);
            g2.drawString("Spikes:", gx + 280, sy + 12);
            g2.setColor(snap.gcPauseCount > 0 ? NexusFrame.ORANGE : NexusFrame.GREEN);
            g2.drawString(String.valueOf(snap.gcPauseCount), gx + 328, sy + 12);

            return cardH;
        }

        // ====================================================================
        // Memory Card with Progress Bar + Line Graph
        // ====================================================================
        private int drawMemoryCard(Graphics2D g2, int x, int y, int w,
                                    PerformanceTracker.Snapshot snap) {
            int headerH = 32;
            int barSection = 28;
            int graphH = 130;
            int bodyPad = 12;
            int cardH = headerH + bodyPad + barSection + 8 + graphH + bodyPad;

            NexusFrame.drawCardBg(g2, x, y, w, cardH);
            NexusFrame.drawCardHeader(g2, x, y, w, "MEMORY", "JVM Heap");

            // Memory progress bar
            int by = y + headerH + bodyPad;
            float pct = snap.heapMax > 0 ? (float) snap.heapUsed / snap.heapMax : 0f;
            Color barColor = pct > 0.85f ? NexusFrame.RED :
                             pct > 0.70f ? NexusFrame.ORANGE : NexusFrame.GREEN;

            String label = formatBytes(snap.heapUsed) + " / " + formatBytes(snap.heapMax);
            String pctStr = String.format("%.0f%%", pct * 100f);

            NexusFrame.drawLabeledBar(g2, x + bodyPad, by, w - bodyPad * 2, 20,
                                          label, pctStr, pct, barColor);

            // Memory graph
            int gy = by + barSection + 8;
            int gw = w - bodyPad * 2;

            // Convert to MB for graph
            float[] memFloat = new float[snap.memoryHistoryCount];
            for (int i = 0; i < snap.memoryHistoryCount; i++) {
                memFloat[i] = snap.memoryHistory[i] / (1024f * 1024f);
            }
            float maxMB = snap.heapMax / (1024f * 1024f);
            if (maxMB <= 0) maxMB = 1024f;

            drawLineGraph(g2, x + bodyPad, gy, gw, graphH,
                          memFloat, snap.memoryHistoryCount,
                          0f, maxMB,
                          NexusFrame.ORANGE,
                          new float[]{maxMB * 0.25f, maxMB * 0.50f, maxMB * 0.75f},
                          new String[]{"25%", "50%", "75%"});

            return cardH;
        }

        // ====================================================================
        // Game State Card
        // ====================================================================
        private int drawGameStateCard(Graphics2D g2, int x, int y, int w,
                                       PerformanceTracker.Snapshot snap) {
            int headerH = 32;
            int bodyPad = 12;
            int lineH = 22;
            int lines = 5;
            int cardH = headerH + bodyPad * 2 + lineH * lines;

            NexusFrame.drawCardBg(g2, x, y, w, cardH);
            NexusFrame.drawCardHeader(g2, x, y, w, "GAME STATE", "");

            int by = y + headerH + bodyPad;
            g2.setFont(NexusFrame.FONT_MONO);

            drawStatRow(g2, x + bodyPad, by, w - bodyPad * 2,
                        "Fleet:", snap.fleetSize + " ships",
                        snap.fleetSize > 20 ? NexusFrame.ORANGE : NexusFrame.TEXT_PRIMARY);
            by += lineH;

            drawStatRow(g2, x + bodyPad, by, w - bodyPad * 2,
                        "Colonies:", String.valueOf(snap.colonyCount),
                        snap.colonyCount > 5 ? NexusFrame.ORANGE : NexusFrame.TEXT_PRIMARY);
            by += lineH;

            drawStatRow(g2, x + bodyPad, by, w - bodyPad * 2,
                        "Factions:", String.valueOf(snap.factionCount),
                        NexusFrame.TEXT_PRIMARY);
            by += lineH;

            drawStatRow(g2, x + bodyPad, by, w - bodyPad * 2,
                        "Mode:", snap.inCombat ? "Dialog" : "Campaign",
                        snap.inCombat ? NexusFrame.ORANGE : NexusFrame.CYAN);
            by += lineH;

            // Worst spike
            String worstStr = snap.worstFrameTime > 0
                ? String.format("%.0fms", snap.worstFrameTime * 1000f)
                : "None";
            drawStatRow(g2, x + bodyPad, by, w - bodyPad * 2,
                        "Worst spike:", worstStr,
                        snap.worstFrameTime > 0.050f ? NexusFrame.RED :
                        snap.worstFrameTime > 0 ? NexusFrame.ORANGE :
                        NexusFrame.GREEN);

            return cardH;
        }

        private void drawStatRow(Graphics2D g2, int x, int y, int w,
                                  String label, String value, Color valueColor) {
            g2.setColor(NexusFrame.TEXT_SECONDARY);
            g2.drawString(label, x, y + 14);
            g2.setColor(valueColor);
            int valW = g2.getFontMetrics().stringWidth(value);
            g2.drawString(value, x + w - valW, y + 14);
        }

        // ====================================================================
        // Diagnostics Card (full width, heuristic warning engine)
        // ====================================================================
        private int drawDiagnosticsCard(Graphics2D g2, int x, int y, int w,
                                         PerformanceTracker.Snapshot snap) {
            int headerH = 32;
            int bodyPad = 12;
            int lineH = 22;

            // Build warnings list: {message, severity}
            // severity: "error" = red, "warn" = orange, "ok" = green
            List warnings = new ArrayList();

            // Memory check
            float memPct = snap.heapMax > 0 ? (float) snap.heapUsed / snap.heapMax : 0f;
            if (memPct > 0.80f) {
                warnings.add(new String[]{
                    String.format("Memory at %.0f%% -- GC stalls likely", memPct * 100f), "error"});
            }

            // FPS check
            if (snap.currentFps > 0 && snap.currentFps < 30f) {
                warnings.add(new String[]{
                    String.format("Low frame rate (%.0f FPS) -- game may feel sluggish", snap.currentFps), "error"});
            }

            // Fleet size
            if (snap.fleetSize > 20) {
                warnings.add(new String[]{
                    "Fleet has " + snap.fleetSize + " ships -- may impact campaign performance", "warn"});
            }

            // Colonies
            if (snap.colonyCount > 5) {
                warnings.add(new String[]{
                    snap.colonyCount + " colonies increase economy update cost", "warn"});
            }

            // GC pauses
            if (snap.gcPauseCount > 0) {
                warnings.add(new String[]{
                    snap.gcPauseCount + " frame spikes detected (worst: " +
                    String.format("%.0fms", snap.worstFrameTime * 1000f) + ")", "warn"});
            }

            // Frame variance
            float variance = 0f;
            if (snap.fpsHistoryCount > 10) {
                variance = computeVariance(snap.fpsHistory, snap.fpsHistoryCount);
                if (variance > 100f) {
                    warnings.add(new String[]{
                        "Inconsistent frame times -- possible background processing", "warn"});
                }
            }

            // Positive indicators
            if (snap.fpsHistoryCount > 10 && variance <= 100f && snap.currentFps >= 50f) {
                warnings.add(new String[]{"Frame rate stable (low variance)", "ok"});
            }

            if (warnings.isEmpty()) {
                warnings.add(new String[]{"All systems nominal", "ok"});
            }

            int cardH = headerH + bodyPad * 2 + lineH * warnings.size();
            NexusFrame.drawCardBg(g2, x, y, w, cardH);
            NexusFrame.drawCardHeader(g2, x, y, w, "DIAGNOSTICS",
                                          warnings.size() + " items");

            int by = y + headerH + bodyPad;
            g2.setFont(NexusFrame.FONT_MONO);

            for (int i = 0; i < warnings.size(); i++) {
                String[] warn = (String[]) warnings.get(i);
                String severity = warn[1];

                Color iconColor;
                String icon;
                if ("error".equals(severity)) {
                    iconColor = NexusFrame.RED;
                    icon = "!!";
                } else if ("warn".equals(severity)) {
                    iconColor = NexusFrame.ORANGE;
                    icon = "!>";
                } else {
                    iconColor = NexusFrame.GREEN;
                    icon = "OK";
                }

                g2.setColor(iconColor);
                g2.drawString(icon, x + bodyPad, by + 14);

                g2.setColor("ok".equals(severity) ? NexusFrame.GREEN : NexusFrame.TEXT_PRIMARY);
                g2.drawString(warn[0], x + bodyPad + 24, by + 14);

                by += lineH;
            }

            return cardH;
        }

        // ====================================================================
        // Custom Line Graph
        // ====================================================================
        private void drawLineGraph(Graphics2D g2, int x, int y, int w, int h,
                                    float[] values, int count,
                                    float yMin, float yMax,
                                    Color lineColor,
                                    float[] gridValues,
                                    String[] gridLabels) {
            int labelMargin = 32;
            int graphX = x + labelMargin;
            int graphW = w - labelMargin;

            if (graphW <= 0 || h <= 0) return;

            // Background
            g2.setColor(new Color(12, 16, 28));
            g2.fillRect(graphX, y, graphW, h);

            float yRange = yMax - yMin;
            if (yRange <= 0) yRange = 1f;

            // Grid lines (dashed)
            Stroke oldStroke = g2.getStroke();
            g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT,
                         BasicStroke.JOIN_MITER, 10f,
                         new float[]{4f, 4f}, 0f));
            g2.setFont(NexusFrame.FONT_SMALL);

            if (gridValues != null) {
                for (int i = 0; i < gridValues.length; i++) {
                    float normalY = (gridValues[i] - yMin) / yRange;
                    int gy = y + h - (int)(normalY * h);
                    if (gy < y || gy > y + h) continue;

                    g2.setColor(NexusFrame.BORDER);
                    g2.drawLine(graphX, gy, graphX + graphW, gy);

                    if (gridLabels != null && i < gridLabels.length) {
                        g2.setColor(NexusFrame.TEXT_MUTED);
                        g2.drawString(gridLabels[i], x, gy + 4);
                    }
                }
            }
            g2.setStroke(oldStroke);

            // Data line
            if (count >= 2) {
                g2.setColor(lineColor);
                g2.setStroke(new BasicStroke(2f));
                int prevPx = -1, prevPy = -1;
                for (int i = 0; i < count; i++) {
                    float t = (float) i / (count - 1);
                    int px = graphX + (int)(t * graphW);

                    float normalY = (values[i] - yMin) / yRange;
                    if (normalY < 0f) normalY = 0f;
                    if (normalY > 1f) normalY = 1f;
                    int py = y + h - (int)(normalY * h);

                    if (prevPx >= 0) {
                        g2.drawLine(prevPx, prevPy, px, py);
                    }
                    prevPx = px;
                    prevPy = py;
                }
                g2.setStroke(oldStroke);
            } else {
                // No data yet
                g2.setFont(NexusFrame.FONT_SMALL);
                g2.setColor(NexusFrame.TEXT_MUTED);
                g2.drawString("Waiting for data...", graphX + 10, y + h / 2);
            }

            // Border
            g2.setColor(NexusFrame.BORDER);
            g2.drawRect(graphX, y, graphW, h);
        }

        // ====================================================================
        // Utilities
        // ====================================================================

        private float computeVariance(float[] values, int count) {
            if (count < 2) return 0f;
            float sum = 0f;
            for (int i = 0; i < count; i++) sum += values[i];
            float mean = sum / count;
            float varSum = 0f;
            for (int i = 0; i < count; i++) {
                float diff = values[i] - mean;
                varSum += diff * diff;
            }
            return varSum / count;
        }

        private String formatBytes(long bytes) {
            if (bytes >= 1024L * 1024L * 1024L) {
                return String.format("%.1fG", bytes / (1024.0 * 1024.0 * 1024.0));
            }
            if (bytes >= 1024L * 1024L) {
                return String.format("%.0fM", bytes / (1024.0 * 1024.0));
            }
            return String.format("%.0fK", bytes / 1024.0);
        }
    }
}
