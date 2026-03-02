package com.nexusprofiler;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.nexusui.api.NexusPage;
import com.nexusui.api.NexusPageFactory;
import com.nexusui.overlay.NexusFrame;
import org.apache.log4j.Logger;

public class ProfilerModPlugin extends BaseModPlugin {

    private static final Logger log = Logger.getLogger(ProfilerModPlugin.class);

    public void onApplicationLoad() throws Exception {
        log.info("NexusProfiler: Loaded");
    }

    public void onGameLoad(boolean newGame) {
        final PerformanceTracker tracker = new PerformanceTracker();
        Global.getSector().addTransientScript(tracker);

        NexusFrame.registerPageFactory(new NexusPageFactory() {
            public String getId() { return "nexus_profiler"; }
            public String getTitle() { return "Profiler"; }
            public NexusPage create() { return new ProfilerPage(tracker); }
        });
        log.info("NexusProfiler: Performance profiler page registered");
    }
}
