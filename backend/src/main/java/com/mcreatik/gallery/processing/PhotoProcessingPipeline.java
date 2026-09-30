package com.mcreatik.gallery.processing;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs the ordered stages for one photo:
 * Validate → Decode → Optimized web image → Thumbnail → (future: face processing runs after READY, asynchronously).
 */
@Component
public class PhotoProcessingPipeline {

    private static final Logger log = LoggerFactory.getLogger(PhotoProcessingPipeline.class);

    private final List<PhotoProcessingStage> stages;

    public PhotoProcessingPipeline(List<PhotoProcessingStage> stages) {
        this.stages = stages;
        log.info("Photo processing pipeline: {}", stages.stream().map(PhotoProcessingStage::name).toList());
    }

    public void run(PhotoProcessingContext context) throws Exception {
        for (PhotoProcessingStage stage : stages) {
            long start = System.nanoTime();
            stage.process(context);
            if (log.isDebugEnabled()) {
                log.debug("{} {} took {} ms", context.photoId(), stage.name(), (System.nanoTime() - start) / 1_000_000);
            }
        }
    }

    public List<String> stageNames() {
        return stages.stream().map(PhotoProcessingStage::name).toList();
    }
}
