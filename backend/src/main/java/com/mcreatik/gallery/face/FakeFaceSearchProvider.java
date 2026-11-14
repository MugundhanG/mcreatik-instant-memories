package com.mcreatik.gallery.face;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory stand-in for Rekognition: used when no AWS keys are set (local runs, demos) and by the tests.
 * Every photo "contains" one person named after a hash of its bytes, and a selfie matches the faces of the person
 * named after a hash of the selfie's bytes (so the same picture finds itself). Tests script people with
 * {@link #photoShows} and {@link #selfieShows}. Not for real use: it recognises nothing.
 */
public class FakeFaceSearchProvider implements FaceSearchProvider {

    private record Face(String faceId, UUID photoId, String person) {
    }

    private final Map<String, List<Face>> collections = new ConcurrentHashMap<>();
    private final Map<UUID, List<String>> photoPeople = new ConcurrentHashMap<>();
    private final Map<String, String> selfiePeople = new ConcurrentHashMap<>();
    private final Set<String> noFace = ConcurrentHashMap.newKeySet();

    // What the tests can check.
    public final List<String> createdCollections = new CopyOnWriteArrayList<>();
    public final List<String> deletedCollections = new CopyOnWriteArrayList<>();
    public final List<String> deletedFaceIds = new CopyOnWriteArrayList<>();
    public final List<String> searchedCollections = new CopyOnWriteArrayList<>();

    /** Forgets everything (between tests). */
    public void reset() {
        collections.clear();
        photoPeople.clear();
        selfiePeople.clear();
        noFace.clear();
        createdCollections.clear();
        deletedCollections.clear();
        deletedFaceIds.clear();
        searchedCollections.clear();
    }

    /** The photo shows these people (one face each). */
    public void photoShows(UUID photoId, String... people) {
        photoPeople.put(photoId, List.of(people));
    }

    /** The selfie with these exact bytes is of this person. */
    public void selfieShows(byte[] selfie, String person) {
        selfiePeople.put(hash(selfie), person);
    }

    /** The selfie with these exact bytes has no face in it. */
    public void selfieHasNoFace(byte[] selfie) {
        noFace.add(hash(selfie));
    }

    public boolean hasCollection(String collection) {
        return collections.containsKey(collection);
    }

    public int faceCount(String collection) {
        return collections.getOrDefault(collection, List.of()).size();
    }

    public Set<String> faceIds(String collection) {
        Set<String> ids = new HashSet<>();
        collections.getOrDefault(collection, List.of()).forEach(f -> ids.add(f.faceId()));
        return ids;
    }

    @Override
    public void createCollection(String collection) {
        if (collections.putIfAbsent(collection, new CopyOnWriteArrayList<>()) == null) {
            createdCollections.add(collection);
        }
    }

    @Override
    public void deleteCollection(String collection) {
        collections.remove(collection);
        deletedCollections.add(collection);
    }

    @Override
    public List<String> indexFaces(String collection, UUID photoId, byte[] jpegBytes) {
        List<Face> faces = collections.get(collection);
        if (faces == null) {
            throw new IllegalStateException("No such collection " + collection);
        }
        List<String> people = photoPeople.getOrDefault(photoId, List.of("p-" + hash(jpegBytes)));
        List<String> ids = new ArrayList<>();
        for (String person : people) {
            String id = UUID.randomUUID().toString();
            faces.add(new Face(id, photoId, person));
            ids.add(id);
        }
        return ids;
    }

    @Override
    public List<String> searchFaces(String collection, byte[] imageBytes, float threshold, int maxFaces) {
        searchedCollections.add(collection);
        String key = hash(imageBytes);
        if (noFace.contains(key)) {
            throw new NoFaceException();
        }
        List<Face> faces = collections.get(collection);
        if (faces == null) {
            return List.of();
        }
        String person = selfiePeople.getOrDefault(key, "p-" + key);
        return faces.stream().filter(f -> f.person().equals(person)).map(Face::faceId).limit(maxFaces).toList();
    }

    @Override
    public void deleteFaces(String collection, Collection<String> faceIds) {
        List<Face> faces = collections.get(collection);
        deletedFaceIds.addAll(faceIds);
        if (faces != null) {
            faces.removeIf(f -> faceIds.contains(f.faceId()));
        }
    }

    @Override
    public List<String> listCollections(String prefix) {
        return collections.keySet().stream().filter(n -> n.startsWith(prefix)).toList();
    }

    /** Test helper: a collection that exists without being created through the app (an orphan). */
    public void plantCollection(String collection) {
        collections.putIfAbsent(collection, new CopyOnWriteArrayList<>());
    }

    private static String hash(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
