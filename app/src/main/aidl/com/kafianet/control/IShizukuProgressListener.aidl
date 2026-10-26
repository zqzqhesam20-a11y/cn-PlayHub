package com.kafianet.control;

oneway interface IShizukuProgressListener {
    void onProgress(long bytesDone, long totalBytes, int filesDone, int totalFiles, String currentFile);
}
