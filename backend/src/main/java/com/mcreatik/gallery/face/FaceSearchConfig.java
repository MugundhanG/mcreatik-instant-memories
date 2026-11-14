package com.mcreatik.gallery.face;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mcreatik.gallery.config.GalleryProperties;

@Configuration
public class FaceSearchConfig {

    private static final Logger log = LoggerFactory.getLogger(FaceSearchConfig.class);

    /** Rekognition when face search is on and the AWS keys are set; otherwise the in-memory fake (no AWS calls ever). */
    @Bean
    FaceSearchProvider faceSearchProvider(GalleryProperties properties) {
        GalleryProperties.FaceSearch config = properties.faceSearch();
        if (config.enabled() && config.hasKeys()) {
            log.info("Face search: AWS Rekognition in {}", config.region());
            return new RekognitionFaceSearchProvider(config.region(), config.accessKeyId(), config.secretAccessKey(),
                    config.maxFacesPerPhoto());
        }
        if (config.enabled()) {
            log.warn("Face search is on but AWS_REKOGNITION_ACCESS_KEY_ID / _SECRET_ACCESS_KEY are not set: using the "
                    + "in-memory fake provider (it recognises nothing). Fine for a demo, wrong for production.");
        }
        return new FakeFaceSearchProvider();
    }
}
