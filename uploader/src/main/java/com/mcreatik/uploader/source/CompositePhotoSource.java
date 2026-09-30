package com.mcreatik.uploader.source;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Runs several sources at once (e.g. camera FTP + folder watching); the queue dedupes overlaps. */
public class CompositePhotoSource implements PhotoSource {

    private final List<PhotoSource> sources;

    public CompositePhotoSource(List<PhotoSource> sources) {
        this.sources = List.copyOf(sources);
    }

    @Override
    public void start(Consumer<Path> onPhoto) {
        sources.forEach(s -> s.start(onPhoto));
    }

    @Override
    public String describe() {
        return sources.stream().map(PhotoSource::describe).collect(Collectors.joining(" + "));
    }

    @Override
    public void close() {
        sources.forEach(PhotoSource::close);
    }
}
