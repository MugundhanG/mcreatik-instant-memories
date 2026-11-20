package com.mcreatik.gallery.face;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The face recognition engine behind "Find my photos". One collection per event, so cleaning up an event is a single
 * {@link #deleteCollection}. Production uses AWS Rekognition; local runs without AWS keys and the tests use an in-memory fake.
 * Implementations never log or keep the image bytes they are given.
 */
public interface FaceSearchProvider {

    /** Creates the collection; already existing is fine. */
    void createCollection(String collection);

    /** Deletes the collection with all its faces; a missing collection is fine. */
    void deleteCollection(String collection);

    /** Adds the faces found in the photo (a JPEG) and returns their face ids (none if the photo has no faces). */
    List<String> indexFaces(String collection, UUID photoId, byte[] jpegBytes);

    /**
     * The faces in the collection that match the (largest) face in the image, best first (most similar first). A missing collection
     * gives an empty list.
     *
     * @throws NoFaceException the image has no face in it
     * @throws BadImageException the image is not a readable JPEG or PNG
     */
    List<FaceMatch> searchFaces(String collection, byte[] imageBytes, float threshold, int maxFaces);

    /**
     * The other face ids in the collection that look like this face (a face already in the collection: no image is
     * sent), best first. A missing collection or a face that is no longer in it gives an empty list.
     */
    List<String> searchFacesByFaceId(String collection, String faceId, float threshold, int maxFaces);

    void deleteFaces(String collection, Collection<String> faceIds);

    /** Names of the collections that start with the prefix (for the nightly orphan check). */
    List<String> listCollections(String prefix);

    /** A face in the collection that matched, and how sure the engine is (0 to 100). */
    record FaceMatch(String faceId, float similarity) {
    }

    /** No face in the selfie. */
    class NoFaceException extends RuntimeException {
        public NoFaceException() {
            super("No face found in the image");
        }
    }

    /**
     * The engine refused because of how the account is set up (permissions, an organization policy, wrong keys), not
     * because of this photo or selfie. Retrying soon is right; giving up on the photo is not.
     */
    class AccessException extends RuntimeException {
        public AccessException(String message) {
            super(message);
        }
    }

    /** True when {@code e} (or a cause) is an account setup refusal: AccessException, or AWS's access / key errors. */
    static boolean isAccessProblem(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof AccessException) {
                return true;
            }
            String name = t.getClass().getSimpleName();
            String message = String.valueOf(t.getMessage());
            if (name.equals("AccessDeniedException") || name.equals("UnrecognizedClientException")
                    || name.equals("InvalidSignatureException") || message.contains("is not authorized to perform")) {
                return true;
            }
        }
        return false;
    }

    /** The useful part of an access refusal for the dashboard: what was refused and by which policy (no keys in it). */
    static String describeAccessProblem(Throwable e) {
        String m = String.valueOf(e.getMessage());
        int at = m.indexOf("is not authorized to perform:");
        String text = at >= 0 ? m.substring(at) : m;
        int cut = text.indexOf(" (Service:");
        return (cut > 0 ? text.substring(0, cut) : text).trim();
    }

    /** Not an image the engine can read. */
    class BadImageException extends RuntimeException {
        public BadImageException(String message) {
            super(message);
        }
    }
}
