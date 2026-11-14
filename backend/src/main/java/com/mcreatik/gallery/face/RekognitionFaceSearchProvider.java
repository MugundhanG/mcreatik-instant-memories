package com.mcreatik.gallery.face;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.CreateCollectionRequest;
import software.amazon.awssdk.services.rekognition.model.DeleteCollectionRequest;
import software.amazon.awssdk.services.rekognition.model.DeleteFacesRequest;
import software.amazon.awssdk.services.rekognition.model.FaceRecord;
import software.amazon.awssdk.services.rekognition.model.Image;
import software.amazon.awssdk.services.rekognition.model.IndexFacesRequest;
import software.amazon.awssdk.services.rekognition.model.InvalidImageFormatException;
import software.amazon.awssdk.services.rekognition.model.InvalidParameterException;
import software.amazon.awssdk.services.rekognition.model.ListCollectionsRequest;
import software.amazon.awssdk.services.rekognition.model.ListCollectionsResponse;
import software.amazon.awssdk.services.rekognition.model.QualityFilter;
import software.amazon.awssdk.services.rekognition.model.ResourceAlreadyExistsException;
import software.amazon.awssdk.services.rekognition.model.ResourceNotFoundException;
import software.amazon.awssdk.services.rekognition.model.SearchFacesByImageRequest;
import software.amazon.awssdk.services.rekognition.model.SearchFacesByImageResponse;

/** AWS Rekognition (Mumbai, ap-south-1). Credentials belong to an IAM user limited to Rekognition. */
public class RekognitionFaceSearchProvider implements FaceSearchProvider {

    private static final int DELETE_BATCH = 4096;

    private final RekognitionClient client;
    private final int maxFacesPerPhoto;

    public RekognitionFaceSearchProvider(String region, String accessKeyId, String secretAccessKey, int maxFacesPerPhoto) {
        this.maxFacesPerPhoto = maxFacesPerPhoto;
        this.client = RekognitionClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    @Override
    public void createCollection(String collection) {
        try {
            client.createCollection(CreateCollectionRequest.builder().collectionId(collection).build());
        } catch (ResourceAlreadyExistsException e) {
            // Created before: fine.
        }
    }

    @Override
    public void deleteCollection(String collection) {
        try {
            client.deleteCollection(DeleteCollectionRequest.builder().collectionId(collection).build());
        } catch (ResourceNotFoundException e) {
            // Already gone: fine.
        }
    }

    @Override
    public List<String> indexFaces(String collection, UUID photoId, byte[] jpegBytes) {
        try {
            var response = client.indexFaces(IndexFacesRequest.builder().collectionId(collection)
                    .image(Image.builder().bytes(SdkBytes.fromByteArrayUnsafe(jpegBytes)).build())
                    .externalImageId(photoId.toString()).maxFaces(maxFacesPerPhoto)
                    .qualityFilter(QualityFilter.AUTO).build());
            List<String> ids = new ArrayList<>();
            for (FaceRecord record : response.faceRecords()) {
                ids.add(record.face().faceId());
            }
            return ids;
        } catch (InvalidImageFormatException e) {
            throw new BadImageException("Not a readable image");
        } catch (InvalidParameterException e) {
            if (noFace(e)) {
                return List.of();
            }
            throw new BadImageException("The image was refused");
        }
    }

    @Override
    public List<String> searchFaces(String collection, byte[] imageBytes, float threshold, int maxFaces) {
        try {
            SearchFacesByImageResponse response = client.searchFacesByImage(SearchFacesByImageRequest.builder()
                    .collectionId(collection).image(Image.builder().bytes(SdkBytes.fromByteArrayUnsafe(imageBytes)).build())
                    .faceMatchThreshold(threshold).maxFaces(maxFaces).qualityFilter(QualityFilter.AUTO).build());
            return response.faceMatches().stream().map(m -> m.face().faceId()).toList();
        } catch (ResourceNotFoundException e) {
            return List.of(); // nothing indexed yet
        } catch (InvalidImageFormatException e) {
            throw new BadImageException("Not a readable image");
        } catch (InvalidParameterException e) {
            if (noFace(e)) {
                throw new NoFaceException();
            }
            throw new BadImageException("The image was refused");
        }
    }

    @Override
    public void deleteFaces(String collection, Collection<String> faceIds) {
        List<String> all = new ArrayList<>(faceIds);
        try {
            for (int i = 0; i < all.size(); i += DELETE_BATCH) {
                client.deleteFaces(DeleteFacesRequest.builder().collectionId(collection)
                        .faceIds(all.subList(i, Math.min(all.size(), i + DELETE_BATCH))).build());
            }
        } catch (ResourceNotFoundException e) {
            // The collection is gone, so are its faces.
        }
    }

    @Override
    public List<String> listCollections(String prefix) {
        List<String> names = new ArrayList<>();
        String token = null;
        do {
            ListCollectionsResponse page = client.listCollections(ListCollectionsRequest.builder().nextToken(token).build());
            page.collectionIds().stream().filter(n -> n.startsWith(prefix)).forEach(names::add);
            token = page.nextToken();
        } while (token != null);
        return names;
    }

    private static boolean noFace(InvalidParameterException e) {
        String m = e.getMessage();
        return m != null && m.toLowerCase(java.util.Locale.ROOT).contains("no faces");
    }
}
