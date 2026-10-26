package com.kafianet.control;

import android.os.ParcelFileDescriptor;
import com.kafianet.control.IShizukuProgressListener;

interface IShizukuFileService {
    // Reserved Shizuku UserService destroy transaction.
    void destroy() = 16777114;

    boolean copyDirectory(String source, String destination) = 1;
    int pathType(String path) = 2;
    String[] listChildren(String path) = 3;
    long fileLength(String path) = 4;
    ParcelFileDescriptor openFileForRead(String path) = 5;
    String copyDirectoryWithProgressAndVerify(String source, String destination, IShizukuProgressListener listener) = 6;
    boolean ping() = 7;
    void prepareCopy() = 8;
    void cancelCopy() = 9;
    String moveDirectoryWithProgressAndVerify(String source, String destination, IShizukuProgressListener listener) = 10;
}
