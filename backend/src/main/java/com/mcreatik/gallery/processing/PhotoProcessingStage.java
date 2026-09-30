package com.mcreatik.gallery.processing;

/**
 * One step of the photo processing pipeline. Stages are Spring beans ordered with {@code @Order}.
 * Adding a stage (e.g. a future face-detection step) means adding a bean, not editing the core.
 */
public interface PhotoProcessingStage {

    void process(PhotoProcessingContext context) throws Exception;

    default String name() {
        return getClass().getSimpleName();
    }
}
