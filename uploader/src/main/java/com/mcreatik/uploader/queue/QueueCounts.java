package com.mcreatik.uploader.queue;

public record QueueCounts(int queued, int uploading, int done, int duplicate, int failed) {

    public int pending() {
        return queued + uploading;
    }

    public int total() {
        return queued + uploading + done + duplicate + failed;
    }
}
